package com.autoledger.feature.capture

import com.autoledger.core.crypto.CryptoBox
import com.autoledger.core.model.Account
import com.autoledger.core.model.Category
import com.autoledger.core.model.ClassificationContext
import com.autoledger.core.model.ClassificationResult
import com.autoledger.core.model.Direction
import com.autoledger.core.model.DuplicateCandidate
import com.autoledger.core.model.DuplicateResolver
import com.autoledger.core.model.LedgerRepository
import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.RawEnvelope
import com.autoledger.core.model.TransactionClassifier
import com.autoledger.core.model.TransferContext
import com.autoledger.core.model.TransferDetector
import com.autoledger.core.model.TransferVerdict
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import com.autoledger.core.model.UserPlatform
import com.autoledger.core.model.platform.PlatformCatalog
import com.autoledger.core.model.platform.PlatformContext
import com.autoledger.core.model.platform.PlatformResolution
import com.autoledger.core.model.platform.PlatformResolver
import com.autoledger.core.model.platform.PlatformSource
import com.autoledger.feature.capture.notify.NotificationParser
import com.autoledger.feature.capture.notify.toRawEnvelope
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

/**
 * 「**文本 → 账本**」完整链路端到端用例，闭合一处覆盖接缝。
 *
 * ## 为什么需要它
 * 此前全仓没有任何一条用例**同时**串联「真实 `toRawEnvelope`」与「完整 `IngestPipeline`」：
 *  - [IncomeDirectionChainTest] 有真 `NotificationParser` + 真 `toRawEnvelope`，但止步于
 *    `resolveInitialTypeWithRefiner`；
 *  - app 的 `IncomeDirectionIngestTest` 有完整 `IngestPipeline`，但信封是**手搓**的
 *    （`directionHint` 直接注入），没经过 `toRawEnvelope`；
 *  - `DigitalRmbNotificationReproTest` 用的是**手写镜像**，而非真 `toRawEnvelope`。
 *
 * 于是「删掉 `toRawEnvelope` 里 `directionHint = parsed.direction` 那一行」这个变异（M5），
 * 上面三条用例**都不会变** —— 两侧各有覆盖，但「文本 → 账本」这条**用户可见的 bug 形态**
 * 本身没被任何一条用例钉住。本用例正是补这个缝。
 *
 * ## 为什么放在 `feature:capture`
 * [IngestPipeline] 构造函数的全部端口类型都定义在 `core:model`
 * （[LedgerRepository] / [DuplicateResolver] / [TransferDetector] / [TransactionClassifier] /
 * [PlatformResolver]，`cryptoBox` 用 `core:crypto`），**不依赖** `feature:dedup` / `feature:platform`。
 * 因此 `feature:capture` 测试期可手写全部假实现，无需改动任何 `build.gradle.kts`。
 *
 * ## 硬约束
 * **必须真的走** `NotificationParser.parse` → `toRawEnvelope` → `pipeline.ingest`：
 * 不得手搓 `RawEnvelope`、不得复制 `toRawEnvelope` 的镜像函数。这是本用例存在的全部理由。
 */
class NotificationToIngestEndToEndTest {

    private val parser = NotificationParser()

    // ------------------------------------------------------------------ 端口假实现（全部来自 core:model）

    private object NoTransfer : TransferDetector {
        override val id = "no_transfer"
        override suspend fun detect(ctx: TransferContext): TransferVerdict = TransferVerdict.none()
    }

    /** 记录调用次数：用于断言「收入路径不跑分类器」。 */
    private class CountingClassifier : TransactionClassifier {
        var calls = 0
        override val id = "counting"
        override val displayName = "计数分类器（测试）"
        override val order = 0
        override suspend fun classify(ctx: ClassificationContext): ClassificationResult {
            calls++
            return ClassificationResult(categoryId = "cat_food", confidence = 0.9f, reason = "测试命中")
        }
    }

    /** 永远判不出平台的假识别器：本用例只关心收支方向，不关心平台。 */
    private object UnknownPlatform : PlatformResolver {
        override val id = "unknown_platform"
        override fun resolve(ctx: PlatformContext): PlatformResolution =
            PlatformResolution(platformId = PlatformCatalog.UNKNOWN_ID, confidence = 0f)
    }

