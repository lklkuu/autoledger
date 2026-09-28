package com.autoledger.feature.platform

import com.autoledger.core.model.platform.PlatformCatalog
import com.autoledger.core.model.platform.PlatformContext
import com.autoledger.core.model.platform.PlatformResolver
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * [KeywordPlatformResolver] 的行为护栏。
 *
 * 用例编号对应 `docs/design/consumer-platform-redesign.md` §7 的 16 条边界情况；
 * 编号 #9/#10/#14/#15/#16 落在 T03/T04/T05，会在各自任务的测试里补。
 */
class KeywordPlatformResolverTest {

    private val resolver = KeywordPlatformResolver()

    private fun resolve(
        rawText: String? = null,
        counterparty: String? = null,
        packageName: String? = null,
        sourceId: String = "notify",
    ) = resolver.resolve(PlatformContext(rawText, counterparty, packageName, sourceId))

    // ---------------------------------------------------------------- §7 #1 / #2

    @Test
    fun `case 1 - empty input resolves to unknown without throwing`() {
        val r = resolve()
        assertEquals(PlatformCatalog.UNKNOWN_ID, r.platformId)
        assertEquals(0f, r.confidence)
        assertTrue(r.candidates.isEmpty())
        assertFalse(r.ambiguous)
    }

    @Test
    fun `case 2 - text with no keyword hit still resolves to unknown`() {
        val r = resolve("您尾号1234的储蓄卡于10月3日消费 1,280.00 元，余额 8,000.00 元", null, "sms:inbox", "sms:inbox")
        assertEquals(PlatformCatalog.UNKNOWN_ID, r.platformId)
        assertEquals(0f, r.confidence)
        assertTrue(r.candidates.isEmpty())
    }

    // ---------------------------------------------------------------- §7 #8（短信无包名）

    @Test
    fun `case 8 - sms has no package so it falls back to keywords only`() {
        // 短信渠道的 packageName 是 "sms:inbox"，不命中任何平台包名映射 —— 这是正确的
        val r = resolve("您尾号1234的卡于10月2日消费支付宝-盒马 66.00元", "盒马", "sms:inbox", "sms:inbox")
        assertEquals("alipay", r.platformId, "银行短信里写了「支付宝」就该走关键词命中")
        assertEquals(0.90f, r.confidence)
    }

    @Test
    fun `case 8b - plain bank sms with no platform clue stays unknown`() {
        val r = resolve("您尾号1234的卡于10月3日 POS 消费 88.00 元", "沃尔玛", "sms:inbox", "sms:inbox")
        assertEquals(PlatformCatalog.UNKNOWN_ID, r.platformId)
    }

    // ---------------------------------------------------------------- §7 #4（未收录平台）

    @Test
    fun `case 4 - unregistered platform is not guessed`() {
        val r = resolve("京东支付 59.00 元")
        assertEquals(PlatformCatalog.UNKNOWN_ID, r.platformId, "未收录的平台不得硬猜")
        assertEquals(0f, r.confidence)
        assertTrue(r.candidates.isEmpty())
    }

    // ---------------------------------------------------------------- §7 #5（多平台冲突）

    @Test
    fun `case 5 - two order platforms tie on score are flagged ambiguous`() {
        // 抖音(0.60) 与 拼多多(0.60) 同分 ⇒ gap=0 < 0.15 ⇒ 歧义
        val r = resolve("抖音商城与拼多多均参与本次活动，实付 100.00 元")
        assertTrue(r.ambiguous, "两个都说得通时必须打角标")
        assertFalse(r.platformId == PlatformCatalog.UNKNOWN_ID, "歧义也要给出 best-effort 取值，不能因歧义丢账")
        assertEquals(2, r.candidates.size)
        assertEquals(0.60f, r.candidates[0].score)
        assertEquals(0.60f, r.candidates[1].score)
    }

    @Test
    fun `order platform wins over payment channel - taobao ordered paid via alipay`() {
        // 团队口径（Q2）：区分「下单平台」与「支付通道」，同时命中时取下单平台。
        // 淘宝订单几乎必然通过支付宝支付，若不区分，alipay(0.90) 会恒压 taobao(0.60)，
        // 「淘宝」这个平台在账单里就永远不出现。
        val r = resolve("淘宝订单通过支付宝付款 88.00 元")
        assertEquals("taobao", r.platformId, "消费发生在淘宝，支付宝只是付款通道")
        assertEquals(0.60f, r.confidence)
        assertFalse(r.ambiguous, "下单平台 vs 支付通道有确定答案，不算歧义")
        assertEquals(listOf("taobao"), r.candidates.map { it.platformId }, "候选留在同一角色内，不要把通道混进来")
    }

    @Test
    fun `payment channel alone still wins - offline alipay scan`() {
        val r = resolve("支付宝付款 25.80 元，收款方 全家便利店")
        assertEquals("alipay", r.platformId, "只命中支付通道 ⇒ 取通道（线下扫码就是这种）")
        assertEquals(0.90f, r.confidence)
        assertFalse(r.ambiguous)
    }

