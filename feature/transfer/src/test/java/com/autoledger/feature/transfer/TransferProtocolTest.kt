package com.autoledger.feature.transfer

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TransferProtocolTest {

    @Test
    fun `hello and ok round-trip`() {
        assertEquals("HELLO token-1\n", String(TransferProtocol.hello("token-1")))

        val env = TransferEnvelope("1.0.0", "d", RawTextPolicy.REWRAPPED, 3, 100, "sha")
        val ok = String(TransferProtocol.helloOk(env))
        assertEquals("OK ${env.encode()}\n", ok)
        assertEquals(env, TransferProtocol.parseOk(ok))
    }

    @Test
    fun `block message is header plus payload`() {
        val payload = "hello".toByteArray()
        val block = TransferProtocol.block(2, payload)
        val headerLen = "BLOCK 2 5\n".length
        assertContentEquals("BLOCK 2 5\n".toByteArray(), block.copyOfRange(0, headerLen))
        assertContentEquals(payload, block.copyOfRange(headerLen, block.size))
        assertEquals(2 to 5, TransferProtocol.parseBlockHeader("BLOCK 2 5"))
    }

    @Test
    fun `resend bitmap round-trip`() {
        val missing = setOf(0, 3, 7)
        val line = String(TransferProtocol.resend(missing))
        assertEquals(missing, TransferProtocol.parseResend(line))
    }

    @Test
    fun `parse rejects malformed lines`() {
        assertNull(TransferProtocol.parseOk("HELLO x"))
        assertNull(TransferProtocol.parseBlockHeader("BLOCK x 5"))
        assertNull(TransferProtocol.parseBlockHeader("BLOCK 2 -1"))
        assertNull(TransferProtocol.parseResend("RESEND !!!"))
    }
}
