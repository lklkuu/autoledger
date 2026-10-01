package com.autoledger.feature.dedup

import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import com.autoledger.core.model.platform.PlatformCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 跨渠道去重 —— 纯 JVM 单元测试。
 *
 * 这是「同一杯咖啡不会被记成两笔」的唯一保障，也是最容易误伤真实消费的地方。
 */
class LedgerDuplicateResolverTest {

    private val anchor = 1_700_000_000_000L

    private fun txn(
        id: String,
        amountMinor: Long,
        counterparty: String,
        occurredAt: Long = anchor,
        sourceId: String = "notify",
        status: TxnStatus = TxnStatus.CONFIRMED,
        fingerprint: String = "",
        type: TxnType = TxnType.EXPENSE,
        platformId: String = PlatformCatalog.UNKNOWN_ID,
    ) = LedgerTransaction(
        id = id,
        amountMinor = amountMinor,
        occurredAtMillis = occurredAt,
        type = type,
        counterparty = counterparty,
        sourceId = sourceId,
        sourceRef = "$sourceId:$id",
        status = status,
        fingerprint = fingerprint,
        platformId = platformId,
    )

    private fun resolver(vararg existing: LedgerTransaction): Pair<LedgerDuplicateResolver, FakeLedgerRepository> {
        val repo = FakeLedgerRepository(existing.toList())
        return LedgerDuplicateResolver(repo) to repo
    }

    // ------------------------------------------------------------ 指纹

    @Test
    fun `fingerprint is stable for same amount and merchant`() {
        val (r, _) = resolver()
        val a = txn("a", -2500, "星巴克")
        val b = txn("b", -2500, "星巴克")
        assertEquals(r.fingerprintOf(a), r.fingerprintOf(b))
    }

    @Test
    fun `fingerprint ignores parentheses decoration case and symbols`() {
        val (r, _) = resolver()
        val plain = txn("a", -2500, "星巴克")
        val decorated = txn("b", -2500, "星巴克（南京西路店）")
        val spaced = txn("c", -2500, " 星巴克 ")
        val latin = txn("d", -2500, "Starbucks")
        assertEquals(r.fingerprintOf(plain), r.fingerprintOf(decorated))
        assertEquals(r.fingerprintOf(plain), r.fingerprintOf(spaced))
        assertEquals(r.fingerprintOf(latin), r.fingerprintOf(txn("e", -2500, "STARBUCKS")))
    }

    @Test
    fun `fingerprint differs by amount`() {
        val (r, _) = resolver()
        assertNotEquals(
            r.fingerprintOf(txn("a", -2500, "星巴克")),
            r.fingerprintOf(txn("b", -2501, "星巴克")),
        )
    }

    @Test
    fun `fingerprint differs by merchant`() {
        val (r, _) = resolver()
        assertNotEquals(
            r.fingerprintOf(txn("a", -2500, "星巴克")),
            r.fingerprintOf(txn("b", -2500, "瑞幸")),
        )
    }

    @Test
    fun `fingerprint sign matters so refund is not merged with payment`() {
        val (r, _) = resolver()
        assertNotEquals(
            r.fingerprintOf(txn("a", -2500, "星巴克")),
            r.fingerprintOf(txn("b", 2500, "星巴克")),
        )
    }

    @Test
    fun `fingerprint is a 64 char lowercase hex digest`() {
        val (r, _) = resolver()
        val fp = r.fingerprintOf(txn("a", -2500, "星巴克"))
        assertEquals(64, fp.length)
        assertTrue(fp.all { it in '0'..'9' || it in 'a'..'f' }, "指纹必须是小写 hex")
    }

    // ------------------------------------------------------------ findDuplicates

    @Test
    fun `finds cross channel duplicate inside window`() = runBlocking {
        val existing = txn("old", -2500, "星巴克", sourceId = "sms").let {
            it.copy(fingerprint = LedgerDuplicateResolver(FakeLedgerRepository()).fingerprintOf(it))
        }
        val incoming = txn("new", -2500, "星巴克", sourceId = "notify").let {
            it.copy(fingerprint = LedgerDuplicateResolver(FakeLedgerRepository()).fingerprintOf(it))
        }
        val (r, _) = resolver(existing)
        val dups = r.findDuplicates(incoming)
        assertEquals(1, dups.size)
        assertEquals("old", dups.first().txnId)
    }

