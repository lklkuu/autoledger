package com.autoledger.feature.dedup

import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.MatchTier
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import com.autoledger.core.model.capture.CaptureSourceIds
import com.autoledger.core.model.platform.PlatformCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * QA 独立复验（必修①）：**Tier-1 层级护栏 `tierOneAllowsAutoMerge`**。
 *
 * 本文件**刻意不复用**工程师的用例，独立断言三条分支：
 *  1. 层级不同（ORDER↔PAYMENT / ORDER↔BANK / ORDER↔NONE）⇒ 放行；
 *  2. 同层级且**不同通道**（微信 vs 支付宝、美团 vs 淘宝）⇒ 拒绝；
 *  3. 同层级且**同一条通道**（`bank` ↔ `bank`）⇒ 放行 —— 这是对我原判断「同层级一律拒」的偏离，
 *     必须独立确认它**不会**把「银行短信 + 银行 App 动账通知」退回待确认（否则 Bug 2 回归）。
 *
 * 关键区分：判据是「同层级 **且不同 platformId**」，不是单纯同层级。
 */
class TierOneGuardrailAuditTest {

    private val anchor = 1_700_000_000_000L

    private val order1 = "meituan"
    private val order2 = "taobao"
    private val pay1 = "wechat"
    private val pay2 = "alipay"
    private val bank = PlatformCatalog.BANK_ID
    private val none = PlatformCatalog.UNKNOWN_ID

    private fun txn(
        id: String,
        platformId: String,
        sourceId: String,
        occurredAt: Long = anchor,
        amountMinor: Long = -1_800L,
        counterparty: String = "星巴克",
        status: TxnStatus = TxnStatus.CONFIRMED,
        fingerprint: String = "fp-$id",
    ) = LedgerTransaction(
        id = id,
        amountMinor = amountMinor,
        occurredAtMillis = occurredAt,
        type = if (amountMinor < 0) TxnType.EXPENSE else TxnType.INCOME,
        counterparty = counterparty,
        sourceId = sourceId,
        sourceRef = "$sourceId:$id",
        status = status,
        fingerprint = fingerprint,
        platformId = platformId,
    )

    private fun resolver(vararg existing: LedgerTransaction) =
        LedgerDuplicateResolver(FakeLedgerRepository(existing.toList()))

    // ------------------------------------------------------------------ 纯函数真值表（独立穷举）

    @Test
    fun `different tiers are always allowed`() {
        // ORDER↔PAYMENT / ORDER↔BANK / ORDER↔NONE，双向
        assertTrue(tierOneAllowsAutoMerge(order1, pay1), "美团下单 + 微信付款")
        assertTrue(tierOneAllowsAutoMerge(pay1, order1))
        assertTrue(tierOneAllowsAutoMerge(order1, bank), "美团下单 + 银行卡扣款")
        assertTrue(tierOneAllowsAutoMerge(bank, order1))
        assertTrue(tierOneAllowsAutoMerge(order1, none), "美团通知 + 未识别平台")
        assertTrue(tierOneAllowsAutoMerge(none, order1))
        // PAYMENT↔BANK 层级不同 ⇒ Tier-1 放行（与 Tier-2 的 REVIEW 不同，见 KDoc）
        assertTrue(tierOneAllowsAutoMerge(pay1, bank), "微信通知 + 银行卡短信（商户同名 ⇒ 指纹相同）")
        assertTrue(tierOneAllowsAutoMerge(bank, pay1))
    }

    @Test
    fun `same tier but different channel is always rejected`() {
        // 一次消费不可能同时走两个支付通道 / 发生在两个下单平台
        assertFalse(tierOneAllowsAutoMerge(pay1, pay2), "微信 vs 支付宝 = 两笔真实消费")
        assertFalse(tierOneAllowsAutoMerge(pay2, pay1))
        assertFalse(tierOneAllowsAutoMerge(order1, order2), "美团 vs 淘宝 = 两笔消费")
        assertFalse(tierOneAllowsAutoMerge(order2, order1))
    }

    @Test
    fun `both sides without tier information are rejected`() {
        assertFalse(tierOneAllowsAutoMerge(none, none), "unknown ↔ unknown：没有任何层级信息")
        assertFalse(tierOneAllowsAutoMerge(none, "an-unlisted-platform"), "未收录 ID 也按 NONE 处理")
        assertFalse(tierOneAllowsAutoMerge("an-unlisted-platform", none))
    }

    @Test
    fun `the same bank channel captured twice is still allowed (Bug 2 must not regress)`() {
        // `bank` 在目录里只有一个 ID：「银行短信」与「银行 App 动账通知」都落它 ⇒ 同一条通道被重复抓取
        assertTrue(tierOneAllowsAutoMerge(bank, bank), "同一条银行通道 ⇒ 必须仍能自动合并")
        // 同一支付通道被重复抓取（不同采集来源）同理放行
        assertTrue(tierOneAllowsAutoMerge(pay1, pay1))
        assertTrue(tierOneAllowsAutoMerge(order1, order1))
    }

    // ------------------------------------------------------------------ 端到端（走 findDuplicates + canAutoMerge）

