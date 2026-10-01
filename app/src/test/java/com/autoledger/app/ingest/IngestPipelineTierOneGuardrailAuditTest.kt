package com.autoledger.app.ingest

import com.autoledger.core.crypto.CryptoBox
import com.autoledger.core.model.Account
import com.autoledger.core.model.Category
import com.autoledger.core.model.ClassificationContext
import com.autoledger.core.model.ClassificationResult
import com.autoledger.core.model.LedgerRepository
import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.RawEnvelope
import com.autoledger.core.model.TransferContext
import com.autoledger.core.model.TransferDetector
import com.autoledger.core.model.TransferVerdict
import com.autoledger.core.model.TransactionClassifier
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.UserPlatform
import com.autoledger.core.model.platform.PlatformCatalog
import com.autoledger.core.model.platform.PlatformContext
import com.autoledger.core.model.platform.PlatformResolution
import com.autoledger.core.model.platform.PlatformResolver
import com.autoledger.core.model.platform.PlatformSource
import com.autoledger.feature.capture.IngestPipeline
import com.autoledger.feature.dedup.LedgerDuplicateResolver
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

/**
 * QA 独立复验（端到端，必修①）：**修复后的 Tier-1 护栏在真实 `IngestPipeline` 里的落点**。
 *
 * 与纯函数级 `feature:dedup` 的审计互补：这里走「信封 → 解析 → 平台识别 → 去重 → 裁决 → 落库」全链路，
 * 直接断言**最终 Outcome**（`MergedInto` vs `NeedsReview`），而不是 `canAutoMerge` 的返回值。
 *
 * 两条必须同时成立的契约：
 *  1. `bank` ↔ `bank`（银行短信 + 银行 App 动账通知）**必须合并**（不得退回待确认，否则 Bug 2 回归）；
 *  2. `wechat` ↔ `alipay`（同商户同金额的两笔真实消费）**必须不合并**（退回待确认）。
 *
 * 用固定平台识别器（按包名映射）替代 `KeywordPlatformResolver`：本用例验证的是**去重护栏**，
 * 不是识别引擎，固定输入能让"被验证的那条路径成为唯一解释"。
 */
class IngestPipelineTierOneGuardrailAuditTest {

    private val anchor = 1_700_000_000_000L

    private fun pipeline(repo: FakeRepo, byPackage: Map<String, String>): IngestPipeline = IngestPipeline(
        repository = repo,
        duplicateResolver = LedgerDuplicateResolver(repo),
        transferDetector = NoTransfer,
        classifier = AlwaysFood,
        cryptoBox = CryptoBox(SecretKeySpec(ByteArray(32) { 7 }, "AES")),
        platformResolver = FixedPlatformResolver(byPackage),
        autoConfirmThreshold = 0.75f,
        autoMergeDuplicates = true,
    )

    private fun envelope(
        id: String,
        sourceId: String,
        packageName: String,
        counterparty: String,
        at: Long = anchor,
        amount: Long = -8_800L,
    ) = RawEnvelope(
        envelopeId = id,
        sourceId = sourceId,
        sourceRef = "$sourceId:$id",
        occurredAtMillis = at,
        rawText = "$counterparty 消费 ${-amount / 100} 元",
        counterpartyHint = counterparty,
        amountHint = amount,
        packageName = packageName,
    )

    @Test
    fun `bank sms plus bank app notification merge end to end, never fall back to review`() = runBlocking<Unit> {
        val repo = FakeRepo()
        val pipeline = pipeline(repo, mapOf("sms:inbox" to "bank", "com.icbc.mobile" to "bank"))

        val sms = pipeline.ingest(envelope("sms", "sms", "sms:inbox", "工商银行"))
        assertTrue(sms is IngestPipeline.Outcome.Accepted, "第一条无重复 ⇒ 直接入账，实际=$sms")

        val notify = pipeline.ingest(
            envelope("app", "notify", "com.icbc.mobile", "工商银行", at = anchor + 30_000L),
        )
        assertTrue(
            notify is IngestPipeline.Outcome.MergedInto,
            "同一条 bank 通道被短信与 App 通知各抓一次 ⇒ 必须自动合并，绝不能退回待确认，实际=$notify",
        )
        assertEquals(sms.txnId, (notify as IngestPipeline.Outcome.MergedInto).primaryId)
        assertEquals(1, repo.alive().size, "账本里应只剩一条")
        assertEquals(TxnStatus.MERGED, repo.findById(notify.txnId)?.status)
    }

