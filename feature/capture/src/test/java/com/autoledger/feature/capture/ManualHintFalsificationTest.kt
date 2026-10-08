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
import com.autoledger.feature.capture.manual.ManualCaptureSource
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

/**
 * 独立验证（QA）：手动记账「平台 / 分类 / 备注」三个 hint 的数据链路（commit 60397e8 + 54cf483）。
 *
 * 立场：**证伪**。不复用 `ManualHintIngestTest` 的任何夹具与断言（那是工程师自证的那一套）：
 *  - 这里用**高置信度**的分类器做对手（工程师用的是 0.4 弱分类器）——
 *    只有分类器「本来会给出另一个结果且自信度高」时，才能证明手选真的压制了它；
 *  - 这里额外穿透到 [IngestPipeline] **直接喂 RawEnvelope** 的路径，绕开 ManualCaptureSource，
 *    目的是检验「unknown 归一化」这道防线到底站在哪一层；
 *  - 这里逐字段比对「改动前等价」的基线，而不是笼统地断言几个字段。
 *
 * 结论摘要见文件末尾「已知取舍」分组。
 */
class ManualHintFalsificationTest {

    private val now = 1_700_000_000_000L

    // ---------------------------------------------------------------- 端口假实现（全部自写）

    private object NoTransfer : TransferDetector {
        override val id = "qa_no_transfer"
        override suspend fun detect(ctx: TransferContext): TransferVerdict = TransferVerdict.none()
    }

    /**
     * **强**分类器：总是给出 [AUTO_HIGH] 分类、**置信度 0.99**（远高于 0.75 自动入账阈值）。
     *
     * 用高置信度是刻意的：如果实现「手选后仍然去跑了一遍分类器并覆盖」，
     * 弱分类器（0.4）只会让流水进待确认、症状温和；而强分类器会直接把它改写成另一个分类并入库 ——
     * 症状明显、无法被糊过去。
     */
    private class StrongClassifier : TransactionClassifier {
        var calls = 0
        override val id = "qa_strong"
        override val displayName = "强分类器（QA）"
        override val order = 0
        override suspend fun classify(ctx: ClassificationContext): ClassificationResult {
            calls++
            return ClassificationResult(categoryId = AUTO_HIGH, confidence = 0.99f, reason = "强命中")
        }
    }

    private object WechatResolver : PlatformResolver {
        override val id = "qa_platform"
        override fun resolve(ctx: PlatformContext): PlatformResolution =
            PlatformResolution(platformId = AUTO_PLATFORM, confidence = AUTO_CONFIDENCE)
    }

    private object NoDuplicates : DuplicateResolver {
        override val id = "qa_no_duplicates"
        override fun fingerprintOf(txn: LedgerTransaction): String = "qa-fp:${txn.amountMinor}|${txn.counterparty}"
        override suspend fun findDuplicates(txn: LedgerTransaction): List<DuplicateCandidate> = emptyList()
        override fun canAutoMerge(txn: LedgerTransaction, candidate: DuplicateCandidate): Boolean = false
        override suspend fun merge(primaryId: String, duplicateIds: List<String>): Unit =
            error("QA 夹具：本用例不应发生合并")
    }

    private fun pipeline(
        classifier: TransactionClassifier,
        repo: LedgerRepository = MemRepo(),
    ): IngestPipeline = IngestPipeline(
        repository = repo,
        duplicateResolver = NoDuplicates,
        transferDetector = NoTransfer,
        classifier = classifier,
        cryptoBox = CryptoBox(SecretKeySpec(ByteArray(32) { 3 }, "AES")),
        platformResolver = WechatResolver,
        autoConfirmThreshold = 0.75f,
        autoMergeDuplicates = true,
    )

