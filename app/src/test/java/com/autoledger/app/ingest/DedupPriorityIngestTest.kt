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
import com.autoledger.core.model.TxnType
import com.autoledger.core.model.UserPlatform
import com.autoledger.core.model.platform.PlatformCatalog
import com.autoledger.core.model.platform.PlatformContext
import com.autoledger.core.model.platform.PlatformSource
import com.autoledger.feature.capture.IngestPipeline
import com.autoledger.feature.dedup.LedgerDuplicateResolver
import com.autoledger.feature.platform.KeywordPlatformResolver
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

/**
 * **端到端证明「去重优先级」真的生效**（解析 → 平台识别 → 入账 → 两级去重 → 主记录裁决 → 字段继承）。
 *
 * ## 为什么必须端到端
 * 单模块测试能各自证明一半，但**证明不了需求真的落地**：
 *  - `Tier2ComplementaryMatchTest`（feature:dedup）证明护栏表与两级匹配本身对；
 *  - `DedupPriorityTest`（core:model）证明裁决规则本身对。
 * 但如果 `IngestPipeline` 没有把两者接起来（还是让"先入库的那条"当主记录），
 * 两个单测都绿，用户看到的仍然是「同一笔账归到了银行短信上、美团那条凭空消失」。
 *
 * ## 本文件证明的三件事
 * 1. **Tier-1 确实查不到、Tier-2 确实能合并** —— 用需求原文的组合：
 *    美团通知（商户「美团外卖」）↔ 银行短信（商户「财付通」）。两者商户名不同 ⇒ 指纹不同。
 * 2. **主记录是高优先级那条，且与入库先后无关** —— 正反两个顺序都断言主记录是「美团」。
 * 3. **用户手选过的平台不被自动覆盖**，但空白的商户会被补上（字段继承只补空白）。
 */
class DedupPriorityIngestTest {

    private val anchor = 1_700_000_000_000L

    // ------------------------------------------------------------------ 夹具

    private fun newPipeline(
        repo: InMemoryLedgerRepository,
        autoMerge: Boolean = true,
    ): IngestPipeline = IngestPipeline(
        repository = repo,
        duplicateResolver = LedgerDuplicateResolver(repo),
        transferDetector = NoTransfer,
        classifier = AlwaysFood,
        cryptoBox = CryptoBox(SecretKeySpec(ByteArray(32) { 7 }, "AES")),
        // 用**真实**平台识别引擎，否则就证明不了"平台识别 → 层级裁决"这条链路
        platformResolver = KeywordPlatformResolver(),
        autoConfirmThreshold = 0.75f,
        autoMergeDuplicates = autoMerge,
    )

    /** 美团 App 通知：包名直接坐实「美团」，商户写「美团外卖」。 */
    private fun meituanEnvelope(at: Long = anchor, amount: Long = -8_800L) = RawEnvelope(
        envelopeId = "e-mt",
        sourceId = "notify",
        sourceRef = "notify:mt-1",
        occurredAtMillis = at,
        rawText = "您的美团外卖订单已支付 88.00 元",
        counterpartyHint = "美团外卖",
        amountHint = amount,
        packageName = "com.sankuai.meituan",
    )

    /**
     * 银行短信：只有「尾号 / 储蓄卡」这类**银行弱线索**，商户「财付通」。
     *
     * 商户刻意用「财付通」——它是微信的持牌主体，也会出现在银行短信的对手方描述里；
     * 正是这种"两边都说得通"的模糊性让 Tier-1 无从下手。
     */
    private fun bankSmsEnvelope(at: Long = anchor + 35_000L, amount: Long = -8_800L) = RawEnvelope(
        envelopeId = "e-bank",
        sourceId = "sms",
        sourceRef = "sms:9527",
        occurredAtMillis = at,
        rawText = "您尾号1234的储蓄卡于10月3日 POS 消费 88.00 元，余额 8,000.00 元",
        counterpartyHint = "财付通",
        amountHint = amount,
        packageName = "sms:inbox",
    )

    /** 银行短信（商户用不含任何通道关键词的名字）—— 用于「银行卡 ↔ 支付通道」歧义组合。 */
    private fun neutralBankSmsEnvelope(at: Long = anchor, amount: Long = -8_800L) = bankSmsEnvelope(at, amount)
        .copy(counterpartyHint = "沃尔玛")

    private fun wechatEnvelope(at: Long = anchor, amount: Long = -8_800L) = RawEnvelope(
        envelopeId = "e-wx",
        sourceId = "notify_wechat",
        sourceRef = "notify:wx-1",
        occurredAtMillis = at,
        rawText = "微信支付凭证：已支付 88.00 元",
        counterpartyHint = "",
        amountHint = amount,
        packageName = "com.tencent.mm",
    )

    private fun InMemoryLedgerRepository.alive() = snapshot().filter { it.status != TxnStatus.MERGED }

    private fun InMemoryLedgerRepository.only(): LedgerTransaction {
        val alive = alive()
        assertEquals(1, alive.size, "账本里应只剩一条这笔消费，实际=${alive.map { it.id }}")
        return alive.single()
    }

