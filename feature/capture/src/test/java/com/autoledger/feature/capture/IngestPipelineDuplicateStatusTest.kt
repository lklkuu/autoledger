package com.autoledger.feature.capture

import com.autoledger.core.crypto.CryptoBox
import com.autoledger.core.model.Account
import com.autoledger.core.model.Category
import com.autoledger.core.model.ClassificationContext
import com.autoledger.core.model.ClassificationResult
import com.autoledger.core.model.DuplicateCandidate
import com.autoledger.core.model.DuplicateResolver
import com.autoledger.core.model.LedgerRepository
import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.MatchTier
import com.autoledger.core.model.RawEnvelope
import com.autoledger.core.model.TransactionClassifier
import com.autoledger.core.model.TransferContext
import com.autoledger.core.model.TransferDetector
import com.autoledger.core.model.TransferVerdict
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.UserPlatform
import com.autoledger.core.model.countsInLedger
import com.autoledger.core.model.platform.PlatformCatalog
import com.autoledger.core.model.platform.PlatformContext
import com.autoledger.core.model.platform.PlatformResolution
import com.autoledger.core.model.platform.PlatformResolver
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

/**
 * `IngestPipeline` 的**状态分流**：有候选却合并不了 ⇒ `TxnStatus.DUPLICATE`。
 *
 * ## 要钉的是「落库状态」，不是护栏返回值
 * `feature:dedup` 的用例能证明 `canAutoMerge == false`，但**证明不了**这条流水最终落成什么状态。
 * 历史缺陷正是这样：护栏拒了合并，`IngestPipeline` 却把状态写成 `RAW` —— 而 `RAW` 是
 * **默认成立**（计入账单），于是重复记录照样出现在账单列表里，用户以为"根本没去重"。
 * 本用例走完整 `ingest()`，直接断言落库后的 `status`。
 *
 * ## 为什么用假去重器，而不是真实 `LedgerDuplicateResolver`
 * `feature:capture` **不依赖** `feature:dedup`（依赖方向是 dedup → capture 的调用方，不是反过来），
 * 模块边界不允许在测试里引它。因此本文件用一个最小假实现，但**判据逐字对齐生产语义**：
 * 候选带上自己的 `occurredAtMillis`，"≤ 30 秒才允许静默合并"。
 * 真实的 29 / 31 / 181 秒三档边界由 `feature:dedup` 的 `LedgerDuplicateResolverTest` 钉住，
 * 两端各管一段，不重复也不留缝。
 */
class IngestPipelineDuplicateStatusTest {

    private val anchor = 1_700_000_000_000L

    // ------------------------------------------------------------------ 端口假实现

    private object NoTransfer : TransferDetector {
        override val id = "no_transfer"
        override suspend fun detect(ctx: TransferContext): TransferVerdict = TransferVerdict.none()
    }

    private object FixedClassifier : TransactionClassifier {
        override val id = "fixed"
        override val displayName = "固定分类（测试）"
        override val order = 0
        override suspend fun classify(ctx: ClassificationContext): ClassificationResult =
            ClassificationResult(categoryId = "cat_food", confidence = 0.95f, reason = "测试固定命中")
    }

    private object UnknownPlatform : PlatformResolver {
        override val id = "unknown_platform"
        override fun resolve(ctx: PlatformContext): PlatformResolution =
            PlatformResolution(platformId = PlatformCatalog.UNKNOWN_ID, confidence = 0f)
    }

    /**
     * 假去重器：**总能找到一个同额候选**（`txnId = "existing"`），候选时间戳由 [candidateOffsetMillis] 决定。
     *
     * `canAutoMerge` 只实现"快窗口"这一条判据（生产端还有层级/门店/权威来源三道护栏，
     * 与本用例要验证的状态分流无关）：`|候选时间 − 本条时间| > 30 秒 ⇒ 不合并`。
     */
    private class FixedDuplicates(private val candidateOffsetMillis: Long) : DuplicateResolver {
        var mergeCalls = 0
            private set

        override val id: String = "fixed_dups"

        override fun fingerprintOf(txn: LedgerTransaction): String = "fp:${txn.amountMinor}"

        override suspend fun findDuplicates(txn: LedgerTransaction): List<DuplicateCandidate> =
            listOf(
                DuplicateCandidate(
                    txnId = "existing",
                    score = 90,
                    crossSource = true,
                    platformId = PlatformCatalog.BANK_ID,
                    tier = MatchTier.FINGERPRINT,
                    sourceId = "sms",
                    counterparty = "某商户",
                    occurredAtMillis = txn.occurredAtMillis + candidateOffsetMillis,
                ),
            )

        override fun canAutoMerge(txn: LedgerTransaction, candidate: DuplicateCandidate): Boolean =
            candidate.occurredAtMillis != 0L &&
                kotlin.math.abs(candidate.occurredAtMillis - txn.occurredAtMillis) <= FAST_MERGE_WINDOW_MILLIS

        override suspend fun merge(primaryId: String, duplicateIds: List<String>) {
            mergeCalls++
        }

        private companion object {
            /** 与生产端 `LedgerDuplicateResolver.FAST_MERGE_WINDOW` 保持一致（见本文件 KDoc 的说明）。 */
            const val FAST_MERGE_WINDOW_MILLIS = 30_000L
        }
    }

