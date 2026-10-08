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
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

/**
 * 手动录入「平台 / 分类 / 备注」**能否真的落到账本上**的 JVM 护栏。
 *
 * ## 为什么必须有它
 * 用户报的是「手动记账选不了平台与分类，选了也和列表里显示的不一致」。真正的断点在数据链路：
 * [ManualCaptureSource.envelope] 没有承载平台/分类的参数、
 * [RawEnvelope] 没有对应字段、`IngestPipeline` 直接写 `platformSource = AUTO` 且 `note = null`。
 * 这一类 bug **UI 层的任何用例都抓不到**（界面上有没有控件、点了有没有反应，与数据能否落到 DB 是两回事），
 * 只有「真实跑一遍 ingest、再回读落库字段」才能钉死。
 *
 * ## 硬约束
 * 必须真的走 `ManualCaptureSource.envelope(...)` → `IngestPipeline.ingest(...)` → `repository.findById(...)`：
 * 不得手搓 [RawEnvelope] 抄一遍 envelope 的拼装逻辑（那等于把被测逻辑复制成断言）。
 *
 * ## 为什么放在 `feature:capture`
 * 同 [NotificationToIngestEndToEndTest]：全部端口类型都在 `core:model`，本模块可不改任何构建脚本手写假实现。
 */
class ManualHintIngestTest {

    /** 固定时间戳：入库时间为真值时流水一切都派生自它，钉死便于复现。 */
    private val now = 1_700_000_000_000L

    // ------------------------------------------------------------------ 端口假实现（全部来自 core:model）

    private object NoTransfer : TransferDetector {
        override val id = "no_transfer"
        override suspend fun detect(ctx: TransferContext): TransferVerdict = TransferVerdict.none()
    }

    /**
     * 会把分类算成 `cat_auto` 的**弱置信度**分类器。
     *
     * 用弱置信度（0.4 < 0.75 阈值）是为了让「没给 hint 时会不会照着老逻辑走」可被区分：
     * 一旦 `categoryHint` 为空而这里又被跳过，落库就会是「待确认」，必然暴露。
     * 同时记录调用次数：手选分类时它必须**一次都不被调用**。
     */
    private class WeakClassifier : TransactionClassifier {
        var calls = 0
        override val id = "weak"
        override val displayName = "弱分类器（测试）"
        override val order = 0
        override suspend fun classify(ctx: ClassificationContext): ClassificationResult {
            calls++
            return ClassificationResult(categoryId = "cat_auto", confidence = 0.4f, reason = "自动命中")
        }
    }

