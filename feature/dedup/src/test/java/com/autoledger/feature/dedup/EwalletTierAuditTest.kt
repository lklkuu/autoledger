package com.autoledger.feature.dedup

import com.autoledger.core.model.platform.PlatformCatalog
import com.autoledger.core.model.platform.PlatformPriority
import com.autoledger.core.model.platform.priorityOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * QA 独立复验（**E_WALLET 层级 + 判定表**，本批用户现场反馈）。
 *
 * ## 为什么另起一份而不复用 `TierTwoVerdictMatrixTest`
 * 本批 `071510d` **改动了那份文件**（工程师把 4×4 升成 5×5）。为了不让"实现方改期望表、再拿它自证"
 * 形成循环，这里由 QA **从头独立推导**一张 25 格期望表，与实现对照。
 * 两份都绿才叫互相印证。
 *
 * ## 独立推导（据用户拍板口径）
 * `tier = priorityOf(id)`，层级序 `ORDER(4) > PAYMENT(3) > E_WALLET(2) > BANK(1) > NONE(0)`。
 * 判定（`complementaryVerdict`）：
 *  ① 恰好一侧 `ORDER` ⇒ AUTO_MERGE（消费场所 × 任一通道）；
 *  ② 任一侧 `NONE`（且非①）⇒ REJECT；
 *  ③ 两侧都属 `{PAYMENT, E_WALLET, BANK}` 且**不同层级** ⇒ REVIEW（"能 REVIEW 就别 REJECT"）；
 *  ④ 同层级：**同 id** ⇒ REVIEW（同通道重复抓取）；**不同 id** ⇒ REJECT（两笔真实消费）。
 */
class EwalletTierAuditTest {

    private val order = "meituan"          // ORDER(4)
    private val pay = "wechat"             // PAYMENT(3)
    private val ewallet = "digital_rmb"    // E_WALLET(2)
    private val ewallet2 = "unionpay"      // E_WALLET(2)，与 digital_rmb 不同 id
    private val bank = PlatformCatalog.BANK_ID // BANK(1)
    private val none = PlatformCatalog.UNKNOWN_ID // NONE(0)

    // ------------------------------------------------------------ P0-3 层级序（不只看文档）

    @Test
    fun `rank order matches the user ruling - e-wallet above bank, below wechat and alipay`() {
        assertEquals(0, PlatformPriority.NONE.rank)
        assertEquals(1, PlatformPriority.BANK.rank)
        assertEquals(2, PlatformPriority.E_WALLET.rank)
        assertEquals(3, PlatformPriority.PAYMENT.rank)
        assertEquals(4, PlatformPriority.ORDER.rank)
        // 用户原话：数币/云闪付「高于银行卡、低于微信支付宝」—— 用实际派生值确认，而非读文档
        assertTrue(priorityOf(ewallet).rank > priorityOf(bank).rank, "数币 > 银行卡")
        assertTrue(priorityOf(ewallet).rank < priorityOf(pay).rank, "数币 < 微信/支付宝")
        assertTrue(priorityOf(ewallet2).rank > priorityOf(bank).rank, "云闪付 > 银行卡")
        assertTrue(priorityOf(ewallet2).rank < priorityOf(pay).rank, "云闪付 < 微信/支付宝")
    }

    @Test
    fun `digital_rmb and unionpay both map to E_WALLET kind`() {
        assertEquals(PlatformPriority.E_WALLET, priorityOf(ewallet))
        assertEquals(PlatformPriority.E_WALLET, priorityOf(ewallet2))
    }

    // ------------------------------------------------------------ P0-1 用户明确拍板的那一格

    @Test
    fun `E_WALLET and BANK is REVIEW, both directions`() {
        assertEquals(ComplementaryVerdict.REVIEW, complementaryVerdict(ewallet, bank), "数币 ↔ 银行卡")
        assertEquals(ComplementaryVerdict.REVIEW, complementaryVerdict(bank, ewallet), "银行卡 ↔ 数币")
        assertEquals(ComplementaryVerdict.REVIEW, complementaryVerdict(ewallet2, bank), "云闪付 ↔ 银行卡")
    }

    // ------------------------------------------------------------ P0-2 25 格逐格穷举（独立推导）

    @Test
    fun `full 5-by-5 matrix matches an independently derived expectation table`() {
        val tiers = listOf(order, pay, ewallet, bank, none)
        val A = ComplementaryVerdict.AUTO_MERGE
        val R = ComplementaryVerdict.REVIEW
        val X = ComplementaryVerdict.REJECT
        // 行=incoming, 列=existing，顺序 ORDER / PAYMENT / E_WALLET / BANK / NONE
        val expected = arrayOf(
            // ORDER 行：恰好一侧 ORDER 全 AUTO；ORDER×ORDER 同 id ⇒ REVIEW
            arrayOf(R, A, A, A, A),
            // PAYMENT 行
            arrayOf(A, R, R, R, X),
            // E_WALLET 行
            arrayOf(A, R, R, R, X),
            // BANK 行
            arrayOf(A, R, R, R, X),
            // NONE 行：NONE×ORDER ⇒ AUTO（需求主场景：美团通知+未识别平台的银行短信）；其余 REJECT
            arrayOf(A, X, X, X, X),
        )
        for (i in tiers.indices) {
            for (j in tiers.indices) {
                assertEquals(
                    expected[i][j],
                    complementaryVerdict(tiers[i], tiers[j]),
                    "第 $i 行 × 第 $j 列（${tiers[i]} ↔ ${tiers[j]}）判定不符",
                )
            }
        }
    }