    @Test
    fun `P0-1 regression - wechat and alipay at the same shop are never silently merged`() = runBlocking<Unit> {
        // 用户点名的最危险场景：一笔走微信通知、一笔走支付宝通知，商户都「星巴克」、都是 18 元。
        // 商户名相同 ⇒ 指纹相同 ⇒ 走 Tier-1；修复前 Tier-1 不看层级 ⇒ 被静默合并。
        val wechat = txn("wx", pay1, "notify_wechat", fingerprint = "fp-same-shop")
        val alipay = txn("ali", pay2, "notify_alipay", fingerprint = "fp-same-shop")
        val r = resolver(wechat)

        val dups = r.findDuplicates(alipay)
        assertEquals(1, dups.size, "指纹相同 ⇒ 仍是 Tier-1 候选，交由用户确认")
        assertEquals(MatchTier.FINGERPRINT, dups.single().tier)
        assertFalse(
            r.canAutoMerge(alipay, dups.single()),
            "一次消费不可能同时走微信与支付宝 ⇒ 只能是两笔真实消费，绝不能静默合并（会吞账）",
        )
    }

    @Test
    fun `two different order platforms with the same merchant name are never silently merged`() = runBlocking<Unit> {
        val meituan = txn("mt", order1, "notify_mt", counterparty = "某某旗舰店", fingerprint = "fp-same-shop2")
        val taobao = txn("tb", order2, "notify_tb", counterparty = "某某旗舰店", fingerprint = "fp-same-shop2")
        val r = resolver(meituan)

        val dups = r.findDuplicates(taobao)
        assertEquals(1, dups.size, "指纹相同 ⇒ 候选仍浮出")
        assertFalse(r.canAutoMerge(taobao, dups.single()), "两个下单平台 = 两笔消费")
    }

    @Test
    fun `bank sms plus bank app notification stays auto mergeable (not pushed to review)`() = runBlocking<Unit> {
        // Bug 2 的原始场景：工行短信(sourceId=sms) + 工行 App 动账通知(sourceId=notify)，
        // 同金额、同商户（归一化后同为「工商银行」）、相差几十秒 ⇒ 必须仍能自动合并。
        val sms = txn("sms", bank, "sms", amountMinor = 505_500L, counterparty = "工商银行", fingerprint = "fp-same-bank")
        val app = txn("app", bank, "notify", occurredAt = anchor + 30_000L, amountMinor = 505_500L, counterparty = "工商银行", fingerprint = "fp-same-bank")
        val r = resolver(sms)

        val dups = r.findDuplicates(app)
        assertEquals(1, dups.size, "指纹相同 ⇒ Tier-1 候选")
        assertTrue(
            r.canAutoMerge(app, dups.single()),
            "同一条 bank 通道被两个采集来源抓到 ⇒ 必须自动合并（护栏若一律拒同层级，Bug 2 会回归）",
        )
    }

    @Test
    fun `unknown plus unknown with the same merchant is not silently merged`() = runBlocking<Unit> {
        val a = txn("a", none, "sms", fingerprint = "fp-same-unknown")
        val b = txn("b", none, CaptureSourceIds.BILL_IMPORT, fingerprint = "fp-same-unknown")
        val r = resolver(a)
        val dups = r.findDuplicates(b)
        assertEquals(1, dups.size, "指纹相同 ⇒ 候选仍浮出（交给用户）")
        assertFalse(r.canAutoMerge(b, dups.single()), "NONE↔NONE 没有层级信息可依据，必须保守")
    }

    // ------------------------------------------------------------------ P0-2 反例（反驳「可接受」）

    /**
     * ⚠️ 本用例**预期失败**（红 = 反驳「P0-2 可接受」）。
     *
     * `normalize()` 抹掉括号门店后缀 ⇒「中石化(朝阳站)」与「中石化(海淀站)」归一化后都是「中石化」。
     * 团队判据是「这种情形两侧平台通常同层级（同品牌 POS 都落 bank）或都是 unknown ⇒ 护栏能兜住」。
     * 但**只要两侧平台层级不同**（一侧是支付通道通知、另一侧是银行短信），护栏就会放行：
     *   A：微信支付通知「中石化(朝阳站)」300 元（wechat = PAYMENT）
     *   B：银行 POS 短信「中石化(海淀站)」300 元（bank = BANK）
     * 两笔**不同加油站、不同车**的真实消费，同金额、3 分钟内 ⇒ 被判成同一指纹 ⇒ Tier-1
     * ⇒ `tierOneAllowsAutoMerge(PAYMENT, BANK)` = true（层级不同）⇒ **静默合并、吞掉一笔**。
     *
     * 即：抹括号的风险**并非**只落在「同层级」上，团队给出的兜底理由不成立。
     */
    @Test
    fun `same brand different storefronts with different tiers must not be merged`() = runBlocking<Unit> {
        val wechat = txn("a", pay1, "notify_wechat", amountMinor = -30_000L, counterparty = "中石化(朝阳站)", fingerprint = "fp-same-brand")
        val bankSms = txn("b", bank, "sms", occurredAt = anchor + 60_000L, amountMinor = -30_000L, counterparty = "中石化(海淀站)", fingerprint = "fp-same-brand")
        val r = resolver(wechat)

        val dups = r.findDuplicates(bankSms)
        assertEquals(1, dups.size, "前置：抹括号后指纹相同 ⇒ 被当成 Tier-1 候选")
        assertFalse(
            r.canAutoMerge(bankSms, dups.single()),
            "两个不同加油站的两笔真实消费，绝不能被抹括号 + 层级护栏放行而静默合并",
        )
    }
}