    @Test
    fun `ignores candidates outside time window`() = runBlocking {
        val fp = LedgerDuplicateResolver(FakeLedgerRepository()).fingerprintOf(txn("x", -2500, "星巴克"))
        val old = txn("old", -2500, "星巴克", occurredAt = anchor - 30 * 60 * 1000L, sourceId = "sms").copy(fingerprint = fp)
        val incoming = txn("new", -2500, "星巴克").copy(fingerprint = fp)
        val (r, _) = resolver(old)
        assertTrue(r.findDuplicates(incoming).isEmpty(), "超过 3 分钟窗口不应判重")
    }

    @Test
    fun `never returns the transaction itself`() = runBlocking {
        val fp = LedgerDuplicateResolver(FakeLedgerRepository()).fingerprintOf(txn("self", -2500, "星巴克"))
        val self = txn("self", -2500, "星巴克").copy(fingerprint = fp)
        val (r, _) = resolver(self)
        assertTrue(r.findDuplicates(self).isEmpty())
    }

    @Test
    fun `skips already merged records`() = runBlocking {
        val fp = LedgerDuplicateResolver(FakeLedgerRepository()).fingerprintOf(txn("x", -2500, "星巴克"))
        val merged = txn("merged", -2500, "星巴克", status = TxnStatus.MERGED).copy(fingerprint = fp)
        val incoming = txn("new", -2500, "星巴克").copy(fingerprint = fp)
        val (r, _) = resolver(merged)
        assertTrue(r.findDuplicates(incoming).isEmpty(), "已合并的记录不应再次参与判重")
    }

    @Test
    fun `cross channel duplicate ranks above same channel one`() = runBlocking {
        val probe = LedgerDuplicateResolver(FakeLedgerRepository())
        val fp = probe.fingerprintOf(txn("x", -2500, "星巴克"))
        val sameChannel = txn("same", -2500, "星巴克", sourceId = "notify").copy(fingerprint = fp)
        val crossChannel = txn("cross", -2500, "星巴克", sourceId = "sms").copy(fingerprint = fp)
        val incoming = txn("new", -2500, "星巴克", sourceId = "notify").copy(fingerprint = fp)
        val (r, _) = resolver(sameChannel, crossChannel)
        val dups = r.findDuplicates(incoming)
        assertEquals(2, dups.size)
        assertEquals("cross", dups.first().txnId, "跨渠道候选必须排在前面")
        assertTrue(dups.first().score > dups.last().score)
    }

    @Test
    fun `finds nothing when repository is empty`() = runBlocking {
        val (r, _) = resolver()
        assertTrue(r.findDuplicates(txn("new", -2500, "星巴克")).isEmpty())
    }

    @Test
    fun `same channel same amount within window is flagged non-cross-source`() = runBlocking {
        // 同一商家、同金额、1 分钟内连续消费多次：指纹相同，但必须标记为「同渠道」，
        // 让 IngestPipeline 降级到待确认，而不是静默自动合并（否则真实消费会被吞）。
        val probe = LedgerDuplicateResolver(FakeLedgerRepository())
        val fp = probe.fingerprintOf(txn("x", -2500, "星巴克"))
        val existing = txn("old", -2500, "星巴克", sourceId = "notify").copy(fingerprint = fp)
        val incoming = txn("new", -2500, "星巴克", sourceId = "notify").copy(fingerprint = fp)
        val (r, _) = resolver(existing)
        val dups = r.findDuplicates(incoming)
        assertEquals(1, dups.size)
        assertEquals(false, dups.first().crossSource)
    }

    // ------------------------------------------------------------ Tier-1 层级护栏（P0 修复）

    private fun fpOf(amountMinor: Long, counterparty: String): String =
        LedgerDuplicateResolver(FakeLedgerRepository()).fingerprintOf(txn("x", amountMinor, counterparty))