    // ------------------------------------------------------------------ ① Tier-1 查不到、Tier-2 能合并

    @Test
    fun `Tier-1 genuinely cannot see this pair, so only Tier-2 can save it`() = runBlocking<Unit> {
        val repo = InMemoryLedgerRepository()
        val dupResolver = LedgerDuplicateResolver(repo)
        val pipeline = newPipeline(repo)

        val first = pipeline.ingest(meituanEnvelope()) as IngestPipeline.Outcome.Accepted
        val stored = assertNotNull(repo.findById(first.txnId))

        // 银行短信那条的指纹：与美团那条**必然不同**（商户名不同），这就是要补 Tier-2 的原因
        val incomingPlatform = KeywordPlatformResolver().resolve(
            PlatformContext(
                rawText = bankSmsEnvelope().rawText,
                counterparty = "财付通",
                packageName = "sms:inbox",
            )
        )
        val probe = LedgerTransaction(
            id = "probe", amountMinor = -8_800L, occurredAtMillis = anchor + 35_000L,
            type = TxnType.EXPENSE, counterparty = "财付通", sourceId = "sms", sourceRef = "sms:9527",
            platformId = incomingPlatform.platformId,
        ).let { it.copy(fingerprint = dupResolver.fingerprintOf(it)) }

        assertNotEquals(
            stored.fingerprint,
            probe.fingerprint,
            "前置条件：商户名不同 ⇒ 指纹不同 ⇒ Tier-1 的指纹索引**根本查不到**这笔",
        )
        assertTrue(
            dupResolver.findDuplicates(probe).isNotEmpty(),
            "但 Tier-2（同金额 + 3 分钟 + 跨渠道 + 层级互补）必须把它捞出来",
        )
    }

    // ------------------------------------------------------------------ ② 主记录是高优先级那条，与顺序无关

    @Test
    fun `D1 - meituan first then bank sms merges into the meituan record`() = runBlocking<Unit> {
        val repo = InMemoryLedgerRepository()
        val pipeline = newPipeline(repo)

        val meituan = pipeline.ingest(meituanEnvelope()) as IngestPipeline.Outcome.Accepted
        val outcome = pipeline.ingest(bankSmsEnvelope())

        val merged = asMerged(outcome)
        assertEquals(meituan.txnId, merged.primaryId, "先入库的美团那条当主记录")

        val alive = repo.only()
        assertEquals(meituan.txnId, alive.id, "留下来的必须是美团那条，而不是银行短信")
        assertEquals("meituan", alive.platformId, "主记录的平台必须是美团（用户真正关心的消费平台）")
        assertEquals(TxnStatus.CONFIRMED, alive.status)
    }

    @Test
    fun `D1 - bank sms first then meituan still ends with the meituan record as primary`() = runBlocking<Unit> {
        // 这条是**关键**：改造前「谁先入库谁当主记录」，
        // 银行短信先到就会把美团那条吞掉 —— 用户在账单里再也看不到「美团」。
        val repo = InMemoryLedgerRepository()
        val pipeline = newPipeline(repo)

        val bank = pipeline.ingest(bankSmsEnvelope())
        val meituan = pipeline.ingest(meituanEnvelope())

        val merged = asMerged(meituan)
        assertEquals(meituan.txnId, merged.primaryId, "后入库但层级更高 ⇒ 它才是主记录")
        assertTrue(merged.primaryId != bank.txnId, "绝不能因为银行短信先到就让它当主记录")

        val alive = repo.only()
        assertEquals(meituan.txnId, alive.id)
        assertEquals("meituan", alive.platformId)
        // 被吸收的银行短信必须能反查出主记录（合并关系是数据，不是字符串拼接）
        assertEquals(meituan.txnId, repo.findById(bank.txnId)?.mergedIntoId)
        assertEquals(TxnStatus.MERGED, repo.findById(bank.txnId)?.status)
    }

    // ------------------------------------------------------------------ ③ 歧义组合不得静默合并

    @Test
    fun `D3 - a payment channel plus a bank card is left for the user, never auto merged`() = runBlocking<Unit> {
        val repo = InMemoryLedgerRepository()
        val pipeline = newPipeline(repo)

        val wechat = pipeline.ingest(wechatEnvelope())
        val bank = pipeline.ingest(neutralBankSmsEnvelope())

        // 银行卡那条必须落 bank（只有弱线索时也不能落 unknown），否则这一格就不是"通道↔银行卡"
        assertEquals(
            PlatformCatalog.BANK_ID,
            repo.findById(bank.txnId)?.platformId,
            "只有银行弱线索时应落 bank，否则这一格测的就不是通道↔银行卡",
        )

        assertTrue(
            bank is IngestPipeline.Outcome.NeedsReview,
            "证据不足的组合必须交给用户，不能静默合并，实际=$bank",
        )
        assertEquals(2, repo.alive().size, "两条都必须留着，等用户判断")
        assertEquals(TxnStatus.RAW, repo.findById(bank.txnId)?.status)
        assertTrue(
            repo.mergeGroupOf(wechat.txnId).isEmpty(),
            "未合并 ⇒ 不得留下任何合并溯源",
        )
    }