    /** 走真实 `ManualCaptureSource.envelope` → 真实 `IngestPipeline` → 回读落库那一行。 */
    private fun ingestManual(
        counterparty: String = "楼下便利店",
        note: String? = null,
        platformId: String? = null,
        categoryId: String? = null,
    ): Pair<LedgerTransaction, StrongClassifier> = runBlocking {
        val envelope = ManualCaptureSource().envelope(
            amountMinor = -1234L,
            counterparty = counterparty,
            note = note,
            occurredAtMillis = now,
            explicitType = null,
            platformId = platformId,
            categoryId = categoryId,
        )
        val classifier = StrongClassifier()
        val repo = MemRepo()
        val outcome = pipeline(classifier, repo).ingest(envelope)
        val stored = requireNotNull(repo.findById(outcome.txnId)) { "流水必须真的落库" }
        stored to classifier
    }

    /** 绕过 ManualCaptureSource，直接把 RawEnvelope 喂给管线（用于定位防线的位置）。 */
    private fun ingestRaw(envelope: RawEnvelope): LedgerTransaction = runBlocking {
        val repo = MemRepo()
        val outcome = pipeline(StrongClassifier(), repo).ingest(envelope)
        requireNotNull(repo.findById(outcome.txnId)) { "流水必须真的落库" }
    }

    // ================================================================= ① 手选平台

    @Test
    fun handPickedPlatformWinsAndIsStampedUser() {
        val (stored, _) = ingestManual(platformId = "meituan")

        assertEquals("meituan", stored.platformId, "手选平台必须盖掉自动识别出来的 $AUTO_PLATFORM")
        assertEquals(PlatformSource.USER, stored.platformSource, "必须标 USER，否则去重继承等自动流程还会改写它")
        assertEquals(1f, stored.platformConfidence, "用户手选 ⇒ 置信度必须是精确的 1f")
        assertEquals(TxnType.EXPENSE, stored.type)
    }

    @Test
    fun handPickedPlatformAlsoKeepsAutoConfidenceAway() {
        val (stored, _) = ingestManual(platformId = "meituan")
        assertEquals(
            false, stored.platformConfidence == AUTO_CONFIDENCE,
            "不得把自动识别的置信度留下来（否则 UI 会显示「平台不确定」角标）",
        )
    }

    // ================================================================= ② 手选分类（对手是强分类器）

    @Test
    fun handPickedCategorySurvivesAStrongClassifier() {
        val (stored, classifier) = ingestManual(categoryId = "cat_restaurant")

        assertEquals("cat_restaurant", stored.categoryId, "★ 手选分类被改写 ⇒ 数据链路没有真的贯通")
        assertEquals(0, classifier.calls, "手选是权威 ⇒ 分类器必须一次都不被调用")
        assertEquals(1f, stored.confidence, "手选分类的置信度 = 1f，不该继承分类器的 0.99")
        assertEquals(TxnStatus.CONFIRMED, stored.status, "置信度 1f ⇒ 直接入账，不得丢进待确认")
    }

    @Test
    fun withoutHandPickTheSameStrongClassifierDoesDecide() {
        // 对照组：同一套夹具下，**不给**分类 hint 时分类器必须说了算 ——
        // 否则上一条「分类器 0 次调用」可能只是因为分类器本来就没被调用。
        val (stored, classifier) = ingestManual()
        assertEquals(AUTO_HIGH, stored.categoryId)
        assertEquals(0.99f, stored.confidence)
        assertEquals(1, classifier.calls)
    }

    // ================================================================= ③ 手选备注

    @Test
    fun handwrittenNoteLandsInTheNoteColumn() {
        val (stored, _) = ingestManual(note = "和小王拼的午饭")
        assertEquals("和小王拼的午饭", stored.note, "★ 修复前 note 恒为 null：用户填的备注保存即消失")
    }

    @Test
    fun blankNoteIsNotStoredAsAnEmptyString() {
        // UI 的备注框是空字符串而不是 null（见 ExpensesScreen 的 `var note by remember { "" }`）。
        // 空白必须归一化成 null，不能落一个空串进去。
        val (stored, _) = ingestManual(note = "   ")
        assertNull(stored.note, "空白备注必须归一成 null，不得落成空串")
    }

    // ================================================================= ④ 不回归：三个 hint 全空