    @Test
    fun `order platform wins over payment channel - meituan ordered paid via wechat`() {
        val r = resolve("美团订单已通过微信支付 45.00 元")
        assertEquals("meituan", r.platformId)
        assertEquals(0.60f, r.confidence)
        assertFalse(r.ambiguous)
    }

    @Test
    fun `order platform wins over payment channel - pdd ordered paid via wechat`() {
        val r = resolve("拼多多订单使用微信支付完成付款 19.90 元")
        assertEquals("pdd", r.platformId)
        assertFalse(r.ambiguous)
    }

    @Test
    fun `a weak order clue does not override a strong payment channel`() {
        // 护栏：下单平台必须至少中词强度才配覆盖通道。
        // 「阿里巴巴」是 taobao 的弱线索(0.35)，不能盖掉确定的支付宝(0.90) ——
        // 这里钱确实是从支付宝出去的，阿里巴巴只是收款公司名。
        val r = resolve("支付宝付款 88.00 元，收款方 阿里巴巴集团")
        assertEquals("alipay", r.platformId)
        assertEquals(0.90f, r.confidence)
    }

    @Test
    fun `payment-channel package does NOT mask the order platform given in the text`() {
        // 淘宝下单的付款通知**正是支付宝 App 发出的**：包名 com.eg.android.AlipayGphone(0.95)。
        // 若一律让包名压顶，淘宝将永不出现 —— 「按消费平台统计」名存实亡。
        //
        // 团队已拍板：**角色优先于包名**。
        //   包名是**支付通道**、正文给出**下单平台** ⇒ 下单平台胜出；
        //   置信度取 0.85（> CONFIRM_THRESHOLD 0.75 ⇒ 不打「待确认」角标；
        //                < 0.95 ⇒ 保留「包名仍是更强证据」的层级关系）。
        val r = resolve("支付宝成功付款 88.00 元 - 淘宝", null, "com.eg.android.AlipayGphone")
        assertEquals("taobao", r.platformId, "消费发生在淘宝；支付宝只是付款方式")
        assertEquals(0.85f, r.confidence)
        assertFalse(r.ambiguous)
    }

    @Test
    fun `order-type package keeps top priority when it is the notifier`() {
        // 反向护栏：包名本身是**下单平台**时，仍维持最高优先（消费就发生在该平台）。
        val r = resolve("订单已支付 88.00 元", null, "com.taobao.taobao")
        assertEquals("taobao", r.platformId)
        assertEquals(0.95f, r.confidence)
        assertFalse(r.ambiguous)
    }

    @Test
    fun `strong order keyword still beats a strong payment keyword`() {
        // 「美团外卖」是强词(0.90)，与支付宝强词同分，但角色优先 ⇒ 美团
        val r = resolve("在美团外卖下单后用支付宝付款 45.00 元")
        assertEquals("meituan", r.platformId)
        assertEquals(0.90f, r.confidence)
        assertFalse(r.ambiguous)
    }

    @Test
    fun `meituan app notification mentioning alipay is NOT ambiguous`() {
        // 最常见的真实冲突：美团 App 发出的通知里写着「已通过支付宝支付」。
        // 包名 0.95 与 支付宝强词 0.90 只差 0.05 —— 若照搬分差规则会误判歧义。
        val r = resolve("您的美团订单已通过支付宝支付 45.00 元", null, "com.sankuai.meituan")
        assertEquals("meituan", r.platformId, "平台是美团（订单所在平台），支付宝只是付款方式")
        assertEquals(0.95f, r.confidence)
        assertFalse(r.ambiguous, "包名是系统给的确认事实，它赢了就没有不确定的余地")
    }

    // ---------------------------------------------------------------- §7 #7（包名与文本冲突）

    @Test
    fun `case 7 - package name outranks conflicting text`() {
        val r = resolve("支付宝付款 30.00 元", null, "com.tencent.mm")
        assertEquals("wechat", r.platformId)
        assertEquals(0.95f, r.confidence)
        assertFalse(r.ambiguous)
        assertTrue(r.candidates.first().evidence.contains("包名 com.tencent.mm"), "evidence 要能解释为什么这么判")
    }

    @Test
    fun `package name alone is enough to resolve`() {
        val r = resolve("您有一笔新的支付", null, "com.tencent.mm")
        assertEquals("wechat", r.platformId)
        assertEquals(0.95f, r.confidence)
    }

    // ---------------------------------------------------------------- §7 #6（平台与商户互不覆盖）

    @Test
    fun `case 6 - merchant name that looks like a platform still resolves both independently`() {
        val r = resolve("订单已支付 32.00 元", "美团外卖")
        assertEquals("meituan", r.platformId)
        // 商户照写「美团外卖」，与平台取值互不干涉（二者是不同字段，本用例锁的是"平台能从商户名取到线索"）
        assertTrue(r.candidates.first().evidence.contains("商户名"), "要能看出线索来自商户名")
    }

