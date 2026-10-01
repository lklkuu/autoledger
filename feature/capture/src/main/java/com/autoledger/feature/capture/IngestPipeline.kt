package com.autoledger.feature.capture

import com.autoledger.core.crypto.CryptoBox
import com.autoledger.core.model.ClassificationContext
import com.autoledger.core.model.DuplicateCandidate
import com.autoledger.core.model.LedgerRepository
import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.RawEnvelope
import com.autoledger.core.model.TransferContext
import com.autoledger.core.model.TransferDetector
import com.autoledger.core.model.TransferKind
import com.autoledger.core.model.TransactionClassifier
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import com.autoledger.core.model.dedup.DedupPriority
import com.autoledger.core.model.platform.PlatformCatalog
import com.autoledger.core.model.platform.PlatformContext
import com.autoledger.core.model.platform.PlatformResolver
import com.autoledger.core.model.platform.PlatformSource
import com.autoledger.core.model.platform.priorityOf
import java.util.UUID

/**
 * 归一化流水线：**所有渠道的唯一写入口**。
 *
 * 顺序是有讲究的：
 * ```
 * 原始信封 → 金额/商户 → 转账识别（决定要不要计入消费） → 分类 → 去重 → 落库/入待确认
 * ```
 * 转账识别必须在分类之前 —— 给一笔「微信零钱充值」分了「餐饮」类，分类正确性就被污染了。
 * 去重必须最后做 —— 指纹里包含前面几条结果，换顺序会导致同一笔账两次指纹不同。
 */
