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
import com.autoledger.core.model.TxnType
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking

/**
 * `IngestPipeline` 的**状态分流**：重复簇里「1 条进账 + 其余待确认」。
 *
 * ## 两条不变量（都是用户可见的，缺一条就会被报障）
 * 1. **重复不得直接进账单**：有候选却合并不了的那条要落 `DUPLICATE`（默认不成立，不计入账单与统计）；
 *    历史缺陷是错写成 `RAW`（默认成立），于是重复记录照样出现在账单列表里。
 * 2. **重复簇里必须有且只有一条真正进账**：N 条同额重复 ⇒ 1 条 `CONFIRMED`（不出现在待确认）
 *    + (N-1) 条 `DUPLICATE`（在采集箱待确认）。整簇都堆进待确认同样是缺陷
 *    （2026-11-04 报障：「3 条重复应该 2 条待确认 + 1 条直接进账单」）。
 *
 * ## 为什么用假去重器，而不是真实 `LedgerDuplicateResolver`
 * `feature:capture` **不依赖** `feature:dedup`（依赖方向是 dedup → capture 的调用方，不是反过来），
 * 模块边界不允许在测试里引它。因此本文件用一个最小假实现，但候选来源与判据都**对齐生产语义**：
 * 候选取自仓储里「计入账本」的同额记录（等价于 Room 的 `findByAmountWithin`），
 * 且只有「跨渠道 + ≤ 30 秒」才允许静默合并。
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

    /** 置信度可调：≥ 0.75 会自动入账，< 0.75 会落「待确认」（RAW）。 */
    private class FixedClassifier(private val confidence: Float) : TransactionClassifier {
        override val id = "fixed"
        override val displayName = "固定分类（测试）"
        override val order = 0
        override suspend fun classify(ctx: ClassificationContext): ClassificationResult =
            ClassificationResult(categoryId = "cat_food", confidence = confidence, reason = "测试固定命中")
    }

    private object UnknownPlatform : PlatformResolver {
        override val id = "unknown_platform"
        override fun resolve(ctx: PlatformContext): PlatformResolution =
            PlatformResolution(platformId = PlatformCatalog.UNKNOWN_ID, confidence = 0f)
    }

    /**
     * 假去重器：候选 = 仓储里「计入账本」的同额记录（对齐 Room 的 `findByAmountWithin`，
     * 因此 `DUPLICATE` / `MERGED` / `IGNORED` 天然不会成为候选）。
     *
     * `canAutoMerge` 只实现两条判据：**跨渠道** + **快窗口（≤ 30 秒）**。生产端还有层级 / 门店 /
     * 权威来源三道护栏，与本文件要验证的状态分流无关；同渠道不合并这一条恰好复刻了
     * 「我收到 3 条同额同渠道通知」这个真实报障场景。
     */
    private class RepoBackedDuplicates(private val repo: InMemoryRepo) : DuplicateResolver {
        var mergeCalls = 0
            private set

        override val id: String = "repo_backed_dups"

        override fun fingerprintOf(txn: LedgerTransaction): String = "fp:${txn.amountMinor}"

        override suspend fun findDuplicates(txn: LedgerTransaction): List<DuplicateCandidate> =
            repo.snapshot()
                .filter { it.status.countsInLedger && it.id != txn.id && it.amountMinor == txn.amountMinor }
                .sortedByDescending { it.occurredAtMillis }
                .map { other ->
                    DuplicateCandidate(
                        txnId = other.id,
                        score = 90,
                        crossSource = other.sourceId != txn.sourceId,
                        platformId = other.platformId,
                        tier = MatchTier.FINGERPRINT,
                        sourceId = other.sourceId,
                        counterparty = other.counterparty,
                        occurredAtMillis = other.occurredAtMillis,
                    )
                }

        override fun canAutoMerge(txn: LedgerTransaction, candidate: DuplicateCandidate): Boolean =
            candidate.crossSource &&
                candidate.occurredAtMillis != 0L &&
                kotlin.math.abs(candidate.occurredAtMillis - txn.occurredAtMillis) <= FAST_MERGE_WINDOW_MILLIS

        /** 与生产端 `LedgerDuplicateResolver.merge` 同语义：被吸收的置 MERGED，主记录置 CONFIRMED。 */
        override suspend fun merge(primaryId: String, duplicateIds: List<String>) {
            mergeCalls++
            duplicateIds.forEach { repo.markStatus(it, TxnStatus.MERGED) }
            repo.markStatus(primaryId, TxnStatus.CONFIRMED)
        }

        private companion object {
            /** 与生产端 `LedgerDuplicateResolver.FAST_MERGE_WINDOW` 保持一致（见本文件 KDoc 的说明）。 */
            const val FAST_MERGE_WINDOW_MILLIS = 30_000L
        }
    }

    private fun newPipeline(
        repo: LedgerRepository,
        resolver: DuplicateResolver,
        confidence: Float = 0.95f,
        autoMergeDuplicates: Boolean = true,
    ): IngestPipeline = IngestPipeline(
        repository = repo,
        duplicateResolver = resolver,
        transferDetector = NoTransfer,
        classifier = FixedClassifier(confidence),
        cryptoBox = CryptoBox(SecretKeySpec(ByteArray(32) { 7 }, "AES")),
        platformResolver = UnknownPlatform,
        autoConfirmThreshold = 0.75f,
        autoMergeDuplicates = autoMergeDuplicates,
    )

    private fun envelope(
        id: String = "1",
        sourceId: String = "notify",
        at: Long = anchor,
    ) = RawEnvelope(
        envelopeId = "e-$id",
        sourceId = sourceId,
        sourceRef = "$sourceId:$id",
        occurredAtMillis = at,
        rawText = "某商户 消费 25.00 元",
        counterpartyHint = "某商户",
        amountHint = -2_500L,
        packageName = "com.example.pay",
    )

    /** 先往仓储里放一条「已在账上」的记录（候选），模拟「第一条通知早已入库」。 */
    private suspend fun InMemoryRepo.seed(
        id: String,
        sourceId: String,
        at: Long,
        status: TxnStatus,
    ): LedgerTransaction = LedgerTransaction(
        id = id,
        amountMinor = -2_500L,
        occurredAtMillis = at,
        type = TxnType.EXPENSE,
        counterparty = "某商户",
        sourceId = sourceId,
        sourceRef = "$sourceId:$id",
        status = status,
    ).also { upsert(it) }

    private fun InMemoryRepo.ledgerRows() = snapshot().filter { it.status.countsInLedger }

    /**
     * 采集箱待确认队列的**同一判据**（`Daos.observeRaw` = `status IN ('RAW','DUPLICATE')`）。
     * 用仓储自己的 `observeRaw()` 取，确保测的是契约而不是我另写的过滤。
     */
    private suspend fun InMemoryRepo.pending() = observeRaw().first()

    // ------------------------------------------------------------------ 用例：状态分流

    @Test
    fun `a candidate outside the 30 second window lands as DUPLICATE and the cluster keeps one row in the ledger`() =
        runBlocking<Unit> {
            val repo = InMemoryRepo()
            // 第一条：跨渠道（sms）、31 秒后到的那条（notify）与它同额。
            // 这里把第一条造成 RAW —— 模拟"低置信度、还没被确认"，正是报障场景里第 1 条的形态。
            val kept = repo.seed(id = "sms-1", sourceId = "sms", at = anchor + 31_000L, status = TxnStatus.RAW)

            // 候选比本条晚 31 秒（> 30 秒 ⇒ 合并不了，但仍在"发现候选"的范围内）
            val resolver = RepoBackedDuplicates(repo)
            val outcome = newPipeline(repo, resolver).ingest(envelope(at = anchor))

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

            // ★ 新不变量：簇里必须有且只有一条真正进账，且是那条候选（不是本次这条）
            assertEquals(
                TxnStatus.CONFIRMED,
                repo.findById(kept.id)?.status,
                "候选那条必须被提升为 CONFIRMED：否则整个簇都堆在待确认里，账单里一条也看不见",
            )
            assertEquals(
                listOf(kept.id),
                repo.ledgerRows().map { it.id },
                "计入账本的记录只能有 1 条，且必须是候选那条",
            )
            assertEquals(
                1,
                repo.pending().size,
                "待确认队列（RAW + DUPLICATE）只剩本次这条 —— 候选那条已经进账单，不该再出现",
            )
        }

    @Test
    fun `the same candidate inside the window is merged and never stored as DUPLICATE`() = runBlocking<Unit> {
        // 对照：把候选时间挪到 25 秒（< 30 秒）⇒ 走合并分支，本条不应落 DUPLICATE。
        val repo = InMemoryRepo()
        val kept = repo.seed(id = "sms-1", sourceId = "sms", at = anchor + 25_000L, status = TxnStatus.RAW)
        val resolver = RepoBackedDuplicates(repo)
        val outcome = newPipeline(repo, resolver).ingest(envelope(at = anchor))

        assertTrue(outcome is IngestPipeline.Outcome.MergedInto, "窗口内 ⇒ 自动合并，实际=$outcome")
        assertEquals(1, resolver.mergeCalls, "合并只应发生一次")
        assertNotEquals(
            TxnStatus.DUPLICATE,
            repo.findById(outcome.txnId)?.status,
            "合并路径不得产生 DUPLICATE",
        )
        assertEquals(TxnStatus.CONFIRMED, repo.findById(kept.id)?.status, "合并后主记录必须是 CONFIRMED")
        assertEquals(1, repo.ledgerRows().size, "合并后账本里只应剩 1 条")
        assertEquals(0, repo.pending().size, "合并成功 ⇒ 不该留在待确认队列里")
    }

    // ------------------------------------------------------------------ 用例：3 条同额重复（用户报障场景）

    /**
     * 用户原话：「3 条重复，放 2 条到待确认，剩下的一条直接进入账单」。
     *
     * 三条同额、**同渠道**（`sourceId` 都是 `notify`）、间隔 2 秒，且分类置信度低（0.5）：
     * | # | 时刻 | 结果 | 理由 |
     * |---|---|---|---|
     * | 1 | t+0s | `RAW` → **被提升为 `CONFIRMED`** | 无候选 + 低置信度 ⇒ 先落 RAW；第 2 条到达时它是最可信候选，被提升进账 |
     * | 2 | t+2s | `DUPLICATE` | 有候选（#1）但同渠道 ⇒ 不静默合并（同额同渠道可能是两笔） |
     * | 3 | t+4s | `DUPLICATE` | 同上；候选只有 #1（#2 已是 DUPLICATE，不再当候选） |
     *
     * 最终：账单 1 条、待确认 2 条 —— 与用户预期一致。
     */
    @Test
    fun `three same amount notifications leave one row in the ledger and two in the review queue`() =
        runBlocking<Unit> {
            val repo = InMemoryRepo()
            val resolver = RepoBackedDuplicates(repo)
            // 低置信度（< 0.75）⇒ 第一条不会自动入账，会落 RAW
            val pipeline = newPipeline(repo, resolver, confidence = 0.5f)

            val first = pipeline.ingest(envelope(id = "n1", at = anchor))
            assertEquals(TxnStatus.RAW, repo.findById(first.txnId)?.status, "前置：第一条先落 RAW（低置信度、无候选）")

            val second = pipeline.ingest(envelope(id = "n2", at = anchor + 2_000L))
            assertEquals(TxnStatus.DUPLICATE, repo.findById(second.txnId)?.status, "第 2 条：同渠道，不合并")
            assertEquals(
                TxnStatus.CONFIRMED,
                repo.findById(first.txnId)?.status,
                "第 2 条入库时把最可信候选（第 1 条）提升为 CONFIRMED —— 簇里必须有一条真正进账",
            )

            val third = pipeline.ingest(envelope(id = "n3", at = anchor + 4_000L))
            assertEquals(TxnStatus.DUPLICATE, repo.findById(third.txnId)?.status, "第 3 条：同渠道，不合并")
            assertEquals(0, resolver.mergeCalls, "同渠道一律不静默合并")

            // ★ 用户要的形态：1 条进账 + 2 条待确认
            val ledger = repo.ledgerRows()
            assertEquals(1, ledger.size, "重复簇里只能有 1 条计入账本，实际=${ledger.map { it.id to it.status }}")
            assertEquals(
                TxnStatus.CONFIRMED,
                ledger.single().status,
                "留在账上的那条必须是 CONFIRMED（RAW/DUPLICATE 都表示还没拍板）",
            )
            assertEquals(first.txnId, ledger.single().id, "留下的是最先入库的那条，编号不变，可追溯")

            val pending = repo.pending()
            assertEquals(2, pending.size, "待确认队列只剩 2 条，实际=${pending.map { it.id }}")
            assertEquals(
                setOf(second.txnId, third.txnId),
                pending.map { it.id }.toSet(),
                "进账的那条不得再出现在待确认队列里",
            )
            assertTrue(
                pending.all { it.status == TxnStatus.DUPLICATE },
                "两条都必须标记为疑似重复（不是 RAW），否则账单页仍会把它们算进去",
            )
        }

    /**
     * 反向契约：`autoMergeDuplicates = false` 时**不得**提升。
     *
     * 该开关的 KDoc 写明「关掉则全部进入人工确认」。若这条路径也悄悄把候选改成 CONFIRMED，
     * 用户设了"我要逐条看"却仍有一条被系统自行确认 —— 那是违背用户设置。
     * 注意账本金额并不因此改变：`RAW` 本就计入，所以这里 `countsInLedger == 1`，
     * 差别只在**待确认队列里是 3 条还是 2 条**。
     */
    @Test
    fun `with auto merge disabled nothing is promoted, so all three rows stay for review`() =
        runBlocking<Unit> {
            val repo = InMemoryRepo()
            val resolver = RepoBackedDuplicates(repo)
            val pipeline = newPipeline(repo, resolver, confidence = 0.5f, autoMergeDuplicates = false)

            val first = pipeline.ingest(envelope(id = "n1", at = anchor))
            pipeline.ingest(envelope(id = "n2", at = anchor + 2_000L))
            pipeline.ingest(envelope(id = "n3", at = anchor + 4_000L))

            assertEquals(
                TxnStatus.RAW,
                repo.findById(first.txnId)?.status,
                "关掉自动合并 ⇒ 不得替用户确认（保持 RAW，仍在待确认队列里）",
            )
            assertEquals(1, repo.ledgerRows().size, "RAW 本就计入账本，金额口径不变")
            assertEquals(3, repo.pending().size, "三条都要让用户看到：一条 RAW + 两条 DUPLICATE")
        }

    // ------------------------------------------------------------------ 内存假仓储

    /** 只实现 ingest 路径会用到的成员；其余抛错以免被误当成真实实现。 */
    private class InMemoryRepo : LedgerRepository {
        private val store = LinkedHashMap<String, LedgerTransaction>()

        fun snapshot(): List<LedgerTransaction> = store.values.toList()

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

        /** 语义对齐 Room.observeRawCount：RAW + DUPLICATE（含疑似重复）。 */
        override fun observeRawCount(): Flow<Int> = flow {
            emit(store.values.count { it.status == TxnStatus.RAW || it.status == TxnStatus.DUPLICATE })
        }

        /** 语义对齐 Room.observeRaw：`status IN ('RAW','DUPLICATE')`。 */
        override fun observeRaw(): Flow<List<LedgerTransaction>> = flow {
            emit(
                store.values
                    .filter { it.status == TxnStatus.RAW || it.status == TxnStatus.DUPLICATE }
                    .sortedByDescending { it.occurredAtMillis },
            )
        }

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