    /**
     * 假平台识别器：识别出一个与手选值**不同**的平台（alipay），且置信度偏低。
     * 目的是让「手选是否真的覆盖了自动识别」有可观测差异 —— 两边同值的话断言等于空转。
     */
    private object AlipayPlatform : PlatformResolver {
        override val id = "fake_platform"
        override fun resolve(ctx: PlatformContext): PlatformResolution =
            PlatformResolution(platformId = "alipay", confidence = 0.42f)
    }

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
        platformResolver = AlipayPlatform,
        autoConfirmThreshold = 0.75f,
        autoMergeDuplicates = true,
    )

    /**
     * 把信封**直接**喂给管线（绕过采集端），用于验证管线**自己**持有的防线。
     *
     * 「unknown 不算手选」这条契约写在 [RawEnvelope.platformHint] 上，因此防线必须钉在管线上：
     * 采集端归一得再干净，也挡不住将来有别的产出方直接构造信封塞 unknown 进来。
     */
    private fun ingestRawEnvelope(envelope: RawEnvelope): LedgerTransaction = runBlocking {
        val repo = InMemoryRepo()
        val outcome = newPipeline(repo, WeakClassifier()).ingest(envelope)
        assertNotNull(repo.findById(outcome.txnId), "流水必须真的落库")
    }

    /** 跑完「采集端信封 → 管线 → 落库」，返回回读出来的那条流水。 */
    private fun ingestAndReadBack(
        counterparty: String = "楼下便利店",
        note: String? = null,
        platformId: String? = null,
        categoryId: String? = null,
    ): Pair<LedgerTransaction, WeakClassifier> = runBlocking {
        // 真实的 ManualCaptureSource.envelope：唯一的信封构造出口，不许在测试里复制它的拼装规则。
        val envelope = ManualCaptureSource().envelope(
            amountMinor = -1234L,
            counterparty = counterparty,
            note = note,
            occurredAtMillis = now,
            explicitType = null,
            platformId = platformId,
            categoryId = categoryId,
        )
        val classifier = WeakClassifier()
        val repo = InMemoryRepo()
        val outcome = newPipeline(repo, classifier).ingest(envelope)
        val stored = assertNotNull(repo.findById(outcome.txnId), "流水必须真的落库")
        stored to classifier
    }

    // ------------------------------------------------------------------ ① 手选平台 ⇒ USER 权威

    @Test
    fun `a hand picked platform overrides the automatic resolution and is stamped as user chosen`() {
        val (stored, _) = ingestAndReadBack(platformId = "meituan")

        assertEquals("meituan", stored.platformId, "手选平台必须覆盖自动识别出来的 alipay")
        assertEquals(PlatformSource.USER, stored.platformSource, "手选平台必须标 USER（去重继承等自动流程不得改写）")
        assertEquals(1f, stored.platformConfidence, "用户手选 ⇒ 置信度 1f（UI 不该再显示「不确定」角标）")
        assertEquals(TxnType.EXPENSE, stored.type)
    }

    // ------------------------------------------------------------------ ② 手选分类 ⇒ 不跑分类器且不被改写

    @Test
    fun `a hand picked category is stored as is and skips the classifier`() {
        val (stored, classifier) = ingestAndReadBack(categoryId = "cat_restaurant")

        assertEquals("cat_restaurant", stored.categoryId, "手选分类必须原样落库，不得被分类器改写成 cat_auto")
        assertEquals(0, classifier.calls, "手选是权威 ⇒ 分类器不得被调用")
        assertEquals(1f, stored.confidence, "手选分类的置信度是 1f ⇒ 不需要进「待确认」")
        assertEquals(TxnStatus.CONFIRMED, stored.status)
    }

    // ------------------------------------------------------------------ ③ 手选备注 ⇒ 真的落到 note 字段

    @Test
    fun `a hand written note lands in the note field and not only in the raw text`() {
        val (stored, _) = ingestAndReadBack(note = "和小王拼的午饭")

        assertEquals("和小王拼的午饭", stored.note, "★ 修复前 note 恒为 null：用户填的备注保存即消失")
        // 入账前的旧行为也不得丢：备注仍要拼进 rawText（分类器靠它做关键词匹配、出错时靠它审计）。
        // rawText 是加密字段，`cryptoBox.sealString` 在 JVM 单测里走到 android.util.Base64 桩会失败
        // ⇒ 不能直接断言 `rawTextSealed`，改为在信封层断言（见下一条用例）。
    }

    /** 与上面互为补充：分类器拿到的输入（rawText / counterpartyHint）必须一字不差地保持原样。 */
    @Test
    fun `the manual envelope still feeds raw text and counterparty exactly as before`() {
        val envelope = ManualCaptureSource().envelope(
            amountMinor = -1234L,
            counterparty = "楼下便利店",
            note = "和小王拼的午饭",
            occurredAtMillis = now,
            explicitType = null,
            platformId = "meituan",
            categoryId = "cat_restaurant",
        )

        assertEquals("楼下便利店", envelope.counterpartyHint, "商户照旧透传给 classification / transfer 的入参")
        assertTrue(envelope.rawText.contains("楼下便利店"), "商户照旧进原文")
        assertTrue(envelope.rawText.contains("和小王拼的午饭"), "★ 备注照旧进原文：这是分类器的线索来源，不得因新增 noteHint 而删掉")
        assertEquals("meituan", envelope.platformHint, "手选平台透传到信封")
        assertEquals("cat_restaurant", envelope.categoryHint, "手选分类透传到信封")
        assertEquals("和小王拼的午饭", envelope.noteHint, "备注额外落到 noteHint（此前只有 rawText 里有）")
        assertEquals(-1234L, envelope.amountHint)
    }

    // ------------------------------------------------------------------ ④ 不回归：三个 hint 全空

    @Test
    fun `without any hint the behaviour is byte for byte the pre change one`() {
        val (stored, classifier) = ingestAndReadBack()

        assertEquals("alipay", stored.platformId, "没手选 ⇒ 仍取自动识别结果")
        assertEquals(0.42f, stored.platformConfidence, "没手选 ⇒ 置信度照旧用识别器给的值")
        assertEquals(PlatformSource.AUTO, stored.platformSource, "没手选 ⇒ 不能冒充 USER，否则以后再也纠不正")
        assertEquals("cat_auto", stored.categoryId, "没手选 ⇒ 照旧跑分类器")
        assertEquals(0.4f, stored.confidence, "没手选 ⇒ 分类置信度照旧 = 分类器给的 0.4")
        assertEquals(1, classifier.calls, "支出且无手选 ⇒ 分类器照旧被调用一次")
        assertNull(stored.note, "没填备注 ⇒ note 仍为 null")
        // 0.4 < 0.75 阈值 ⇒ 与改动前一致地进「待确认」。
        assertEquals(TxnStatus.RAW, stored.status, "低置信度且无手选 ⇒ 与改动前一致：进待确认")
    }

    // ------------------------------------------------------------------ ⑤ 「未知」不是手选

    /**
     * 「没平台 / 未知」在归一化时必须等于**没有 hint**。
     *
     * 这条盯着的是一个很容易犯的错：表单里 PlatformPicker 的默认值是 [PlatformCatalog.UNKNOWN_ID]
     * （非 null），若照直塞进 `platformHint`，每行手动流水都会被标成 `PlatformSource.USER` ——
     * 既盖掉自动识别的结果（商户名里明明写着「美团外卖」也认不出来），
     * 又因为 USER 是权威标记而**永远**不能再被重解析 / 去重继承修正。
     */
    @Test
    fun `selecting unknown keeps the automatic platform path untouched`() {
        val (stored, _) = ingestAndReadBack(platformId = PlatformCatalog.UNKNOWN_ID)

        assertEquals("alipay", stored.platformId, "unknown = 没选 ⇒ 自动识别结果照旧生效")
        assertEquals(PlatformSource.AUTO, stored.platformSource, "★ unknown 绝不能被标成 USER")
        assertEquals(0.42f, stored.platformConfidence, "unknown ⇒ 置信度照旧")
    }

    /**
     * 同一条契约的**契约层**版本：穿透采集端、直接构造 [RawEnvelope] 塞进 unknown。
     *
     * 与上一条的差异在于防线落点：上一条证明「采集端归一正确」，这一条证明
     * **管线自己也扛得住**（`IngestPipeline` 是唯一把 hint 换成 `PlatformSource.USER` 的地方，
     * 契约既然写在 [RawEnvelope] 上，防线就必须在这里，而不是只指望上游）。
     * 今天全仓只有 ManualCaptureSource 一个产出方，这条防的是将来新增的渠道 / UI 路径。
     */
    @Test
    fun `a raw envelope carrying unknown as the platform hint is treated as no hint at all`() {
        val stored = ingestRawEnvelope(
            RawEnvelope(
                envelopeId = "direct",
                sourceId = "manual",
                sourceRef = "manual:direct",
                occurredAtMillis = now,
                rawText = "楼下便利店",
                counterpartyHint = "楼下便利店",
                amountHint = -1234L,
                // 直接喂进去：unknown 绝不能被当成用户手选。
                platformHint = PlatformCatalog.UNKNOWN_ID,
            ),
        )

        assertEquals("alipay", stored.platformId, "unknown ⇒ 自动识别结果照旧生效")
        assertEquals(PlatformSource.AUTO, stored.platformSource, "★ 管线层防线：unknown 不得被标成 USER")
        assertEquals(0.42f, stored.platformConfidence, "unknown ⇒ 置信度照旧")
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