class IngestPipeline(
    private val repository: LedgerRepository,
    private val duplicateResolver: com.autoledger.core.model.DuplicateResolver,
    private val transferDetector: TransferDetector,
    private val classifier: TransactionClassifier,
    private val cryptoBox: CryptoBox,
    /** 消费平台识别（纯 JVM 引擎，串在解析之后，**不改动 NotificationParser**）。 */
    private val platformResolver: PlatformResolver,
    /** 置信度高于此值 + 无重复 => 自动入账，否则进「待确认」 */
    private val autoConfirmThreshold: Float = 0.75f,
    /** 跨渠道重复是否自动合并（关掉则全部进入人工确认） */
    private val autoMergeDuplicates: Boolean = true,
) {

    sealed interface Outcome {
        val txnId: String
        data class Accepted(override val txnId: String, val autoConfirmed: Boolean, val reason: String) : Outcome
        data class MergedInto(override val txnId: String, val primaryId: String) : Outcome
        data class NeedsReview(override val txnId: String, val reason: String) : Outcome
    }

    suspend fun ingest(envelope: RawEnvelope): Outcome {
        val amount = envelope.amountHint
        val counterparty = envelope.counterpartyHint.orEmpty()
        val id = UUID.randomUUID().toString()

        // 消费平台识别：与「是不是一笔钱」正交，故串在解析之后，不影响金额/商户判定。
        // 识别不出就落 unknown（由 UI 打角标让用户确认），绝不硬塞一个看起来差不多的平台。
        val platform = platformResolver.resolve(
            PlatformContext(
                rawText = envelope.rawText,
                counterparty = counterparty.takeIf { it.isNotBlank() },
                packageName = envelope.packageName,
                sourceId = envelope.sourceId,
            )
        )

        val draft = LedgerTransaction(
            id = id,
            amountMinor = amount ?: 0L,
            occurredAtMillis = envelope.occurredAtMillis,
            type = resolveInitialType(envelope.explicitType, amount),
            counterparty = counterparty,
            platformId = platform.platformId,
            platformConfidence = platform.confidence,
            platformSource = PlatformSource.AUTO,
            note = null,
            sourceId = envelope.sourceId,
            sourceRef = envelope.sourceRef,
            rawTextSealed = runCatching { cryptoBox.sealString(envelope.rawText) }.getOrNull(),
            status = TxnStatus.RAW,
        ).let { it.copy(fingerprint = duplicateResolver.fingerprintOf(it)) }

        // 1) 内部划转识别
        val accounts = repository.listAccounts()
        val transfer = transferDetector.detect(
            TransferContext(
                counterparty = counterparty,
                note = envelope.rawText.take(200),
                amountMinor = amount ?: 0L,
                occurredAtMillis = envelope.occurredAtMillis,
                sourceId = envelope.sourceId,
                accounts = accounts,
            )
        )

        // 2) 类型判定：内部划转 / 退款 一律不计入消费
        val typed = draft.copy(
            type = when (transfer.kind) {
                TransferKind.NONE -> draft.type
                TransferKind.REFUND -> TxnType.REFUND
                else -> TxnType.TRANSFER
            },
        )

        // 3) 分类（只对真正的消费逐个算）
        var confidence = 1f
        var categoryId: String? = null
        var reason = "已入账"
        if (typed.type == TxnType.EXPENSE) {
            val result = classifier.classify(
                ClassificationContext(
                    counterparty = counterparty,
                    // 把原文（截断）交给分类器，关键词/记忆能匹配到更多线索，提升准确率。
                    note = envelope.rawText.take(200),
                    amountMinor = amount ?: 0L,
                    occurredAtMillis = envelope.occurredAtMillis,
                    sourceId = envelope.sourceId,
                    packageName = envelope.packageName,
                )
            )
            categoryId = result.categoryId
            confidence = result.confidence
            reason = result.reason
        } else {
            reason = "识别为${transfer.kind.name}，不计入消费"
        }

        // 4) 跨渠道去重
        val duplicates: List<DuplicateCandidate> =
            if (amount == null) emptyList() else duplicateResolver.findDuplicates(typed)

        val unresolvedAmount = amount == null
        val shouldConfirm = !unresolvedAmount &&
            confidence >= autoConfirmThreshold &&
            duplicates.isEmpty()

        val final = typed.copy(
            categoryId = categoryId,
            confidence = confidence,
            status = when {
                duplicates.isNotEmpty() && !autoMergeDuplicates -> TxnStatus.RAW
                shouldConfirm -> TxnStatus.CONFIRMED
                else -> TxnStatus.RAW
            },
        )
        repository.upsert(final)

        // P2-3：多候选时**按护栏结论择一**，而不是盲目取 `duplicates.first()`。
        // Tier-2 的候选分都是固定 50，排序对「能不能自动合并」是无关的 ——
        // 若一个 REVIEW 候选恰好排在前面，就会把本可 AUTO_MERGE 的候选挡掉，
        // 白白退化成「待确认」。这里直接挑出第一个**通过护栏**的候选来合并。
        val mergeable = if (autoMergeDuplicates) {
            duplicates.firstOrNull { duplicateResolver.canAutoMerge(typed, it) }
        } else {
            null
        }

        return when {
            unresolvedAmount -> Outcome.NeedsReview(final.id, "未解析出金额，请在待确认里补全")
            mergeable != null -> {
                // 「谁留下」不再简单地让"先入库的那条"当主记录 —— 那会让同一笔账归到哪个平台
                // 取决于哪条通知先到，用户真正关心的下单平台（美团）会被银行短信盖掉。
                val choice = DedupPriority.choosePrimary(
                    incomingId = final.id,
                    incomingRank = priorityOf(final.platformId).rank,
                    // ingest 阶段恒为 AUTO（平台要么是识别结果，要么还没被用户改过）。
                    incomingIsUser = final.platformSource == PlatformSource.USER,
                    existingId = mergeable.txnId,
                    existingRank = mergeable.priorityRank,
                    existingIsUser = mergeable.platformSource == PlatformSource.USER,
                )
                duplicateResolver.merge(choice.primaryId, listOf(choice.mergedId))
                // 合并后把被吸收那条的**有效信息补进主记录的空白**（只补空白，绝不覆盖）。
                inheritBlankFields(choice.primaryId, listOf(choice.mergedId))
                Outcome.MergedInto(final.id, choice.primaryId)
            }
            duplicates.isNotEmpty() -> Outcome.NeedsReview(
                final.id,
                if (duplicates.first().crossSource) "发现 ${duplicates.size} 笔可能的重复，待你确认合并"
                else "同渠道 ${duplicates.size} 笔同金额流水，疑似重复，待你确认是否为独立消费",
            )
            shouldConfirm -> Outcome.Accepted(final.id, true, reason)
            else -> Outcome.NeedsReview(final.id, "分类置信度 ${"%.2f".format(confidence)} 偏低，待确认")
        }
    }

    /**
     * 合并后的**字段继承**：只把 [mergedIds] 的有效信息补进 [primaryId] 的**空白字段**。
     *
     * 三条硬约束（设计 §4.5）：
     * 1. **只补空白、绝不覆盖** —— 覆盖会悄悄改掉用户已经看到并可能已经认过的值；
     * 2. `rawTextSealed` / `sourceRef` / `platformConfidence` **不动**：
     *    它们是「这条记录怎么来的」的原始证据，换了就等于伪造来源；被吸收的行仍在库里，证据不丢；
     * 3. 平台只在「主记录是 unknown，或层级更低且**不是用户指定**」时才继承，
     *    且继承后 `platformSource` 保持 AUTO —— 这不是用户选的，不能冒充权威值
     *    （冒充了以后就再也不会被自动流程修正，等于永久污染）。
     *
     * 主要价值：银行短信常常抽不出商户名，而美团通知有「美团外卖」⇒
     * 合并后主记录才能补上真实商户，否则用户在账单里只看到一条没有商户名的记录。
     */
    private suspend fun inheritBlankFields(primaryId: String, mergedIds: List<String>) {
        val primary = repository.findById(primaryId) ?: return
        val absorbedRows = mergedIds.mapNotNull { repository.findById(it) }
        if (absorbedRows.isEmpty()) return

        var patched = primary
        for (absorbed in absorbedRows) {
            patched = patched.copy(
                counterparty = patched.counterparty.ifBlank { absorbed.counterparty },
                note = patched.note?.takeIf { it.isNotBlank() }
                    ?: absorbed.note?.takeIf { it.isNotBlank() },
                categoryId = patched.categoryId ?: absorbed.categoryId,
                platformId = inheritedPlatformId(
                    primaryPlatformId = patched.platformId,
                    primarySource = patched.platformSource,
                    absorbedPlatformId = absorbed.platformId,
                ),
            )
        }

        if (patched != primary) repository.upsert(patched)
    }
}

