package com.autoledger.feature.capture.notify

import com.autoledger.core.model.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * QA 独立复验：本轮 `a70cf65` 给 `bank_generic_out.bodyMustContainAny` **追加触发词**
 * （`支付给 / 钱包支付 / 数字人民币支付 / 数字钱包 / 数字人民币钱包`）的**副作用**。
 *
 * ## 为什么要单独立一份（不复用工程师的用例）
 * 工程师的用例只证明「新增组合词**命中**了真实样本 [3][4]」这一半。
 * 触发词是**准入面**：一旦加宽，任何**含这些词**的文本都会进入金额提取 —— 包括
 * **营销短信 / 收入短信**。本文件专测**准入面的负作用**，即工程师用例没覆盖的那一半。
 *
 * ## 设计口径（据规则包自身的注释与既有用例）
 * `DefaultNotificationRules` 明确写着「营销词命中即整条拒绝（**宁可漏记一条，也不把营销文案记成一笔账**）」，
 * 且既有 `NotificationParserMisfireTest` 把「营销短信不得虚增一笔支出」钉为**正确行为**。
 * 因此本文件对营销类文本断言 `null`（不得入账）—— 断言的是**期望**，若失败即为可复现的假阳性。
 *
 * ## 方向口径
 * 「收到 / 转入 / 到账」类文本是**收入**。支出规则靠 `INCOME_GOVERNS_AMOUNT` 让位，
 * 而该让位条件是**顺序敏感**的（入账词必须**领先第一个金额**）。本文件构造「入账词在金额之后」
 * 的文本，检验新加宽的触发词是否会把它吞成一笔**支出**（方向反转）。
 */
class TriggerWordSideEffectAuditTest {

    private val parser = NotificationParser()

    // ============================================================ 一、新增触发词 → 营销短信假阳性

    @Test
    fun `marketing sms mentioning 数字钱包 with an amount must NOT be booked`() {
        // 「开通数字钱包，支付立减88元」——含新触发词「数字钱包」，含金额 88，但**无**任何营销拒绝词
        // （活动/立减金/红包/优惠/领取…都未出现；「立减」≠「立减金」）。
        // 期望：不得入账（假阳性会造成一笔凭空的 88 元支出）。
        val r = parser.parse("sms:inbox", "95588", "【数字人民币】开通数字钱包，支付立减88元，先到先得")
        assertNull(
            r,
            "营销短信不得被记成一笔支出；实际 ruleId=${r?.ruleId} amount=${r?.amountMinor}",
        )
    }

    @Test
    fun `marketing sms mentioning 钱包支付 with an amount must NOT be booked`() {
        val r = parser.parse("sms:inbox", "95588", "【工商银行】钱包支付新体验，首绑立减50元")
        assertNull(
            r,
            "营销短信（首绑立减）不得被记成一笔支出；实际 ruleId=${r?.ruleId} amount=${r?.amountMinor}",
        )
    }

    @Test
    fun `marketing sms mentioning 数字人民币钱包 with an amount must NOT be booked`() {
        val r = parser.parse("sms:inbox", "95588", "【工商银行】数字人民币钱包开立有礼，完成首笔支付立减20元")
        assertNull(
            r,
            "营销短信（开立有礼）不得被记成一笔支出；实际 ruleId=${r?.ruleId} amount=${r?.amountMinor}",
        )
    }

    @Test
    fun `marketing sms mentioning 支付给 with an amount must NOT be booked`() {
        // 「支付给」是最宽的三个字：只要正文明写「…支付给…」即准入。
        val r = parser.parse("sms:inbox", "95555", "【招商银行】推荐好友办卡，好友支付给您888元奖励金")
        assertNull(
            r,
            "营销短信不得被记成一笔支出；实际 ruleId=${r?.ruleId} amount=${r?.amountMinor}",
        )
    }

    // ============================================================ 二、新增触发词 → 收入被记成支出（方向反转）

