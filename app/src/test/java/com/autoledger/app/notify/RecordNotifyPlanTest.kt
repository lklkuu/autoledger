package com.autoledger.app.notify

import com.autoledger.app.ui.nav.Destination
import com.autoledger.core.model.TxnStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [planRecordNotify] 的逐格护栏（**纯 JVM**，不碰 Android）。
 *
 * 这些用例锁定的正是用户报障的两件事：
 * 1. **不该弹的不弹** —— `DUPLICATE` / `MERGED` / `IGNORED` 三类「没作为独立一笔入账」的流水
 *    必须返回 null，否则一次消费（多来源多条通知）会把通知栏刷成一串；
 * 2. **文案不许撒谎** —— 金额缺失时说「金额未识别」（而不是「金额待确认」），
 *    未确认时不写「已自动记账」。
 */
class RecordNotifyPlanTest {

    // ---------- 1) 不入账的流水：一律不弹 ----------

    @Test
    fun `duplicate rows never notify`() {
        // 同一次消费的第 2、3 条重复：根本没进账单，弹「已自动记账」是事实错误，也是刷屏根源。
        assertNull(
            planRecordNotify(
                amountMinor = -84351,
                counterparty = "美团外卖",
                storedStatus = TxnStatus.DUPLICATE,
                needsReview = true,
            ),
            "DUPLICATE（疑似重复、未计入账单）不得弹「记账提醒」",
        )
    }

    @Test
    fun `merged rows never notify`() {
        // 已被主记录吸收：金额由主记录承担，再弹一条等于同一笔报两次。
        assertNull(
            planRecordNotify(
                amountMinor = -84351,
                counterparty = "招商银行",
                storedStatus = TxnStatus.MERGED,
                needsReview = false,
            ),
            "MERGED（已被主记录吸收）不得弹提醒",
        )
    }

    @Test
    fun `ignored rows never notify`() {
        assertNull(
            planRecordNotify(
                amountMinor = -10000,
                counterparty = "零钱通",
                storedStatus = TxnStatus.IGNORED,
                needsReview = false,
            ),
            "IGNORED（用户/规则判定不算账）不得弹提醒",
        )
    }

    // ---------- 2) 已入账：标题如实、金额用绝对值、去账单页 ----------

    @Test
    fun `confirmed row with amount says auto recorded and opens monthly`() {
        val plan = planRecordNotify(
            amountMinor = -84351,
            counterparty = "美团外卖",
            storedStatus = TxnStatus.CONFIRMED,
            needsReview = false,
        )
        assertNotNull(plan)
        assertEquals("已自动记账", plan.title)
        assertTrue(plan.body.contains("¥843.51"), "正文必须带金额 ¥843.51，实际为 ${plan.body}")
        assertFalse(plan.body.contains("-843.51"), "正文不得出现负号")
        assertTrue(plan.body.contains("美团外卖"), "正文必须带商户，实际为 ${plan.body}")
        assertTrue(plan.body.contains("点按查看"), "正文保留既有引导语，实际为 ${plan.body}")
        assertEquals(
            Destination.MONTHLY,
            plan.openDestination,
            "已入账 ⇒ 点开去账单页核对，而不是采集箱",
        )
    }

    // ---------- 3) 待确认：标题改成「待你确认」、去采集箱 ----------

    @Test
    fun `raw row is review even when caller passes needsReview false`() {
        // RAW = 待确认但默认成立 ⇒ 用户必须去采集箱看一眼，不能报「已自动记账」。
        val plan = planRecordNotify(
            amountMinor = -2500,
            counterparty = "瑞幸咖啡",
            storedStatus = TxnStatus.RAW,
            needsReview = false,
        )
        assertNotNull(plan)
        assertEquals("记了一笔，待你确认", plan.title)
        assertEquals(Destination.CAPTURE, plan.openDestination)
    }

    @Test
    fun `needsReview true on a confirmed row still routes to capture`() {
        // 流水线认为要人看（例如 Accepted 但 autoConfirmed=false），即便库里已是 CONFIRMED，
        // 也必须按"待确认"提示 —— 否则用户不知道要去核对。
        val plan = planRecordNotify(
            amountMinor = -2500,
            counterparty = "瑞幸咖啡",
            storedStatus = TxnStatus.CONFIRMED,
            needsReview = true,
        )
        assertNotNull(plan)
        assertEquals("记了一笔，待你确认", plan.title)
        assertEquals(Destination.CAPTURE, plan.openDestination)
    }

    // ---------- 4) 金额缺失：说「未识别」，不许说「待确认」 ----------

    @Test
    fun `null amount says amount not recognized rather than pending`() {
        val plan = planRecordNotify(
            amountMinor = null,
            counterparty = "未知商户",
            storedStatus = TxnStatus.RAW,
            needsReview = true,
        )
        assertNotNull(plan)
        assertTrue(plan.body.contains("金额未识别"), "正文应为「金额未识别」，实际为 ${plan.body}")
        assertFalse(
            plan.body.contains("金额待确认"),
            "「金额待确认」把「解析失败」说成「等你确认」，会让用户只等着系统自己确认，实际为 ${plan.body}",
        )
    }

    @Test
    fun `zero amount is treated as unrecognized`() {
        // IngestPipeline 解析不到金额时落 amountMinor = 0 ⇒ 不能冒充「¥0.00」这个真实金额。
        val plan = planRecordNotify(
            amountMinor = 0L,
            counterparty = null,
            storedStatus = TxnStatus.RAW,
            needsReview = true,
        )
        assertNotNull(plan)
        assertTrue(plan.body.contains("金额未识别"), "0 元应视为未识别，实际为 ${plan.body}")
        assertFalse(plan.body.contains("¥0.00"), "不得把 0 当成真实金额，实际为 ${plan.body}")
    }

    // ---------- 5) 主体兜底：空白商户不能说成商户 ----------

    @Test
    fun `blank counterparty falls back to a generic subject`() {
        val plan = planRecordNotify(
            amountMinor = -1990,
            counterparty = "   ",
            storedStatus = TxnStatus.CONFIRMED,
            needsReview = false,
        )
        assertNotNull(plan)
        assertTrue(plan.body.contains("一笔新流水"), "空白商户应兜底为「一笔新流水」，实际为 ${plan.body}")
        assertFalse(plan.body.contains("   ·"), "兜底后不得留下空白商户段，实际为 ${plan.body}")
    }

    @Test
    fun `null counterparty falls back to a generic subject`() {
        val plan = planRecordNotify(
            amountMinor = 12345,
            counterparty = null,
            storedStatus = TxnStatus.CONFIRMED,
            needsReview = false,
        )
        assertNotNull(plan)
        assertTrue(plan.body.contains("一笔新流水"), "实际为 ${plan.body}")
        // 收入（正数）同样只报数字，不带正号。
        assertTrue(plan.body.contains("¥123.45"), "实际为 ${plan.body}")
    }

    // ---------- 6) 未知状态不构成否定证据 ----------

    @Test
    fun `unknown stored status does not suppress the notification`() {
        // 理论上不会发生（ingest 之后一定能查到），但查库失败时不该静默吞掉提醒。
        val plan = planRecordNotify(
            amountMinor = -6666,
            counterparty = "便利店",
            storedStatus = null,
            needsReview = false,
        )
        assertNotNull(plan, "查不到落库状态时应按正常入账处理，而不是抑制提醒")
        assertEquals("已自动记账", plan.title)
    }
}
