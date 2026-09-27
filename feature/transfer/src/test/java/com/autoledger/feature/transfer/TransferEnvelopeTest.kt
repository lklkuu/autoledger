package com.autoledger.feature.transfer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TransferEnvelopeTest {

    @Test
    fun `round-trip`() {
        val e = TransferEnvelope("1.0.0", "Redmi K40", RawTextPolicy.REWRAPPED, 10, 123456L, "abc123")
        assertEquals(e, TransferEnvelope.decode(e.encode()))
    }

    @Test
    fun `reject malformed input`() {
        assertNull(TransferEnvelope.decode("a|b|c|d|e"))                 // 缺字段
        assertNull(TransferEnvelope.decode("a|b|BAD|1|2|3"))            // 非法枚举
        assertNull(TransferEnvelope.decode("a|b|REWRAPPED|x|2|3"))      // 非法 blockCount
        assertNull(TransferEnvelope.decode("a|b|REWRAPPED|-1|2|3"))     // 负数
        assertNull(TransferEnvelope.decode(""))
    }
}