    private fun newPipeline(repo: LedgerRepository, resolver: DuplicateResolver): IngestPipeline = IngestPipeline(
        repository = repo,
        duplicateResolver = resolver,
        transferDetector = NoTransfer,
        classifier = FixedClassifier,
        cryptoBox = CryptoBox(SecretKeySpec(ByteArray(32) { 7 }, "AES")),
        platformResolver = UnknownPlatform,
        autoConfirmThreshold = 0.75f,
        autoMergeDuplicates = true,
    )

    private fun envelope(at: Long) = RawEnvelope(
        envelopeId = "e-1",
        sourceId = "notify",
        sourceRef = "notify:1",
        occurredAtMillis = at,
        rawText = "某商户 消费 25.00 元",
        counterpartyHint = "某商户",
        amountHint = -2_500L,
        packageName = "com.example.pay",
    )

    // ------------------------------------------------------------------ 用例

    @Test
    fun `a candidate outside the 30 second window lands as DUPLICATE, never as RAW`() = runBlocking<Unit> {
        val repo = InMemoryRepo()
        // 候选比本条晚 31 秒（> 30 秒 ⇒ 合并不了，但仍在"发现候选"的范围内）
        val resolver = FixedDuplicates(candidateOffsetMillis = 31_000L)
        val outcome = newPipeline(repo, resolver).ingest(envelope(anchor))

        val stored = repo.findById(outcome.txnId)
        assertEquals(
            TxnStatus.DUPLICATE,
            stored?.status,
            "有候选但合并不了 ⇒ 疑似重复（默认不成立）：不进账单与统计，只进采集箱待确认",
        )
        assertNotEquals(
            TxnStatus.RAW,
            stored?.status,
            "★ 回归钉子：RAW 是「默认成立」（计入账单），历史缺陷就是错写成 RAW，" +
                "导致重复记录照样出现在账单列表里",
        )
        assertTrue(outcome is IngestPipeline.Outcome.NeedsReview, "必须浮出待确认，实际=$outcome")
        assertTrue(
            (outcome as IngestPipeline.Outcome.NeedsReview).reason.contains("未计入账单"),
            "文案必须告诉用户「它没进账单」，否则用户会以为这笔账丢了",
        )
        assertEquals(0, resolver.mergeCalls, "超窗口 ⇒ 不得发生任何合并写入")
    }

    @Test
    fun `the same candidate inside the window is merged and never stored as DUPLICATE`() = runBlocking<Unit> {
        // 对照：把候选时间挪到 25 秒（< 30 秒）⇒ 走合并分支，本条不应落 DUPLICATE。
        val repo = InMemoryRepo()
        val resolver = FixedDuplicates(candidateOffsetMillis = 25_000L)
        val outcome = newPipeline(repo, resolver).ingest(envelope(anchor))

        assertTrue(outcome is IngestPipeline.Outcome.MergedInto, "窗口内 ⇒ 自动合并，实际=$outcome")
        assertEquals(1, resolver.mergeCalls, "合并只应发生一次")
        assertNotEquals(
            TxnStatus.DUPLICATE,
            repo.findById(outcome.txnId)?.status,
            "合并路径不得产生 DUPLICATE",
        )
    }

    // ------------------------------------------------------------------ 内存假仓储

    /** 只实现 ingest 路径会用到的成员；其余抛错以免被误当成真实实现。 */
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
                txn.status.countsInLedger &&
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
                txn.status.countsInLedger &&
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

        override suspend fun listAccounts(): List<Account> = emptyList()

        override suspend fun listCategories(): List<Category> = emptyList()

        override suspend fun <R> inTransaction(block: suspend () -> R): R = block()

        override suspend fun delete(id: String) = unused()

        override suspend fun listSince(fromMillis: Long, includeTransfers: Boolean): List<LedgerTransaction> = unused()

        override suspend fun listRange(
            fromMillis: Long,
            toMillis: Long,
            includeTransfers: Boolean,
        ): List<LedgerTransaction> = unused()

        override suspend fun listAll(includeTransfers: Boolean): List<LedgerTransaction> = unused()

        override suspend fun assignCategory(id: String, categoryId: String, confidence: Float) = unused()

        override suspend fun assignPlatform(id: String, platformId: String) = unused()

        override suspend fun listUserPlatforms(includeArchived: Boolean): List<UserPlatform> = unused()

        override suspend fun upsertUserPlatform(platform: UserPlatform) = unused()

        override suspend fun archiveUserPlatform(id: String) = unused()

        override suspend fun upsertCategory(category: Category) = unused()

        override suspend fun deleteCategory(id: String) = unused()

        override fun observeSince(fromMillis: Long): Flow<List<LedgerTransaction>> = emptyFlow()

        override fun observeRawCount(): Flow<Int> = emptyFlow()

        override fun observeRaw(): Flow<List<LedgerTransaction>> = emptyFlow()

        override fun observeAll(includeTransfers: Boolean): Flow<List<LedgerTransaction>> = emptyFlow()

        override fun observeRange(
            fromMillis: Long,
            toMillis: Long,
            includeTransfers: Boolean,
        ): Flow<List<LedgerTransaction>> = emptyFlow()

        override fun observeCategories(): Flow<List<Category>> = emptyFlow()

        private fun unused(): Nothing = error("本用例夹具未实现该方法")
    }
}
