package com.autoledger.feature.transfer

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TransferCodecTest {

    @Test
    fun `chunk and assemble round-trip`() {
        val data = ByteArray(TransferCodec.BLOCK_SIZE * 2 + 123) { (it % 251).toByte() }
        val blocks = TransferCodec.chunk(data)
        assertEquals(3, blocks.size)
        assertContentEquals(data, TransferCodec.assemble(blocks))
    }

    @Test
    fun `empty data produces no blocks`() {
        assertEquals(emptyList(), TransferCodec.chunk(ByteArray(0)))
        assertEquals(0, TransferCodec.assemble(emptyList()).size)
    }

    @Test
    fun `sha256 is stable and distinct`() {
        val a = "hello".toByteArray()
        val b = "world".toByteArray()
        assertEquals(TransferCodec.sha256Hex(a), TransferCodec.sha256Hex(a))
        assertNotEquals(TransferCodec.sha256Hex(a), TransferCodec.sha256Hex(b))
        assertEquals(64, TransferCodec.sha256Hex(a).length)
    }

    @Test
    fun `bitmap round-trip`() {
        val received = setOf(0, 2, 5, 100)
        val bytes = TransferCodec.encodeBitmap(received)
        assertEquals(received, TransferCodec.decodeBitmap(bytes))
    }

    @Test
    fun `empty bitmap round-trip`() {
        val bytes = TransferCodec.encodeBitmap(emptySet())
        assertEquals(emptySet(), TransferCodec.decodeBitmap(bytes))
    }
}
