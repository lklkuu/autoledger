package com.autoledger.feature.dedup

import com.autoledger.core.model.platform.PlatformCatalog
import com.autoledger.core.model.platform.PlatformPriority
import com.autoledger.core.model.platform.priorityOf
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * QA 独立复审（Tier-2 **其它组合**）：把 [complementaryVerdict] 的判定表**逐格钉死**。
 *
 * ## 为什么单独做一张矩阵
 * 前三轮我把注意力全压在 `ORDER↔PAYMENT`（需求主场景）与 `ORDER↔NONE`（新缝隙）上，
 * 剩下的 7 个格子只靠"读代码觉得对"。这一份把 `4 层级 × 4 层级 = 16 格`**全部穷举**，
 * 任何一格被"顺手改松"都会立刻变红 —— 尤其是：
 *  - `PAYMENT↔BANK` 必须**永远**停 [ComplementaryVerdict.REVIEW]，一旦被改成 AUTO_MERGE，
 *    「微信 88 + 银行卡扣 88（其实是先充值再消费的两笔）」就会被静默吞掉；
 *  - `ORDER↔ORDER` / `PAYMENT↔PAYMENT` / `NONE↔NONE` 必须**永远** REJECT，
 *    否则"同层级 = 两笔真实消费"的最后一道闸门失守；
 *  - `BANK↔BANK` 由 **必修⑤** 改判为 REVIEW（目录里 `BANK` 只有唯一 ID `bank`，同通道跨来源重复抓取 ⇒ 交用户），**不再是 REJECT**。
 *
 * 纯函数、无 Room，故可与 Room 测试并行、毫秒级。
 */
class TierTwoVerdictMatrixTest {

    private val order = "meituan"
    private val payment = "wechat"
    private val bank = PlatformCatalog.BANK_ID
    private val none = PlatformCatalog.UNKNOWN_ID

    /** 前置自检：确保本用例假设的四个平台层级与实现一致（改了目录会被这里拦住）。 */
    @Test
    fun `the four reference platforms map to the four tiers as assumed`() {
        assertEquals(PlatformPriority.ORDER, priorityOf(order))
        assertEquals(PlatformPriority.PAYMENT, priorityOf(payment))
        assertEquals(PlatformPriority.BANK, priorityOf(bank))
        assertEquals(PlatformPriority.NONE, priorityOf(none))
    }

    @Test
    fun `exactly one ORDER side always auto merges`() {
        // 一侧说"在哪个平台花"，另一侧说"钱从哪出" ⇒ 同一笔的两个侧面。
        assertEquals(ComplementaryVerdict.AUTO_MERGE, complementaryVerdict(order, payment))
        assertEquals(ComplementaryVerdict.AUTO_MERGE, complementaryVerdict(payment, order))
        assertEquals(ComplementaryVerdict.AUTO_MERGE, complementaryVerdict(order, bank))
        assertEquals(ComplementaryVerdict.AUTO_MERGE, complementaryVerdict(bank, order))
        assertEquals(ComplementaryVerdict.AUTO_MERGE, complementaryVerdict(order, none))
        assertEquals(ComplementaryVerdict.AUTO_MERGE, complementaryVerdict(none, order))
    }

    @Test
    fun `PAYMENT plus BANK is a review case and is symmetric`() {
        assertEquals(ComplementaryVerdict.REVIEW, complementaryVerdict(payment, bank))
        assertEquals(ComplementaryVerdict.REVIEW, complementaryVerdict(bank, payment))
    }

    /**
     * 必修⑤：**同一条银行通道被重复抓取**（目录里 `BANK` 只有唯一 ID `bank`）⇒ 由 REJECT 改判 REVIEW。
     *
     * 依据（团队拍板）：同一 `bank` 通道 + 跨 source + 同金额 + 短窗 ⇒ 大概率是银行两个渠道都推了同一笔，
     * 但"同金额的两笔真实银行扣款"也不能排除 ⇒ 保守**浮出候选交用户裁决**，而不是静默双记。
     * ⚠️ 这一格是**必修⑤ 的红证据**：落地前 `complementaryVerdict(bank, bank) == REJECT`，本用例会红。
     */
    @Test
    fun `the single bank channel captured twice is REVIEW, not REJECT (must-fix 5)`() {
        assertEquals(
            ComplementaryVerdict.REVIEW,
            complementaryVerdict(bank, bank),
            "同一 bank 通道被两个采集来源抓到 ⇒ REVIEW（交用户），既不得静默合并、也不得静默双记",
        )
    }

    @Test
    fun `same-tier pairs with different channels are rejected`() {
        assertEquals(ComplementaryVerdict.REJECT, complementaryVerdict(order, "taobao"), "两个下单场所 = 两笔")
        assertEquals(ComplementaryVerdict.REJECT, complementaryVerdict("taobao", order))
        assertEquals(ComplementaryVerdict.REJECT, complementaryVerdict(payment, "alipay"), "两个支付通道 = 两笔")
        assertEquals(ComplementaryVerdict.REJECT, complementaryVerdict("alipay", payment))
        assertEquals(ComplementaryVerdict.REJECT, complementaryVerdict(none, none), "双方都无层级信息")
    }

    @Test
    fun `NONE against PAYMENT or BANK is conservatively rejected`() {
        // unknown 那条可能"什么都没识别出来"，没有互补证据 ⇒ 连候选都不是。
        assertEquals(ComplementaryVerdict.REJECT, complementaryVerdict(none, payment))
        assertEquals(ComplementaryVerdict.REJECT, complementaryVerdict(payment, none))
        assertEquals(ComplementaryVerdict.REJECT, complementaryVerdict(none, bank))
        assertEquals(ComplementaryVerdict.REJECT, complementaryVerdict(bank, none))
    }

    /**
     * 穷举 16 格 + 对称性：把"哪一格是 AUTO_MERGE"写成**显式期望表**，
     * 实现若悄悄放宽/收紧任意一格，这里会精确指出是哪一对平台。
     */
    @Test
    fun `full 4-by-4 matrix matches the frozen expectation table and is symmetric`() {
        val tiers = listOf(order, payment, bank, none)
        // 期望表：AUTO_MERGE / REVIEW / REJECT，按 (行, 列) 层级顺序 ORDER=0, PAYMENT=1, BANK=2, NONE=3
        // 注意 [2][2]（BANK↔BANK）为 REVIEW —— 必修⑤ 的改判点（原 REJECT）。
        val expected = arrayOf(
            arrayOf(ComplementaryVerdict.REJECT, ComplementaryVerdict.AUTO_MERGE, ComplementaryVerdict.AUTO_MERGE, ComplementaryVerdict.AUTO_MERGE),
            arrayOf(ComplementaryVerdict.AUTO_MERGE, ComplementaryVerdict.REJECT, ComplementaryVerdict.REVIEW, ComplementaryVerdict.REJECT),
            arrayOf(ComplementaryVerdict.AUTO_MERGE, ComplementaryVerdict.REVIEW, ComplementaryVerdict.REVIEW, ComplementaryVerdict.REJECT),
            arrayOf(ComplementaryVerdict.AUTO_MERGE, ComplementaryVerdict.REJECT, ComplementaryVerdict.REJECT, ComplementaryVerdict.REJECT),
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
}