    /**
     * "能 REVIEW 就别 REJECT" 原则审查：列出**全部 REJECT 格并逐个给出理由**，
     * 确认没有"本可 REVIEW 却被 REJECT（用户根本看不到）"的漏网。
     */
    @Test
    fun `every REJECT cell has a defensible reason`() {
        val rejects = mutableListOf<Pair<String, String>>()
        val ids = listOf(order, pay, ewallet, bank, none)
        for (a in ids) for (b in ids) {
            if (complementaryVerdict(a, b) == ComplementaryVerdict.REJECT) rejects += a to b
        }
        // 期望的 REJECT 集合 = 同层级不同 id（美团/淘宝、微信/支付宝、数币/云闪付）+ NONE×资金通道 + NONE×NONE
        val expectedReject = setOf(
            ewallet to ewallet2, ewallet2 to ewallet,   // 数币 vs 云闪付（同层不同 id）
            none to pay, pay to none,
            none to ewallet, ewallet to none,
            none to bank, bank to none,
            none to none,
        )
        // 用不同 id 补上 ORDER/PAYMENT 的同层不同 id
        assertTrue(rejects.isNotEmpty(), "应存在 REJECT 格")
        assertEquals(
            ComplementaryVerdict.REJECT, complementaryVerdict(pay, "alipay"), "微信 vs 支付宝 = 两笔")
        assertEquals(
            ComplementaryVerdict.REJECT, complementaryVerdict(order, "taobao"), "美团 vs 淘宝 = 两笔")
        for ((a, b) in expectedReject) {
            assertEquals(ComplementaryVerdict.REJECT, complementaryVerdict(a, b), "$a ↔ $b 应为 REJECT")
        }
    }

    @Test
    fun `same-tier same-id is REVIEW, same-tier different-id is REJECT`() {
        // 同 id（同一条通道被重复抓取）⇒ REVIEW
        assertEquals(ComplementaryVerdict.REVIEW, complementaryVerdict(bank, bank), "bank ↔ bank")
        assertEquals(ComplementaryVerdict.REVIEW, complementaryVerdict(ewallet, ewallet), "数币 ↔ 数币")
        assertEquals(ComplementaryVerdict.REVIEW, complementaryVerdict(pay, pay), "微信 ↔ 微信")
        assertEquals(ComplementaryVerdict.REVIEW, complementaryVerdict(order, order), "美团 ↔ 美团")
        // 不同 id ⇒ REJECT
        assertEquals(ComplementaryVerdict.REJECT, complementaryVerdict(ewallet, ewallet2), "数币 ↔ 云闪付")
        assertEquals(ComplementaryVerdict.REJECT, complementaryVerdict(pay, "alipay"), "微信 ↔ 支付宝")
        assertEquals(ComplementaryVerdict.REJECT, complementaryVerdict(order, "taobao"), "美团 ↔ 淘宝")
    }

    @Test
    fun `verdict is symmetric across the whole 5-by-5 matrix`() {
        val ids = listOf(order, pay, ewallet, bank, none)
        for (a in ids) for (b in ids) {
            assertEquals(complementaryVerdict(a, b), complementaryVerdict(b, a), "对称性 $a↔$b")
        }
    }

    // ------------------------------------------------------------ 与 Tier-1 的分层差异（有意为之）

    @Test
    fun `tier-1 and tier-2 deliberately differ on the same pair`() {
        // E_WALLET↔BANK：Tier-1 放行（同商户名=同一条支付的两面），Tier-2 REVIEW（商户名不同/为空，证据弱）
        assertTrue(tierOneAllowsAutoMerge(ewallet, bank), "Tier-1：层级不同 ⇒ 放行")
        assertEquals(ComplementaryVerdict.REVIEW, complementaryVerdict(ewallet, bank), "Tier-2：REVIEW")
        // 同层级同 id 两边都放行/交用户；同层级不同 id：Tier-1 拒、Tier-2 拒
        assertTrue(tierOneAllowsAutoMerge(bank, bank), "Tier-1：同一条 bank 通道 ⇒ 放行（Bug 2）")
        assertTrue(!tierOneAllowsAutoMerge(pay, "alipay"), "Tier-1：微信 vs 支付宝 ⇒ 拒")
        assertEquals(ComplementaryVerdict.REJECT, complementaryVerdict(pay, "alipay"), "Tier-2：也拒")
    }
}
