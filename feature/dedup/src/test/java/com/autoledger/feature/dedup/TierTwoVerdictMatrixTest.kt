package com.autoledger.feature.dedup

import com.autoledger.core.model.platform.PlatformCatalog
import com.autoledger.core.model.platform.PlatformPriority
import com.autoledger.core.model.platform.priorityOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * QA 独立复审（Tier-2 **全组合**）：把 [complementaryVerdict] 的判定表**逐格钉死**。
 *
 * ## 为什么单独做一张矩阵
 * 需求主场景（`ORDER↔PAYMENT`）与新缝隙（`ORDER↔NONE`）往往被反复覆盖，其余格子只靠"读代码觉得对"。
 * 这一份把 `5 层级 × 5 层级 = 25 格`**全部穷举**，任何一格被"顺手改松"都会立刻变红 —— 尤其是：
 *  - `PAYMENT↔BANK` / `E_WALLET↔BANK` / `PAYMENT↔E_WALLET` 必须**永远**停 [ComplementaryVerdict.REVIEW]，
 *    一旦被改成 AUTO_MERGE，「微信 88 + 银行卡扣 88（其实是先充值再消费的两笔）」就会被静默吞掉；
 *  - `ORDER↔ORDER` / `PAYMENT↔PAYMENT` / `E_WALLET↔E_WALLET`（**不同 id**）与 `NONE↔NONE` 必须**永远** REJECT，
 *    否则"同层级 = 两笔真实消费"的最后一道闸门失守；
 *  - 同层级 **同 id**（如同一条 `bank` / `digital_rmb` 通道被重复抓取）⇒ REVIEW（交用户，不静默双记）。
 *
 * ## 层级（本次新增 `E_WALLET` 中间层）
 * `ORDER(4) > PAYMENT(3) > E_WALLET(2) > BANK(1) > NONE(0)`。
 *
 * 纯函数、无 Room，故可与 Room 测试并行、毫秒级。
 */
class TierTwoVerdictMatrixTest {

    private val order = "meituan"
    private val payment = "wechat"
    private val eWallet = "digital_rmb"
    private val bank = PlatformCatalog.BANK_ID
    private val none = PlatformCatalog.UNKNOWN_ID

    /** 前置自检：确保本用例假设的五个平台层级与实现一致（改了目录会被这里拦住）。 */
    @Test
    fun `the five reference platforms map to the five tiers as assumed`() {
        assertEquals(PlatformPriority.ORDER, priorityOf(order))
        assertEquals(PlatformPriority.PAYMENT, priorityOf(payment))
        assertEquals(PlatformPriority.E_WALLET, priorityOf(eWallet))
        assertEquals(PlatformPriority.BANK, priorityOf(bank))
        assertEquals(PlatformPriority.NONE, priorityOf(none))
    }

    @Test
    fun `exactly one ORDER side always auto merges, against every other tier`() {
        // 一侧说"在哪个平台花"，另一侧说"钱从哪出" ⇒ 同一笔的两个侧面。
        for (other in listOf(payment, eWallet, bank, none)) {
            assertEquals(ComplementaryVerdict.AUTO_MERGE, complementaryVerdict(order, other), "ORDER ↔ $other")
            assertEquals(ComplementaryVerdict.AUTO_MERGE, complementaryVerdict(other, order), "$other ↔ ORDER")
        }
    }

    @Test
    fun `every pair among the money channels is a review case and is symmetric`() {
        // {PAYMENT, E_WALLET, BANK} 两两组合（含同 id）：都交用户。
        // 用户方针「能 REVIEW 就别 REJECT」—— 这些"钱从哪条通道/哪张卡出去"的组合，先浮出来让用户裁决。
        val channels = listOf(payment, eWallet, bank)
        for (a in channels) for (b in channels) {
            assertEquals(
                ComplementaryVerdict.REVIEW,
                complementaryVerdict(a, b),
                "资金通道组合 $a ↔ $b 必须是 REVIEW（既不静默合并、也不静默丢失）",
            )
        }
    }

    @Test
    fun `same-tier different ids are rejected`() {
        // 一次消费不可能同时走两个支付通道 / 发生在两个下单平台 / 走两条官方数字通道 ⇒ 只能是两笔。
        assertEquals(ComplementaryVerdict.REJECT, complementaryVerdict(payment, "alipay"), "微信 vs 支付宝")
        assertEquals(ComplementaryVerdict.REJECT, complementaryVerdict("alipay", payment))
        assertEquals(ComplementaryVerdict.REJECT, complementaryVerdict(order, "taobao"), "美团 vs 淘宝")
        assertEquals(ComplementaryVerdict.REJECT, complementaryVerdict("taobao", order))
        assertEquals(ComplementaryVerdict.REJECT, complementaryVerdict(eWallet, "unionpay"), "数币 vs 云闪付")
        assertEquals(ComplementaryVerdict.REJECT, complementaryVerdict("unionpay", eWallet))
    }