    /**
     * 逐字段比对「改动前」的等价物 —— 不是抽查几个字段。
     *
     * 改动前的代码是：`platformId = 自动`、`platformConfidence = 自动`、`platformSource = AUTO`、
     * `note = null`、`categoryId = 分类器结果`、`confidence = 分类器置信度`。
     */
    @Test
    fun noHintIsByteForByteEquivalentToThePreChangeBehaviour() {
        val (stored, classifier) = ingestManual()

        assertEquals(AUTO_PLATFORM, stored.platformId, "平台：照旧是自动识别结果")
        assertEquals(AUTO_CONFIDENCE, stored.platformConfidence, "平台置信度：照旧是识别器给的原值")
        assertEquals(PlatformSource.AUTO, stored.platformSource, "★ 不得冒充 USER，否则这行以后再也纠不正")
        assertEquals(AUTO_HIGH, stored.categoryId, "分类：照旧由分类器决定")
        assertEquals(0.99f, stored.confidence, "分类置信度：照旧是分类器给的原值")
        assertEquals(1, classifier.calls, "分类器照旧被调用恰好一次")
        assertNull(stored.note, "没填备注 ⇒ note 仍为 null")
        assertEquals(TxnStatus.CONFIRMED, stored.status, "0.99 ≥ 0.75 ⇒ 自动入账")
    }

    @Test
    fun preChangeClassifierWouldHaveProducedTheFinancialAmountToo() {
        val (stored, _) = ingestManual()
        assertEquals(-1234L, stored.amountMinor, "金额链路不得被 hint 改动波及")
        assertEquals("楼下便利店", stored.counterparty)
    }

    // ================================================================= ⑤ 「未知平台」归一化：防线在哪一层？

    @Test
    fun unknownSelectionFromTheManualFormIsEquivalentToNoSelection() {
        val (stored, _) = ingestManual(platformId = PlatformCatalog.UNKNOWN_ID)

        assertEquals(AUTO_PLATFORM, stored.platformId, "unknown = 没选 ⇒ 自动识别结果照旧生效")
        assertEquals(PlatformSource.AUTO, stored.platformSource, "★ unknown 绝不能被标成 USER")
        assertEquals(AUTO_CONFIDENCE, stored.platformConfidence)
    }

    /**
     * **原为 QA 标记的存疑项（契约与防线不在同一层），已由 commit a676156 修复，转为回归护栏。**
     *
     * 当时的问题：`platformHint = "unknown"` 的归一化**只**存在于 `ManualCaptureSource.envelope`，
     * `IngestPipeline` 自己不做这层防御 ⇒ 任何绕过 ManualCaptureSource 直接构造 RawEnvelope
     * 的调用方（账单导入 / 新采集渠道 / 另一条 UI 路径）塞进 `PlatformCatalog.UNKNOWN_ID`，
     * 就会得到 `{platformId=unknown, source=USER, confidence=1f}` ——
     * 正是 `RawEnvelope.platformHint` 契约里明令禁止的结果。
     *
     * 修复要求：防线必须落在**契约持有者所在的那条路径**上。
     * 本用例绕过 ManualCaptureSource 直接喂信封，就是为了保证这道防线在任何入口都成立。
     */
    @Test
    fun pipelineItselfRejectsTheUnknownLiteralEvenWhenTheCallerDoesNotNormalizeIt() {
        val stored = ingestRaw(
            RawEnvelope(
                envelopeId = "qa-1",
                sourceId = ManualCaptureSource.ID,
                sourceRef = "qa:1",
                occurredAtMillis = now,
                rawText = "楼下便利店",
                counterpartyHint = "楼下便利店",
                amountHint = -1234L,
                platformHint = PlatformCatalog.UNKNOWN_ID,
            ),
        )

        assertEquals(
            AUTO_PLATFORM, stored.platformId,
            "★ unknown 字面量必须等同未选择 ⇒ 走自动识别结果",
        )
        assertEquals(
            PlatformSource.AUTO, stored.platformSource,
            "★ unknown 绝不能被标成权威 USER（否则这行永远无法被重解析 / 去重继承修正）",
        )
        assertEquals(AUTO_CONFIDENCE, stored.platformConfidence, "unknown ⇒ 置信度照旧用识别器给的值")
    }

