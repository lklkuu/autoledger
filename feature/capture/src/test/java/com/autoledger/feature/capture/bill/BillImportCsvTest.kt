package com.autoledger.feature.capture.bill

import com.autoledger.core.model.TxnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「支付宝 / 微信账单 CSV 导入」的纯 JVM 护栏（T9）。
 *
 * 覆盖本次修复的退款方向缺陷：支付宝"收/支"列的退款行取值为 **不计收支**，
 * `contains("支")` 会把它误判成支出、金额取负，退款被记成负支出；非退款的"不计收支"行
 * （提现 / 还款 / 余额宝划转）同样被虚增成支出。修复后：退款 → REFUND(正)、不计收支 → TRANSFER(正)。
 */
class BillImportCsvTest {

    private val source = BillImportCaptureSource()

    // 含多行前言 + 8 列表头的支付宝样例。
    private val csv = """
        支付宝交易记录明细查询
        账号:demo@example.com
        起始日期:[2024-01-01 00:00:00]    终止日期:[2024-01-31 23:59:59]
        ---------------------------------交易记录明细列表---------------------------------
        交易时间,交易对方,商品说明,收/支,金额(元),交易状态,收/付款方式,交易订单号
        2024/1/2 13:45,星巴克,咖啡,支出,25.00,交易成功,余额,20240102001
        2024/1/3 09:10,星巴克,咖啡退款,不计收支,25.00,退款成功,余额,20240103002
        2024/1/4 10:00,本人,提现到银行卡,不计收支,100.00,交易成功,余额,20240104003
        2024/1/5 11:00,某商户,订单关闭,支出,50.00,交易关闭,余额,20240105004
    """.trimIndent()

    private fun envelopes() = source.parse(csv.byteInputStream())

    @Test
    fun `header is sniffed after a multi-line preamble`() {
        // 4 条数据行里，"交易关闭" 的行被 REJECT_STATUS 丢弃 → 剩 3 条。
        assertEquals(3, envelopes().size, "应在多行前言后定位表头，并保留 3 条有效行")
    }

    @Test
    fun `expense row is negative and has no explicit type`() {
        val e = envelopes().first { it.sourceRef.contains("20240102001") }
        assertEquals(-2_500L, e.amountHint, "支出行应为负")
        assertNull(e.explicitType, "普通支出不得下发类型")
    }

    @Test
    fun `refund row is positive and explicitly typed as refund`() {
        val e = envelopes().first { it.sourceRef.contains("20240103002") }
        assertEquals(2_500L, e.amountHint, "退款应记为正（资金流入），不得因'不计收支'被判成负支出")
        assertEquals(TxnType.REFUND, e.explicitType, "退款行必须显式标注 REFUND")
    }

    @Test
    fun `neutral row is typed as transfer`() {
        val e = envelopes().first { it.sourceRef.contains("20240104003") }
        assertEquals(10_000L, e.amountHint, "不计收支的行不得被判成负支出")
        assertEquals(TxnType.TRANSFER, e.explicitType, "不计收支（提现等）应按内部划转处理")
    }

    @Test
    fun `closed row is dropped`() {
        assertTrue(
            envelopes().none { it.sourceRef.contains("20240105004") },
            "交易关闭的行应被 REJECT_STATUS 丢弃",
        )
    }
}