    @Test
    fun `tier1 - two payment channels of the same shop must NOT auto merge`() = runBlocking {
        // 回归：微信通知 + 支付宝通知（同店、同金额、3 分钟内、商户名恰好一致）。
        // 一次消费不可能同时走两个支付通道 ⇒ 只能是两笔真实消费 ⇒ 绝不得静默合并。
        val fp = fpOf(-1800, "星巴克")
        val wechat = txn("wx", -1800, "星巴克", sourceId = "notify", platformId = "wechat").copy(fingerprint = fp)
        val alipay = txn("ali", -1800, "星巴克", sourceId = "notify_alipay", platformId = "alipay").copy(fingerprint = fp)
        val (r, _) = resolver(wechat)
        val dups = r.findDuplicates(alipay)
        assertEquals(1, dups.size, "指纹相同 ⇒ 仍会作为候选浮出（提示用户）")
        assertTrue(dups.first().crossSource)
        assertFalse(
            r.canAutoMerge(alipay, dups.first()),
            "PAYMENT↔PAYMENT 是两笔消费，必须降级为待确认，不得静默合并",
        )
    }

    @Test
    fun `tier1 - two order platforms of the same amount must NOT auto merge`() = runBlocking {
        val fp = fpOf(-8800, "某某商户")
        val meituan = txn("mt", -8800, "某某商户", sourceId = "notify", platformId = "meituan").copy(fingerprint = fp)
        val taobao = txn("tb", -8800, "某某商户", sourceId = "bill_import", platformId = "taobao").copy(fingerprint = fp)
        val (r, _) = resolver(meituan)
        val dups = r.findDuplicates(taobao)
        assertEquals(1, dups.size)
        assertFalse(r.canAutoMerge(taobao, dups.first()), "两个下单平台 ⇒ 两笔消费")
    }

    @Test
    fun `tier1 - a payment channel plus a bank card still auto merges`() = runBlocking {
        // 反向护栏：微信消费通知 + 银行扣款短信是 **v1.0 核心能力**，护栏不得把它一起拒掉。
        val fp = fpOf(-1800, "星巴克")
        val wechat = txn("wx", -1800, "星巴克", sourceId = "notify", platformId = "wechat").copy(fingerprint = fp)
        val bank = txn("bk", -1800, "星巴克", sourceId = "sms", platformId = "bank").copy(fingerprint = fp)
        val (r, _) = resolver(wechat)
        val dups = r.findDuplicates(bank)
        assertEquals(1, dups.size)
        assertTrue(
            r.canAutoMerge(bank, dups.first()),
            "层级互补（PAYMENT↔BANK）⇒ 允许自动合并（功能不得倒退）",
        )
    }

    @Test
    fun `tier1 - the same bank channel captured twice still auto merges`() = runBlocking {
        // Bug 2 的落点：银行短信 + 银行 App 动账通知是**同一条** `bank` 通道被两个来源抓到 ⇒ 同一笔。
        val fp = fpOf(-505_500, "工商银行")
        val fromSms = txn("sms-1", -505_500, "工商银行", sourceId = "sms", platformId = "bank").copy(fingerprint = fp)
        val fromApp = txn("notify-1", -505_500, "工商银行", sourceId = "notify", platformId = "bank").copy(fingerprint = fp)
        val (r, _) = resolver(fromSms)
        val dups = r.findDuplicates(fromApp)
        assertEquals(1, dups.size)
        assertTrue(
            r.canAutoMerge(fromApp, dups.first()),
            "同一条银行通道被重复抓取 ⇒ 同一笔，必须自动合并（否则银行收入又会被记两次）",
        )
    }

    @Test
    fun `tier1 - complementary levels of the same shop still auto merges`() = runBlocking {
        val fp = fpOf(-8800, "某某商户")
        val order = txn("mt", -8800, "某某商户", sourceId = "notify", platformId = "meituan").copy(fingerprint = fp)
        val pay = txn("wx", -8800, "某某商户", sourceId = "bill_import", platformId = "wechat").copy(fingerprint = fp)
        val (r, _) = resolver(order)
        val dups = r.findDuplicates(pay)
        assertEquals(1, dups.size)
        assertTrue(r.canAutoMerge(pay, dups.first()), "层级互补 ⇒ 允许自动合并")
    }