    /** 恒无重复、恒不自动合并的假去重器（不使用 feature:dedup 的任何类型）。 */
    private object NoDuplicates : DuplicateResolver {
        override val id = "no_duplicates"
        override fun fingerprintOf(txn: LedgerTransaction): String = "fp:${txn.amountMinor}|${txn.counterparty}"
        override suspend fun findDuplicates(txn: LedgerTransaction): List<DuplicateCandidate> = emptyList()
        override fun canAutoMerge(txn: LedgerTransaction, candidate: DuplicateCandidate): Boolean = false
        override suspend fun merge(primaryId: String, duplicateIds: List<String>): Unit =
            error("本用例不应发生合并")
    }

    private fun newPipeline(repo: LedgerRepository, classifier: TransactionClassifier): IngestPipeline = IngestPipeline(
        repository = repo,
        duplicateResolver = NoDuplicates,
        transferDetector = NoTransfer,
        classifier = classifier,
        cryptoBox = CryptoBox(SecretKeySpec(ByteArray(32) { 7 }, "AES")),
        platformResolver = UnknownPlatform,
        autoConfirmThreshold = 0.75f,
        autoMergeDuplicates = true,
    )

    // ------------------------------------------------------------------ 端到端：文本 → 账本

    /** 一条端到端跑完链路后取回的全部产物，供两条用例各取所需地断言。 */
    private data class E2E(
        val parsed: NotificationParser.ParseResult,
        val env: RawEnvelope,
        val outcome: IngestPipeline.Outcome,
        val stored: LedgerTransaction,
        val classifier: CountingClassifier,
    )

    private val bankIncomeSms = "【工商银行】您尾号1234账户工资已转入。"

    /**
     * **真正串起「文本 → 账本」**：`NotificationParser.parse` → 真实 `toRawEnvelope` → `pipeline.ingest`。
     *
     * 前置（①解析 + ②信封）断言也放在这里 —— 它们正是 M5（删掉 `directionHint = parsed.direction`）
     * 会击穿的地方，两条用例都因此对 M5 敏感。
     */
    private fun runFromRawText(rawBody: String): E2E = runBlocking {
        // ① 解析前置：命中收入规则、金额取不到、方向为流入。
        val parsed = assertNotNull(parser.parse("sms:inbox", "95588", rawBody), "文案应命中收入规则")
        assertEquals("sms_bank_in", parsed.ruleId, "应命中银行收入短信规则")
        assertNull(parsed.amountMinor, "该文案三条金额正则都取不到数字 ⇒ 金额缺失")
        assertEquals(Direction.IN, parsed.direction, "规则声明的方向为流入")

        // ② 信封透传（真实 toRawEnvelope，不是镜像）。
        val env = toRawEnvelope(
            sourceId = "sms",
            sourceRef = "sms:e2e",
            occurredAtMillis = 1_700_000_000_000L,
            rawText = "95588\n$rawBody",
            packageName = "95588",
            parsed = parsed,
        )
        assertEquals(Direction.IN, env.directionHint, "方向必须透传到信封（删掉这行即复现 bug / M5）")
        assertNull(env.amountHint, "金额缺失应保持 null")

        // ③ 完整管线落库。
        val classifier = CountingClassifier()
        val repo = InMemoryRepo()
        val outcome = newPipeline(repo, classifier).ingest(env)
        val stored = assertNotNull(repo.findById(outcome.txnId))
        E2E(parsed, env, outcome, stored, classifier)
    }

    /**
     * 一条**命中收入规则但金额正则取不到金额**的真实短信，从原文一路走到落库：
     * 修复前会被记成 **EXPENSE**（一笔收入被静默记成 0 元支出），修复后必须是 INCOME。
     */
    @Test
    fun `a bank income sms goes from raw text to an income ledger record end to end`() {
        val e = runFromRawText(bankIncomeSms)

        assertEquals(TxnType.INCOME, e.stored.type, "★ 核心：修复前为 EXPENSE（收入被静默记成支出）")
        assertEquals(0L, e.stored.amountMinor, "金额缺失时落库金额为 0，等待用户补全")
        assertEquals(TxnStatus.RAW, e.stored.status, "金额缺失 ⇒ 进待确认")
        assertTrue(e.outcome is IngestPipeline.Outcome.NeedsReview, "金额缺失必须浮出待确认，而非静默入账")
    }

