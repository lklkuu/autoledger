package com.autoledger.app.ingest

import com.autoledger.core.crypto.CryptoBox
import com.autoledger.core.model.Account
import com.autoledger.core.model.Category
import com.autoledger.core.model.ClassificationContext
import com.autoledger.core.model.ClassificationResult
import com.autoledger.core.model.Direction
import com.autoledger.core.model.LedgerRepository
import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.RawEnvelope
import com.autoledger.core.model.TransferContext
import com.autoledger.core.model.TransferDetector
import com.autoledger.core.model.TransferVerdict
import com.autoledger.core.model.TransactionClassifier
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import com.autoledger.core.model.UserPlatform
import com.autoledger.core.model.platform.PlatformSource
import com.autoledger.feature.capture.IngestPipeline
import com.autoledger.feature.dedup.LedgerDuplicateResolver
import com.autoledger.feature.platform.KeywordPlatformResolver
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

/**
 * 端到端「金额缺失的收入」路径：走**完整** [IngestPipeline]，验证修复后一笔命中收入规则、
 * 金额取不到的短信会被记成**收入**（修复前是隐性记成 0 元支出）。
 *
 * 放在 app 模块：只有它同时依赖 capture / dedup / platform（`feature:capture` 连测试期都没有 `feature:dedup`）。
 *
 * **刻意不复制 `toRawEnvelope` 的镜像函数** —— 直接构造 `RawEnvelope(directionHint = …)`
 * 喂进管线，避免镜像与真身漂移导致「测到的不是生产行为」。
 */
class IncomeDirectionIngestTest {

    // ------------------------------------------------------------------ 夹具

    private object NoTransfer : TransferDetector {
        override val id = "no_transfer"
        override suspend fun detect(ctx: TransferContext): TransferVerdict = TransferVerdict.none()
    }

    /** 记录调用次数的分类器：用于证明「收入路径不跑分类器」。 */
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

    private fun newPipeline(repo: LedgerRepository, classifier: TransactionClassifier): IngestPipeline = IngestPipeline(
        repository = repo,
        duplicateResolver = LedgerDuplicateResolver(repo),
        transferDetector = NoTransfer,
        classifier = classifier,
        cryptoBox = CryptoBox(SecretKeySpec(ByteArray(32) { 7 }, "AES")),
        platformResolver = KeywordPlatformResolver(),
        autoConfirmThreshold = 0.75f,
        autoMergeDuplicates = true,
    )

    /** 一条命中收入规则、金额缺失的真实形态短信信封。 */
    private fun incomeEnvelopeMissingAmount() = RawEnvelope(
        envelopeId = "c1",
        sourceId = "sms",
        sourceRef = "sms:c1",
        occurredAtMillis = 1_700_000_000_000L,
        rawText = "【工商银行】您尾号1234账户工资已转入。",
        counterpartyHint = "工商银行",
        amountHint = null,
        packageName = "95588",
        explicitType = null,
        directionHint = Direction.IN,
    )

    // ------------------------------------------------------------------ C1 / C2

    /** C1：金额缺失的收入走完 ingest ⇒ 记录为 INCOME（修复前为 EXPENSE）、金额 0、状态 RAW、落 NeedsReview。 */
    @Test
    fun `an income envelope missing its amount is stored as income and flagged for review`() = runBlocking {
        val repo = InMemoryRepo()
        val pipeline = newPipeline(repo, CountingClassifier())

        val outcome = pipeline.ingest(incomeEnvelopeMissingAmount())

        val stored = repo.findById(outcome.txnId)!!
        assertEquals(TxnType.INCOME, stored.type, "★ 核心：金额缺失但方向已知为流入 ⇒ 必须记成收入（修复前是支出）")
        assertEquals(0L, stored.amountMinor, "金额缺失时落库金额为 0（等待用户补全）")
        assertEquals(TxnStatus.RAW, stored.status, "金额缺失 ⇒ 进「待确认」")
        assertTrue(outcome is IngestPipeline.Outcome.NeedsReview, "金额缺失必须浮出待确认，而不是静默入账")
    }

    /** C2：分类器**不被调用**（分类只对 EXPENSE 跑），且 categoryId 为 null。 */
    @Test
    fun `the classifier is never called for an income record`() = runBlocking {
        val repo = InMemoryRepo()
        val classifier = CountingClassifier()
        val pipeline = newPipeline(repo, classifier)

        val outcome = pipeline.ingest(incomeEnvelopeMissingAmount())

        assertEquals(0, classifier.calls, "收入 ⇒ 分类器不应被调用（分类只对支出跑）")
        val stored = repo.findById(outcome.txnId)!!
        assertNull(stored.categoryId, "收入路径不分类 ⇒ categoryId 保持 null")
    }

    // ------------------------------------------------------------------ 最小假仓储（只实现复现所需；其余抛错）

    /** 语义逐条对齐 Room 的内存仓储，仅覆盖本用例用到的路径。 */
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