    @Test
    fun `incoming transfer whose income verb trails the amount must NOT be booked as an expense`() {
        // 「收到他行汇款…对方支付给您的500元已转入」——语义是**收入**（收到/转入）。
        // 但入账词「转入」出现在金额**之后**，INCOME_GOVERNS_AMOUNT 的顺序条件不满足 ⇒ 支出规则不让位。
        // 而新触发词「支付给」命中 ⇒ 被记成 -500 的支出。期望：不得被记成流出（方向反转）。
        val r = parser.parse(
            "sms:inbox", "95533",
            "【建设银行】尾号1234账户收到他行汇款，对方支付给您的500元已转入",
        )
        assertTrue(
            r == null || (r.amountMinor ?: 0L) > 0,
            "收入文本不得被记成支出；实际 ruleId=${r?.ruleId} amount=${r?.amountMinor} dir=${r?.direction}",
        )
    }

    // ============================================================ 三、对照组：新增触发词不得破坏既有正确路径

    @Test
    fun `control - a genuine 数字钱包 payment is still booked with the right amount`() {
        // 真实样本 [3]（工行数字钱包动账通知），修复点：必须能入账且金额正确。
        val r = parser.parse(
            "com.icbc.wallet", "动账通知",
            "您尾号为4793的数字钱包支付给中电联京东共管钱包（0098）¥17.45",
        )
        assertNotNull(r, "真实钱包支付通知必须能命中规则")
        assertEquals("bank_generic_out", r.ruleId)
        assertEquals(-1_745L, r.amountMinor)
        assertEquals(Direction.OUT, r.direction)
    }

    @Test
    fun `control - salary inflow that governs its amount is still booked as income`() {
        // 入账词**领先金额**（工资5000元）⇒ 支出规则让位 ⇒ 收入规则认领。期望 +5000。
        val r = parser.parse("sms:inbox", "95555", "【工商银行】您尾号1234的账户工资入账5000元")
        assertNotNull(r, "工资入账是真实收入，不能被拒")
        assertEquals("sms_bank_in", r.ruleId)
        assertEquals(500_000L, r.amountMinor)
        assertEquals(Direction.IN, r.direction)
    }

    @Test
    fun `control - an ordinary card purchase is still booked as an expense`() {
        val r = parser.parse("sms:inbox", "95555", "【工商银行】您尾号1234信用卡消费人民币200.00元")
        assertNotNull(r)
        assertEquals("bank_generic_out", r.ruleId)
        assertEquals(-20_000L, r.amountMinor)
        assertEquals(Direction.OUT, r.direction)
    }

    // ============================================================ 四、P2：真实原文 [1]–[4] 逐条复核（独立于工程师用例）

    @Test
    fun `repro doc samples 1 to 4 each parse with the correct yuan amount`() {
        // 逐字取自 docs/repro/digital-rmb-4-notifications.md
        val s1 = parser.parse(
            "95588", "95588",
            "[工商银行]尾号9783卡10月1日11:23工商银行支出(数字人民币消费(京东))17.45元，余额1,037.60元。",
        )
        val s2 = parser.parse(
            "com.icbc", "动账通知",
            "尾号9783卡10月1日11:23工商银行支出(数字人民币消费(京东))17.45元。请点击查看详情。",
        )
        val s3 = parser.parse(
            "com.icbc.wallet", "动账通知",
            "您尾号为4793的数字钱包支付给中电联京东共管钱包（0098）¥17.45",
        )
        val s4 = parser.parse(
            "cn.gov.pboc.dcep", "付款通知",
            "您的我的钱包数字人民币钱包在京东平台支付¥17.45",
        )
        listOf(s1, s2, s3, s4).forEachIndexed { i, r ->
            assertNotNull(r, "[${i + 1}] 必须命中规则（此前 [3][4] 为未命中）")
            assertEquals("bank_generic_out", r.ruleId, "[${i + 1}] 应命中银行支出通用规则")
            assertEquals(-1_745L, r.amountMinor, "[${i + 1}] 应解析出 17.45 元")
            assertEquals(Direction.OUT, r.direction, "[${i + 1}] 应为流出")
        }
    }
}