    /** 同一条端到端链路的另一半不变式：收入路径**不跑分类器**（分类只对支出跑）。 */
    @Test
    fun `the income end-to-end path never runs the classifier`() {
        val e = runFromRawText(bankIncomeSms)

        assertEquals(0, e.classifier.calls, "收入 ⇒ 分类器不得被调用")
        assertNull(e.stored.categoryId, "收入不分类 ⇒ categoryId 为 null")
    }

    // ------------------------------------------------------------------ 内存假仓储（语义对齐 Room；不用 feature:dedup）

    private class InMemoryRepo : LedgerRepository {
        private val store = LinkedHashMap<String, LedgerTransaction>()

        override suspend fun upsert(txn: LedgerTransaction) {
            store[txn.id] = txn
        }

        override suspend fun upsertAll(txns: List<LedgerTransaction>) {
            txns.forEach { store[it.id] = it }
        }

        override suspend fun findById(id: String): LedgerTransaction? = store[id]

        override suspend fun findByFingerprintNear(
            fingerprint: String,
            anchor: Long,
            windowMillis: Long,
            excludeId: String,
        ): List<LedgerTransaction> = store.values.filter { txn ->
            txn.fingerprint == fingerprint &&
                txn.id != excludeId &&
                txn.status != TxnStatus.MERGED &&
                kotlin.math.abs(txn.occurredAtMillis - anchor) <= windowMillis
        }

        override suspend fun findByAmountWithin(
            amountMinor: Long,
            fromMillis: Long,
            toMillis: Long,
            excludeId: String,
        ): List<LedgerTransaction> = store.values.filter { txn ->
            txn.amountMinor == amountMinor &&
                txn.id != excludeId &&
                txn.status != TxnStatus.MERGED &&
                txn.status != TxnStatus.IGNORED &&
                txn.occurredAtMillis in fromMillis..toMillis
        }.sortedByDescending { it.occurredAtMillis }

        override suspend fun setMergeState(id: String, status: TxnStatus, primaryId: String?) {
            store[id]?.let { store[id] = it.copy(status = status, mergedIntoId = primaryId) }
        }

        override suspend fun mergeGroupOf(primaryId: String): List<LedgerTransaction> =
            store.values.filter { it.mergedIntoId == primaryId }

        override suspend fun markStatus(id: String, status: TxnStatus) {
            store[id]?.let { store[id] = it.copy(status = status) }
        }

        override suspend fun assignPlatform(id: String, platformId: String) {
            store[id]?.let {
                store[id] = it.copy(
                    platformId = platformId,
                    platformConfidence = 1f,
                    platformSource = PlatformSource.USER,
                )
            }
        }

        override suspend fun listAccounts(): List<Account> = emptyList()
        override suspend fun listCategories(): List<Category> = emptyList()
        override suspend fun <R> inTransaction(block: suspend () -> R): R = block()

        override suspend fun delete(id: String) = unused()
        override suspend fun listSince(fromMillis: Long, includeTransfers: Boolean): List<LedgerTransaction> = unused()
        override suspend fun listRange(fromMillis: Long, toMillis: Long, includeTransfers: Boolean): List<LedgerTransaction> = unused()
        override suspend fun listAll(includeTransfers: Boolean): List<LedgerTransaction> = unused()
        override suspend fun assignCategory(id: String, categoryId: String, confidence: Float) = unused()
        override suspend fun listUserPlatforms(includeArchived: Boolean): List<UserPlatform> = unused()
        override suspend fun upsertUserPlatform(platform: UserPlatform) = unused()
        override suspend fun archiveUserPlatform(id: String) = unused()
        override suspend fun upsertCategory(category: Category) = unused()
        override suspend fun deleteCategory(id: String) = unused()
        override fun observeSince(fromMillis: Long): Flow<List<LedgerTransaction>> = emptyFlow()
        override fun observeRawCount(): Flow<Int> = emptyFlow()
        override fun observeRaw(): Flow<List<LedgerTransaction>> = emptyFlow()
        override fun observeAll(includeTransfers: Boolean): Flow<List<LedgerTransaction>> = emptyFlow()
        override fun observeRange(fromMillis: Long, toMillis: Long, includeTransfers: Boolean): Flow<List<LedgerTransaction>> = emptyFlow()
        override fun observeCategories(): Flow<List<Category>> = emptyFlow()

        private fun unused(): Nothing = error("本用例夹具未实现该方法")
    }
}
