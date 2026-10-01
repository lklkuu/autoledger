package com.autoledger.feature.platform

import com.autoledger.core.model.platform.PlatformContext
import com.autoledger.core.model.platform.PlatformResolver
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * QA 独立复验：`digital_rmb` 新增中词「数字钱包」(0.60) 会不会**压过本应更高的平台**
 * （微信 / 支付宝等 `PAYMENT`，美团等 `ORDER`）。
 *
 * ## 用户口径
 * 数币/云闪付优先级「**高于银行卡、低于微信支付宝**」；下单平台（美团/淘宝）层级最高。
 * 因此「一行文本里同时出现数币线索与微信/美团线索」时，**不应**被判成数币。
 *
 * ## 结论（本文件用实际引擎验证，不凭读码）
 * 不成立：识别**先比分数**（强词 0.90 > 中词 0.60），同分再按**目录 sortOrder**（微信 10 < 美团 30 <
 * 数币 80）决出。因此「数字钱包」0.60 既压不过 0.90 的«微信支付/云闪付»，也压不过同分的«美团»。
 * 唯一需要当心的是「数字人民币钱包」（含强词「数字人民币」⇒ 0.90）——但它仍**低于包名 0.95**，
 * 且与支付宝 0.90 同分时按 sortOrder 让位给支付宝。以下逐条钉死。
 */
class EwalletKeywordOverrideAuditTest {

    private val resolver = KeywordPlatformResolver()

    private fun resolve(
        rawText: String? = null,
        counterparty: String? = null,
        packageName: String? = null,
        sourceId: String = "notify",
    ) = resolver.resolve(PlatformContext(rawText, counterparty, packageName, sourceId))

    // ------------------------------------------------------------ 期望保留的修复效果

    @Test
    fun `wallet notification with only the medium word resolves to digital_rmb at 0-60`() {
        // 真实样本 [3]：只含「数字钱包」（无强词），此前被「尾号」拖成 bank(0.35)。
        val r = resolve("动账通知\n您尾号为4793的数字钱包支付给中电联京东共管钱包（0098）¥17.45", null, "com.icbc.wallet")
        assertEquals("digital_rmb", r.platformId, "「数字钱包」应把这类通知识别为数币")
        assertEquals(0.60f, r.confidence)
        assertTrue(r.confidence < PlatformResolver.CONFIRM_THRESHOLD, "0.60 < 0.75 ⇒ 仍打待确认角标")
    }

    // ------------------------------------------------------------ 覆盖假设的反证

    @Test
    fun `数字钱包 0-60 must NOT override a strong 微信支付 signal`() {
        // 含强词「微信支付」(0.90) 与中词「数字钱包」(0.60) ⇒ 微信胜。若被数币压过即为缺陷。
        val r = resolve("微信支付 已支付88元，本单通过数字钱包完成", null, null)
        assertEquals("wechat", r.platformId, "微信支付(0.90) 必须压过数字钱包(0.60)")
    }

    @Test
    fun `数字钱包 0-60 must NOT override a strong 云闪付 signal`() {
        val r = resolve("数字钱包支付88元，云闪付收单", null, null)
        assertEquals("unionpay", r.platformId, "云闪付(0.90) 必须压过数字钱包(0.60)")
    }

    @Test
    fun `数字钱包 0-60 must NOT override an ORDER platform at the same medium score`() {
        // 同分 0.60：美团(sortOrder 30) 与 数币(sortOrder 80) ⇒ 美团胜（下单平台优先）。
        val r = resolve("美团下单，数字钱包支付88元", null, null)
        assertEquals("meituan", r.platformId, "同分时下单平台应胜出，而不是数币")
    }

    @Test
    fun `数字人民币钱包 0-90 must NOT override 支付宝 at the same strong score`() {
        // 「数字人民币钱包」含强词「数字人民币」⇒ 0.90；「支付宝」也是 0.90。同分 ⇒ 按 sortOrder 支付宝(20) 胜数币(80)。
        val r = resolve("支付宝付款88元，付款方式：数字人民币钱包", null, null)
        assertEquals("alipay", r.platformId, "同分 0.90 时应让位给支付宝（sortOrder 更靠前）")
    }

    @Test
    fun `a wechat package still wins over the digital wallet keyword`() {
        // 包名 0.95 是系统事实，高于任何关键词。
        val r = resolve("付款通知\n数字人民币钱包支付¥17.45", null, "com.tencent.mm")
        assertEquals("wechat", r.platformId, "包名(0.95)必须压过关键词(0.90)")
    }

    @Test
    fun `数字人民币 strong keyword still wins over a mere 微信 medium keyword`() {
        // 边界：数币强词(0.90) vs 微信中词(0.60) ⇒ 数币胜。这是分数决定的**预期**行为（记录在案，非缺陷）。
        val r = resolve("数字人民币支付成功88元，商户：微信收款码", null, null)
        assertEquals("digital_rmb", r.platformId)
        assertTrue(r.confidence >= 0.90f)
    }
}