    // ---------------------------------------------------------------- §7 #13（手动补记）

    @Test
    fun `case 13 - manual entry with merchant only gives a lower confidence platform`() {
        val r = resolve(null, "美团")
        assertEquals("meituan", r.platformId)
        assertEquals(0.60f, r.confidence, "商户名命中中词 ⇒ 0.60 < 0.75 ⇒ UI 必须打角标提示确认")
        assertTrue(r.confidence < PlatformResolver.CONFIRM_THRESHOLD)
    }

    @Test
    fun `case 13b - manual entry without platform stays unknown rather than guessing`() {
        val r = resolve(null, "楼下面馆")
        assertEquals(PlatformCatalog.UNKNOWN_ID, r.platformId)
    }

    // ---------------------------------------------------------------- 分层与阈值

    @Test
    fun `tier ladder is respected and the strongest signal wins per platform`() {
        // 「财付通」弱词 0.35 在正文，「微信支付」强词 0.90 在正文 ⇒ 同一平台取最高分
        val r = resolve("财付通-微信支付 12.00 元")
        assertEquals("wechat", r.platformId)
        assertEquals(0.90f, r.confidence)
    }

    @Test
    fun `weak clue alone is adopted but stays below the confirm threshold`() {
        // 「财付通」是微信持牌主体：银行短信里出现它 ⇒ 钱确实走了微信支付。
        // 0.35 ≥ UNKNOWN_THRESHOLD ⇒ 采用；但 0.35 < CONFIRM_THRESHOLD(0.75) ⇒ UI 打角标。
        val r = resolve("您尾号1234的卡于10月1日消费财付通-2zero首饰屋 398.00元", "2zero首饰屋", "sms:inbox", "sms:inbox")
        assertEquals("wechat", r.platformId)
        assertEquals(0.35f, r.confidence)
        assertTrue(r.confidence < PlatformResolver.CONFIRM_THRESHOLD, "必须能被 UI 识别为「待确认」")
        assertTrue(r.candidates.isEmpty(), "0.35 < AMBIGUOUS_TOP_FLOOR(0.50) ⇒ 没有真候选可推荐")
    }

    @Test
    fun `weak clue in the merchant field also counts`() {
        val r = resolve("您尾号1234的卡消费 398.00元", "财付通-2zero首饰屋", "sms:inbox", "sms:inbox")
        assertEquals("wechat", r.platformId)
        assertEquals(0.35f, r.confidence)
    }

    @Test
    fun `candidates are capped at TOP_N and sorted by score`() {
        // 构造 4 个平台同时命中，验证只留前 3 且降序
        val r = resolve("抖音支付、拼多多、淘宝、天猫、美团 合计 500.00 元")
        assertTrue(r.candidates.size <= PlatformResolver.TOP_N)
        assertEquals(r.candidates.sortedByDescending { it.score }.map { it.platformId }, r.candidates.map { it.platformId })
        assertTrue(r.candidates.all { it.score >= PlatformResolver.AMBIGUOUS_TOP_FLOOR })
    }

    @Test
    fun `candidate order is stable across repeated calls`() {
        val text = "抖音商城与拼多多均参与本次活动，实付 100.00 元"
        val first = resolve(text).candidates.map { it.platformId }
        repeat(5) {
            assertEquals(first, resolve(text).candidates.map { it.platformId }, "候选顺序不得抖动")
        }
    }

    @Test
    fun `latin brand name is matched case insensitively`() {
        assertEquals("alipay", resolve("alipay payment 15.00").platformId)
        assertEquals("alipay", resolve("Alipay 付款 15.00 元").platformId)
        assertEquals("alipay", resolve("ALIPAY 15.00").platformId)
    }

    // ---------------------------------------------------------------- §7 #12（退款/划转照常识别）

    @Test
    fun `case 12 - resolver has no opinion about transaction type`() {
        // 退款 / 内部划转也要能识别平台（便于追溯「抖音退款」）。
        // 解析器输入里根本没有 type 字段 ⇒ 天然一视同仁，这里锁住这个契约不被后续改动破坏。
        val refund = resolve("抖音支付退款 199.00 元已退回")
        assertEquals("douyin", refund.platformId)
        val transfer = resolve("转账给朋友 500.00 元")
        assertEquals(PlatformCatalog.UNKNOWN_ID, transfer.platformId)
    }

    // ---------------------------------------------------------------- 契约

    @Test
    fun `sourceId never influences the verdict`() {
        // sourceId 是技术追溯字段，不参与平台判定
        val a = resolve("美团外卖 32.00 元", sourceId = "notify")
        val b = resolve("美团外卖 32.00 元", sourceId = "sms:inbox")
        val c = resolve("美团外卖 32.00 元", sourceId = "bill_import")
        assertEquals(a, b)
        assertEquals(b, c)
    }

    @Test
    fun `resolver id is stable`() {
        assertEquals("keyword_platform", resolver.id)
    }
}
