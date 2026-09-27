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

        val draft = LedgerTransaction(
            id = id,
            amountMinor = amount ?: 0L,
            occurredAtMillis = envelope.occurredAtMillis,
            type = resolveInitialType(envelope.explicitType, amount),
            counterparty = counterparty,
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

        return when {
            unresolvedAmount -> Outcome.NeedsReview(final.id, "未解析出金额，请在待确认里补全")
            duplicates.isNotEmpty() && autoMergeDuplicates &&
                duplicateResolver.isAutoMergeSafe(typed) && duplicates.first().crossSource -> {
                duplicateResolver.merge(duplicates.first().txnId, listOf(final.id))
                Outcome.MergedInto(final.id, duplicates.first().txnId)
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