    @Test
    fun `tier1 known boundary - same brand different stores collide on fingerprint but are not auto merged`() = runBlocking {
        // normalize **有意**抹掉括号门店后缀（同一笔常一个带门店、一个不带），
        // 已知边界：同品牌不同门店 + 同金额 + 3 分钟 + 跨渠道 ⇒ 指纹也会撞上。
        // 兜底靠层级护栏：两侧通常同层级（都 unknown / 都 bank）⇒ 拒绝自动合并 ⇒ 降级待确认。
        val a = txn("a", -3300, "星巴克(国贸店)", sourceId = "notify")
        val b = txn("b", -3300, "星巴克(南京西路店)", sourceId = "sms")
        assertEquals(fpOf(-3300, "星巴克"), LedgerDuplicateResolver(FakeLedgerRepository()).fingerprintOf(a), "抹括号是既定行为")
        assertEquals(
            LedgerDuplicateResolver(FakeLedgerRepository()).fingerprintOf(a),
            LedgerDuplicateResolver(FakeLedgerRepository()).fingerprintOf(b),
            "两个不同门店名归一化后相同 ⇒ 指纹相同（已记录的边界）",
        )

        val fp = LedgerDuplicateResolver(FakeLedgerRepository()).fingerprintOf(a)
        val (r, _) = resolver(a.copy(fingerprint = fp))
        val dups = r.findDuplicates(b.copy(fingerprint = fp))
        assertEquals(1, dups.size, "指纹相同 ⇒ 仍浮出为候选，提示用户核对")
        assertFalse(
            r.canAutoMerge(b.copy(fingerprint = fp), dups.first()),
            "NONE↔NONE（不同门店都没识别出平台）不得静默合并",
        )
    }

    // ------------------------------------------------------------ merge / unmerge

    @Test
    fun `merge marks duplicates MERGED and primary CONFIRMED`() = runBlocking {
        val primary = txn("p", -2500, "星巴克", status = TxnStatus.RAW)
        val dup = txn("d", -2500, "星巴克", status = TxnStatus.RAW)
        val (r, repo) = resolver(primary, dup)
        r.merge("p", listOf("d"))
        assertEquals(TxnStatus.MERGED, repo.findById("d")!!.status)
        assertEquals(TxnStatus.CONFIRMED, repo.findById("p")!!.status)
    }

    @Test
    fun `unmerge restores RAW for manual review`() = runBlocking {
        val dup = txn("d", -2500, "星巴克", status = TxnStatus.MERGED)
        val (r, repo) = resolver(dup)
        r.unmerge("d")
        assertEquals(TxnStatus.RAW, repo.findById("d")!!.status)
    }

    @Test
    fun `resolver id is stable`() {
        val (r, _) = resolver()
        assertEquals("ledger_dup", r.id)
        assertEquals(LedgerDuplicateResolver.RESOLVER_ID, r.id)
    }

    // ------------------------------------------------------------ RED 用例（已知缺陷）

    @Test
    fun `Red_blank merchant must not collapse every same amount txn into one fingerprint`() {
        // 缺陷 LedgerDuplicateResolver.kt:31 —— 指纹只有 hash(金额|归一化商户)。
        // 商户名为空（通知/短信解析失败的常见结果）时，同金额的所有流水指纹完全相同，
        // 3 分钟窗口内会被 IngestPipeline 自动合并，真实消费被静默吞掉。
        val (r, _) = resolver()
        val coffee = txn("a", -2500, "", sourceId = "notify")
        val taxi = txn("b", -2500, "", sourceId = "notify")
        assertNotEquals(
            r.fingerprintOf(coffee),
            r.fingerprintOf(taxi),
            "商户名为空时必须引入 sourceRef 等第二信号，不能让所有同金额流水同指纹",
        )
    }
}