    @Test
    fun `wechat and alipay at the same shop are NOT merged end to end`() = runBlocking<Unit> {
        // P0-1 的端到端锁定：两笔真实消费（一笔微信、一笔支付宝），商户都「星巴克」都是 18 元
        val repo = FakeRepo()
        val pipeline = pipeline(
            repo,
            mapOf("com.tencent.mm" to "wechat", "com.eg.android.AlipayGphone" to "alipay"),
        )

        val wechat = pipeline.ingest(envelope("wx", "notify_wechat", "com.tencent.mm", "星巴克", amount = -1_800L))
        assertTrue(wechat is IngestPipeline.Outcome.Accepted)

        val alipay = pipeline.ingest(
            envelope("ali", "notify_alipay", "com.eg.android.AlipayGphone", "星巴克", at = anchor + 30_000L, amount = -1_800L),
        )
        assertTrue(
            alipay is IngestPipeline.Outcome.NeedsReview,
            "一次消费不可能同时走微信与支付宝 ⇒ 两笔真实消费，必须留给用户确认，实际=$alipay",
        )
        assertEquals(2, repo.alive().size, "两条都必须留着")
        assertTrue(repo.snapshot().none { it.status == TxnStatus.MERGED }, "不得有任何一条被静默合并")
    }

    // ================================================================ 必修③④⑤ 的端到端复验

    @Test
    fun `must-fix 3 - different storefronts of the same brand are NOT merged end to end`() = runBlocking<Unit> {
        // 必修③ 端到端：微信通知「中石化(朝阳站)」+ 银行短信「中石化(望京站)」，同金额、层级不同。
        // normalize 抹括号 ⇒ 指纹相同 ⇒ 走 Tier-1；仅靠层级护栏（PAYMENT≠BANK）会放行 ——
        // 门店护栏必须把它拦下 ⇒ 降级待确认（两笔真实消费不得被吞）。
        val repo = FakeRepo()
        val pipeline = pipeline(repo, mapOf("com.tencent.mm" to "wechat", "sms:inbox" to "bank"))

        val wechat = pipeline.ingest(envelope("wx", "notify_wechat", "com.tencent.mm", "中石化(朝阳站)"))
        assertTrue(wechat is IngestPipeline.Outcome.Accepted, "第一条无重复 ⇒ 直接入账，实际=$wechat")

        val bank = pipeline.ingest(
            envelope("bs", "sms", "sms:inbox", "中石化(望京站)", at = anchor + 30_000L),
        )
        assertTrue(
            bank is IngestPipeline.Outcome.NeedsReview,
            "两个不同加油站的两笔真实消费，绝不能被静默合并，实际=$bank",
        )
        assertTrue(repo.snapshot().none { it.status == TxnStatus.MERGED }, "不得有任何一条被静默合并")
    }

    @Test
    fun `must-fix 4 - a manual entry is NOT absorbed by an unrelated order end to end`() = runBlocking<Unit> {
        // 必修④ 端到端：用户手工记的现金 88 元（platform=unknown、source=manual）+ 美团订单 88 元。
        // unknown 只代表"没识别出平台"，不代表"某笔订单的银行侧" ⇒ 不得被静默吸收。
        val repo = FakeRepo()
        val pipeline = pipeline(repo, mapOf("com.sankuai.meituan" to "meituan"))

        val manual = pipeline.ingest(envelope("m", "manual", "com.autoledger.manual", "菜市场"))
        assertTrue(manual is IngestPipeline.Outcome.Accepted, "手工记录无重复 ⇒ 直接入账，实际=$manual")

        val meituan = pipeline.ingest(
            envelope("mt", "notify", "com.sankuai.meituan", "美团外卖", at = anchor + 60_000L),
        )
        assertTrue(
            meituan is IngestPipeline.Outcome.NeedsReview,
            "用户手工录入是权威数据，不得被同金额的外卖订单静默吞并，实际=$meituan",
        )
        assertEquals(TxnStatus.CONFIRMED, repo.findById(manual.txnId)?.status, "手工那条必须保持未被吸收")
        assertTrue(repo.snapshot().none { it.status == TxnStatus.MERGED }, "不得有任何一条被静默合并")
    }

    @Test
    fun `must-fix 5 - blank-merchant bank twin goes to review, never silently doubled, end to end`() = runBlocking<Unit> {
        // 必修⑤ 端到端（最直接的证据）：银行短信 + 银行 App 动账通知，**商户都为空**、同金额、跨来源。
        // 修复前：Tier-1（空商户指纹含 sourceId ⇒ 必然不等）+ Tier-2（bank↔bank=REJECT）双重落空
        //         ⇒ 第二条被判「无重复」⇒ 直接 CONFIRMED ⇒ 同一笔银行流水静默双记（虚增支出）。
        // 修复后：Tier-2 bank↔bank=REVIEW ⇒ 第二条进「待确认」，浮出候选交用户，不再静默双记。
        val repo = FakeRepo()
        val pipeline = pipeline(repo, mapOf("sms:inbox" to "bank", "com.icbc.mobile" to "bank"))

        val sms = pipeline.ingest(envelope("sms", "sms", "sms:inbox", counterparty = ""))
        assertTrue(sms is IngestPipeline.Outcome.Accepted, "第一条无重复 ⇒ 直接入账，实际=$sms")

        val notify = pipeline.ingest(
            envelope("app", "notify", "com.icbc.mobile", counterparty = "", at = anchor + 30_000L),
        )
        assertTrue(
            notify is IngestPipeline.Outcome.NeedsReview,
            "同一条 bank 通道被两个来源抓到、商户为空 ⇒ 必须进「待确认」；若为 Accepted 即『静默双记』" +
                "（说明必修⑤ 未生效），实际=$notify",
        )
        assertTrue(
            repo.snapshot().none { it.status == TxnStatus.MERGED },
            "REVIEW 不是静默合并 ⇒ 两条都不得被 MERGED",
        )
        assertEquals(2, repo.alive().size, "两条都保留（一条已入账 + 一条待确认）")
    }
}