    @Test
    fun `NONE against a money channel is conservatively rejected`() {
        // unknown 那条可能"什么都没识别出来"，没有互补证据 ⇒ 连候选都不是。
        for (ch in listOf(payment, eWallet, bank)) {
            assertEquals(ComplementaryVerdict.REJECT, complementaryVerdict(none, ch), "NONE ↔ $ch")
            assertEquals(ComplementaryVerdict.REJECT, complementaryVerdict(ch, none), "$ch ↔ NONE")
        }
    }

    /**
     * 穷举 25 格 + 对称性：把"哪一格是什么"写成**显式期望表**，
     * 实现若悄悄放宽/收紧任意一格，这里会精确指出是哪一对平台。
     */
    @Test
    fun `full 5-by-5 matrix matches the frozen expectation table and is symmetric`() {
        val tiers = listOf(order, payment, eWallet, bank, none)
        // 期望表：按 (行, 列) 层级顺序 ORDER=0, PAYMENT=1, E_WALLET=2, BANK=3, NONE=4
        //  - 恰好一侧 ORDER ⇒ AUTO_MERGE；
        //  - {PAYMENT,E_WALLET,BANK} 两两（含同 id）⇒ REVIEW；
        //  - 其它（同层级不同 id 不在此单代表矩阵内、NONE 相关）⇒ REJECT。
        val expected = arrayOf(
            arrayOf(ComplementaryVerdict.REVIEW, ComplementaryVerdict.AUTO_MERGE, ComplementaryVerdict.AUTO_MERGE, ComplementaryVerdict.AUTO_MERGE, ComplementaryVerdict.AUTO_MERGE),
            arrayOf(ComplementaryVerdict.AUTO_MERGE, ComplementaryVerdict.REVIEW, ComplementaryVerdict.REVIEW, ComplementaryVerdict.REVIEW, ComplementaryVerdict.REJECT),
            arrayOf(ComplementaryVerdict.AUTO_MERGE, ComplementaryVerdict.REVIEW, ComplementaryVerdict.REVIEW, ComplementaryVerdict.REVIEW, ComplementaryVerdict.REJECT),
            arrayOf(ComplementaryVerdict.AUTO_MERGE, ComplementaryVerdict.REVIEW, ComplementaryVerdict.REVIEW, ComplementaryVerdict.REVIEW, ComplementaryVerdict.REJECT),
            arrayOf(ComplementaryVerdict.AUTO_MERGE, ComplementaryVerdict.REJECT, ComplementaryVerdict.REJECT, ComplementaryVerdict.REJECT, ComplementaryVerdict.REJECT),
        )
        for (i in tiers.indices) {
            for (j in tiers.indices) {
                val actual = complementaryVerdict(tiers[i], tiers[j])
                assertEquals(
                    expected[i][j],
                    actual,
                    "第 $i 行 × 第 $j 列（${tiers[i]} ↔ ${tiers[j]}）判定不符；" +
                        "AUTO_MERGE 会把两笔真实消费合成一笔，REJECT 写反会让该合并的合并不了",
                )
            }
        }
        // 对称性：判定不得依赖"谁先到"
        for (a in tiers) for (b in tiers) {
            assertEquals(
                complementaryVerdict(a, b),
                complementaryVerdict(b, a),
                "判定必须对称（$a ↔ $b）",
            )
        }
    }

    /**
     * 单代表矩阵无法区分「同层级同 id」与「同层级不同 id」（每个层级只取一个代表）。
     * 本用例补上这条分叉，防止把「同层级一律 REJECT」当成实现。
     */
    @Test
    fun `within a tier, same id and different id diverge`() {
        // ⚠️ 注意：`bank ↔ digital_rmb` 是**不同层级**（BANK vs E_WALLET）⇒ REVIEW，不能用它来演示「同层级分叉」。
        // 正确的一对是**同一层级内**的同 id vs 不同 id —— 取 E_WALLET 层（数币 / 云闪付）：
        assertNotEquals(
            complementaryVerdict(eWallet, eWallet),
            complementaryVerdict(eWallet, "unionpay"),
            "同层级里，「同 id（同一条通道）」与「不同 id（不同通道）」必须分叉",
        )
        assertEquals(ComplementaryVerdict.REVIEW, complementaryVerdict(eWallet, eWallet), "同一条数币通道被重复抓取 ⇒ 交用户")
        assertEquals(ComplementaryVerdict.REJECT, complementaryVerdict(eWallet, "unionpay"), "数币 vs 云闪付（两条官方数字通道）⇒ 两笔")
        // PAYMENT 层的同一条通道重复抓取同理 ⇒ REVIEW
        assertEquals(ComplementaryVerdict.REVIEW, complementaryVerdict(payment, payment), "同一条支付通道被重复抓取 ⇒ 交用户")
        assertEquals(ComplementaryVerdict.REJECT, complementaryVerdict(payment, "alipay"), "微信 vs 支付宝 ⇒ 两笔")
    }
}