/**
 * 合并后主记录该不该改用被吸收记录的平台（设计 §4.5 的第三条）。
 *
 * 抽成顶层 `internal` 纯函数，理由与 [resolveInitialType] 相同：
 * 这条规则**在自动合并路径上几乎到不了**（自动合并时主记录必然是层级更高的一方，
 * 也就是那个 ORDER 平台，它的平台不会是需要被"补"的空值），
 * 但它对**用户手动合并**与将来的路径都必须成立 —— 挂在私有方法里就只能靠
 * 造完整 ingest 链路去碰运气覆盖，抽出来即可直接逐格单测。
 *
 * 三条约束：
 * - 用户手选过的平台（`USER`）**永远**不被自动继承覆盖（`PlatformSource.USER` 存在的全部意义）；
 * - 两边平台相同时不动（避免无意义的写入）；
 * - 只在「主记录是 `unknown`」或「被吸收方层级更高」时才继承。
 *
 * 注意：继承后调用方**不修改** `platformSource`（保持 `AUTO`）——
 * 这不是用户选的，不能冒充权威值，否则该行以后就再也不会被自动流程修正了。
 */
internal fun inheritedPlatformId(
    primaryPlatformId: String,
    primarySource: PlatformSource,
    absorbedPlatformId: String,
): String {
    if (primarySource == PlatformSource.USER) return primaryPlatformId
    if (absorbedPlatformId == primaryPlatformId) return primaryPlatformId
    val primaryRank = priorityOf(primaryPlatformId).rank
    val shouldTake = primaryPlatformId == PlatformCatalog.UNKNOWN_ID ||
        primaryRank < priorityOf(absorbedPlatformId).rank
    return if (shouldTake) absorbedPlatformId else primaryPlatformId
}

/**
 * 初始账本类型判定：**显式类型优先**，其次才按金额正负推断。
 *
 * 之所以显式类型优先：退款解析结果的金额是**正数**（`Direction.IN` 取 `abs`），
 * 若只按正负判断会先落成 INCOME；即便后面靠关键词二次命中覆盖成 REFUND，也只是**隐式契约**——
 * 将来加一条不含"退款/退回"等词的退款文案（如"返现"）就会静默记成收入，退款丢失。
 *
 * 抽成顶层 `internal` 纯函数是为了可以脱离 Android/Room 直接做 JVM 单测（见 IngestPipelineTypeTest）。
 *
 * @param explicitType 采集端已确定的类型；非空时直接采用
 * @param amount 金额（分，正负代表收支方向）；越界/缺失按 EXPENSE 兜底
 */
internal fun resolveInitialType(explicitType: TxnType?, amount: Long?): TxnType = when {
    explicitType != null -> explicitType
    amount == null -> TxnType.EXPENSE
    amount < 0 -> TxnType.EXPENSE
    else -> TxnType.INCOME
}