// ------------------------------------------------------------------ 夹具

/** 按包名映射到固定 platformId 的识别器（本用例只关心去重护栏，不关心识别引擎）。 */
private class FixedPlatformResolver(private val byPackage: Map<String, String>) : PlatformResolver {
    override val id: String = "fixed"
    override fun resolve(ctx: PlatformContext): PlatformResolution =
        PlatformResolution(
            platformId = byPackage[ctx.packageName] ?: PlatformCatalog.UNKNOWN_ID,
            confidence = 0.9f,
        )
}

private object NoTransfer : TransferDetector {
    override val id = "no_transfer"
    override suspend fun detect(ctx: TransferContext): TransferVerdict = TransferVerdict.none()
}

private object AlwaysFood : TransactionClassifier {
    override val id = "always_food"
    override val displayName = "固定分类（测试）"
    override val order = 0
    override suspend fun classify(ctx: ClassificationContext): ClassificationResult =
        ClassificationResult(categoryId = "cat_food", confidence = 0.9f, reason = "测试固定命中")
}

/** 只实现本用例需要的方法，其余抛错以免被误当成真实实现。语义对齐 RoomLedgerRepository。 */
private class FakeRepo : LedgerRepository {

    private val store = LinkedHashMap<String, LedgerTransaction>()

    fun snapshot(): List<LedgerTransaction> = store.values.toList()
    fun alive(): List<LedgerTransaction> = store.values.filter { it.status != TxnStatus.MERGED }

    override suspend fun upsert(txn: LedgerTransaction) {
        store[txn.id] = txn
    }

    override suspend fun upsertAll(txns: List<LedgerTransaction>) {
        txns.forEach { store[it.id] = it }
    }

    override suspend fun findById(id: String): LedgerTransaction? = store[id]

    /** 平台在 ingest 阶段恒为 AUTO；本用例不涉及用户手选，仅按契约为完整性实现。 */
    override suspend fun assignPlatform(id: String, platformId: String) {
        store[id]?.let {
            store[id] = it.copy(platformId = platformId, platformConfidence = 1f, platformSource = PlatformSource.USER)
        }
    }

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

    override suspend fun listAccounts(): List<Account> = emptyList()

    override suspend fun <R> inTransaction(block: suspend () -> R): R = block()

    // ---------------- 本用例用不到 ----------------

    override suspend fun delete(id: String) = unused()
    override suspend fun listSince(fromMillis: Long, includeTransfers: Boolean): List<LedgerTransaction> = unused()
    override suspend fun listRange(fromMillis: Long, toMillis: Long, includeTransfers: Boolean): List<LedgerTransaction> = unused()
    override suspend fun listAll(includeTransfers: Boolean): List<LedgerTransaction> = unused()
    override suspend fun assignCategory(id: String, categoryId: String, confidence: Float) = unused()
    override suspend fun listCategories(): List<Category> = unused()
    override suspend fun upsertCategory(category: Category) = unused()
    override suspend fun deleteCategory(id: String) = unused()
    override suspend fun listUserPlatforms(includeArchived: Boolean): List<UserPlatform> = unused()
    override suspend fun upsertUserPlatform(platform: UserPlatform) = unused()
    override suspend fun archiveUserPlatform(id: String) = unused()
    override fun observeSince(fromMillis: Long): Flow<List<LedgerTransaction>> = emptyFlow()
    override fun observeRawCount(): Flow<Int> = emptyFlow()
    override fun observeRaw(): Flow<List<LedgerTransaction>> = emptyFlow()
    override fun observeAll(includeTransfers: Boolean): Flow<List<LedgerTransaction>> = emptyFlow()
    override fun observeRange(fromMillis: Long, toMillis: Long, includeTransfers: Boolean): Flow<List<LedgerTransaction>> = emptyFlow()
    override fun observeCategories(): Flow<List<Category>> = emptyFlow()

    private fun unused(): Nothing = error("本集成用例未实现该方法")
}