    // ------------------------------------------------------------------ ④ 用户权威 + 字段继承

    @Test
    fun `D11 and D14 - a user chosen platform is kept while blank fields are inherited`() = runBlocking<Unit> {
        val repo = InMemoryLedgerRepository()
        val pipeline = newPipeline(repo)

        // ① 微信通知先到，但**商户为空**（真实：只有"微信支付凭证：已支付 88.00 元"）
        val wechat = pipeline.ingest(wechatEnvelope())
        assertTrue(repo.findById(wechat.txnId)?.counterparty.isNullOrBlank(), "前置条件：主记录商户为空")

        // ② 用户手动把这条的平台指定成「微信」⇒ 权威标记 USER
        repo.assignPlatform(wechat.txnId, "wechat")
        assertEquals(PlatformSource.USER, repo.findById(wechat.txnId)?.platformSource)

        // ③ 层级更高的美团通知后到
        val meituan = pipeline.ingest(meituanEnvelope())

        val merged = asMerged(meituan)
        assertEquals(wechat.txnId, merged.primaryId, "用户指定过的记录必须豁免被吞（R0 用户权威）")
        // 被吸收的是美团那条 —— 用溯源列反查，而不是靠返回值里的字段名
        assertEquals(wechat.txnId, repo.findById(meituan.txnId)?.mergedIntoId)

        val kept = assertNotNull(repo.findById(wechat.txnId))
        assertEquals("wechat", kept.platformId, "用户手选的平台不得被自动继承覆盖")
        assertEquals(PlatformSource.USER, kept.platformSource, "仍然保持 USER 权威标记")
        assertEquals("美团外卖", kept.counterparty, "空白的商户应被补上（只补空白）")
        assertEquals("notify:mt-1", repo.findById(meituan.txnId)?.sourceRef, "被吸收行原样保留，它自己的 sourceRef 不丢")
    }

    // ------------------------------------------------------------------ 辅助

    private fun asMerged(outcome: IngestPipeline.Outcome): IngestPipeline.Outcome.MergedInto {
        assertTrue(outcome is IngestPipeline.Outcome.MergedInto, "期望被合并，实际=$outcome")
        return outcome as IngestPipeline.Outcome.MergedInto
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
}

/**
 * 只实现本用例需要的方法，其余 [unused] 抛错以免被误当成真实实现
 * （与 `BankIncomeCrossChannelIngestTest.InMemoryLedgerRepository` 同一约定）。
 *
 * 语义逐条对齐 `RoomLedgerRepository` / `TransactionDao`：
 * `findByFingerprintNear` = 指纹 + 对称窗口 + 排除自身 + 排除已合并；
 * `findByAmountWithin` = 金额等值 + 闭区间窗口 + 排除自身 + 排除已合并与已忽略。
 */
private class InMemoryLedgerRepository : LedgerRepository {

    private val store = LinkedHashMap<String, LedgerTransaction>()

    fun snapshot(): List<LedgerTransaction> = store.values.toList()

    override suspend fun upsert(txn: LedgerTransaction) {
        store[txn.id] = txn
    }

    override suspend fun upsertAll(txns: List<LedgerTransaction>) {
        txns.forEach { store[it.id] = it }
    }

    override suspend fun delete(id: String) = unused()

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
    }

    /** 合并的唯一入口：状态 + 溯源一次写入（与 Room 侧 updateMergeState 语义一致）。 */
    override suspend fun setMergeState(id: String, status: TxnStatus, primaryId: String?) {
        store[id]?.let { store[id] = it.copy(status = status, mergedIntoId = primaryId) }
    }

    override suspend fun mergeGroupOf(primaryId: String): List<LedgerTransaction> =
        store.values.filter { it.mergedIntoId == primaryId }

    override suspend fun markStatus(id: String, status: TxnStatus) {
        store[id]?.let { store[id] = it.copy(status = status) }
    }

    override suspend fun assignPlatform(id: String, platformId: String) {
        // 与真实实现对齐：用户指定 ⇒ 满置信度 + USER 源（权威标记）
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

    // ---------------- 本用例用不到 ----------------

    override suspend fun listSince(fromMillis: Long, includeTransfers: Boolean): List<LedgerTransaction> = unused()

    override suspend fun listRange(
        fromMillis: Long,
        toMillis: Long,
        includeTransfers: Boolean,
    ): List<LedgerTransaction> = unused()

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

    override fun observeRange(
        fromMillis: Long,
        toMillis: Long,
        includeTransfers: Boolean,
    ): Flow<List<LedgerTransaction>> = emptyFlow()

    override fun observeCategories(): Flow<List<Category>> = emptyFlow()

    private fun unused(): Nothing = error("本集成用例未实现该方法")
}