    // ================================================================= ⑥ 备注是否污染分词/分类输入

    @Test
    fun noteDoesNotChangeTheRawTextContract() {
        val envelope = ManualCaptureSource().envelope(
            amountMinor = -1234L,
            counterparty = "楼下便利店",
            note = "和小王拼的午饭",
            occurredAtMillis = now,
        )
        assertEquals("楼下便利店", envelope.counterpartyHint)
        kotlin.test.assertTrue(envelope.rawText.contains("楼下便利店"), "商户照旧进原文")
        kotlin.test.assertTrue(envelope.rawText.contains("和小王拼的午饭"), "备注照旧进原文（分类器线索来源）")
    }

    // ================================================================= 内存仓储

    /**
     * 最小仓储：只需要支撑 ingest 的读写路径，其余端口显式抛错 ——
     * 一旦 IngestPipeline 偷偷开始依赖新的仓储能力，测试会立刻炸响而不是静默通过。
     */
    private class MemRepo : LedgerRepository {
        private val store = LinkedHashMap<String, LedgerTransaction>()

        override suspend fun upsert(txn: LedgerTransaction) { store[txn.id] = txn }
        override suspend fun upsertAll(txns: List<LedgerTransaction>) { txns.forEach { store[it.id] = it } }
        override suspend fun findById(id: String): LedgerTransaction? = store[id]
        override suspend fun listAccounts(): List<Account> = emptyList()
        override suspend fun listCategories(): List<Category> = emptyList()
        override suspend fun <R> inTransaction(block: suspend () -> R): R = block()

        override suspend fun findByFingerprintNear(
            fingerprint: String, anchor: Long, windowMillis: Long, excludeId: String,
        ): List<LedgerTransaction> = store.values.filter {
            it.fingerprint == fingerprint && it.id != excludeId
        }

        override suspend fun findByAmountWithin(
            amountMinor: Long, fromMillis: Long, toMillis: Long, excludeId: String,
        ): List<LedgerTransaction> = store.values.filter { it.id != excludeId && it.amountMinor == amountMinor }

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

        override suspend fun delete(id: String) = qaUnused("delete")
        override suspend fun listSince(fromMillis: Long, includeTransfers: Boolean) = qaUnused("listSince")
        override suspend fun listRange(fromMillis: Long, toMillis: Long, includeTransfers: Boolean) = qaUnused("listRange")
        override suspend fun listAll(includeTransfers: Boolean) = qaUnused("listAll")
        override suspend fun assignCategory(id: String, categoryId: String, confidence: Float) = qaUnused("assignCategory")
        override suspend fun listUserPlatforms(includeArchived: Boolean) = qaUnused("listUserPlatforms")
        override suspend fun upsertUserPlatform(platform: UserPlatform) = qaUnused("upsertUserPlatform")
        override suspend fun archiveUserPlatform(id: String) = qaUnused("archiveUserPlatform")
        override suspend fun upsertCategory(category: Category) = qaUnused("upsertCategory")
        override suspend fun deleteCategory(id: String) = qaUnused("deleteCategory")
        override fun observeSince(fromMillis: Long): Flow<List<LedgerTransaction>> = qaUnused("observeSince")
        override fun observeRawCount(): Flow<Int> = qaUnused("observeRawCount")
        override fun observeRaw(): Flow<List<LedgerTransaction>> = qaUnused("observeRaw")
        override fun observeAll(includeTransfers: Boolean): Flow<List<LedgerTransaction>> = qaUnused("observeAll")
        override fun observeRange(fromMillis: Long, toMillis: Long, includeTransfers: Boolean) =
            qaUnused("observeRange")
        override fun observeCategories(): Flow<List<Category>> = qaUnused("observeCategories")

        private fun qaUnused(what: String): Nothing =
            error("QA 夹具未实现 $what：IngestPipeline 开始依赖它了，请按真实语义补齐")
    }

    private companion object {
        const val AUTO_PLATFORM = "wechat"
        const val AUTO_CONFIDENCE = 0.42f
        const val AUTO_HIGH = "cat_automatically_decided"
    }
}
