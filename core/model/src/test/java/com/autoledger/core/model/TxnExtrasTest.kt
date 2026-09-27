package com.autoledger.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TxnExtras 编解码 —— 纯 JVM 单测（extras JSON 读写正确性）。 */
class TxnExtrasTest {

    @Test
    fun `encode then decode round trips tags`() {
        val extras = TxnExtras(tags = listOf("报销", "出差"))
        val decoded = TxnExtras.decode(extras.encode())
        assertEquals(listOf("报销", "出差"), decoded.tags)
    }

    @Test
    fun `decode of null or blank returns empty`() {
        assertEquals(TxnExtras.EMPTY, TxnExtras.decode(null))
        assertEquals(TxnExtras.EMPTY, TxnExtras.decode(""))
        assertEquals(TxnExtras.EMPTY, TxnExtras.decode("   "))
    }

    @Test
    fun `decode tolerates malformed json`() {
        assertEquals(TxnExtras.EMPTY, TxnExtras.decode("{ not json"))
        assertEquals(TxnExtras.EMPTY, TxnExtras.decode("[1,2,3]"))
        assertEquals(TxnExtras.EMPTY, TxnExtras.decode("\"a string\""))
    }

    @Test
    fun `decode tolerates wrong typed tags`() {
        // tags 不是数组 → 视为无标签
        assertEquals(TxnExtras.EMPTY, TxnExtras.decode("""{"tags":"报销"}"""))
        // 数组里夹带非字符串项（数字 / null）→ 只保留字符串项
        assertEquals(listOf("报销"), TxnExtras.decode("""{"tags":["报销",123,null]}""").tags)
    }

    @Test
    fun `round trips location and receipt ref`() {
        val decoded = TxnExtras.decode(TxnExtras(location = "南京西路", receiptRef = "IMG_001").encode())
        assertEquals("南京西路", decoded.location)
        assertEquals("IMG_001", decoded.receiptRef)
    }

    @Test
    fun `blank optional fields are dropped from json`() {
        assertEquals("{}", TxnExtras(location = " ", receiptRef = "").encode())
        assertEquals(TxnExtras.EMPTY, TxnExtras.decode(TxnExtras(location = " ").encode()))
    }

    @Test
    fun `tags are normalized on withTags`() {
        val out = TxnExtras.EMPTY.withTags(listOf("  出差 ", "", "报销", "出差")).tags
        assertEquals(listOf("出差", "报销"), out)
    }

    @Test
    fun `tagsOf reads tags directly from json`() {
        assertEquals(listOf("报销"), TxnExtras.tagsOf("""{"tags":["报销"]}"""))
        assertTrue(TxnExtras.tagsOf(null).isEmpty())
    }

    @Test
    fun `txnExtras extension decodes transaction extras`() {
        val txn = LedgerTransaction(
            id = "t1", amountMinor = -100, occurredAtMillis = 0L, type = TxnType.EXPENSE,
            sourceId = "manual", sourceRef = "r", extras = """{"tags":["报销"]}""",
        )
        assertEquals(listOf("报销"), txn.txnExtras.tags)
        assertNull(txn.copy(extras = null).txnExtras.tags.firstOrNull())
    }
}
