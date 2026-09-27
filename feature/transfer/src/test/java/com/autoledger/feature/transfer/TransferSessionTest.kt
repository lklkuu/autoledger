package com.autoledger.feature.transfer

import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class TransferSessionTest {

    @Test
    fun `serve and receive round-trip over localhost`() {
        val payload = ByteArray(TransferCodec.BLOCK_SIZE * 2 + 1000) { (it % 250).toByte() }
        val meta = TransferMeta("1.0.0", "test-device", RawTextPolicy.REWRAPPED)

        val server = ServerSocket(0)
        var servedEnvelope: TransferEnvelope? = null
        val serverThread = thread(name = "transfer-server") {
            server.accept().use { s ->
                servedEnvelope = TransferSession(s).serve(payload, "token-1", meta)
            }
        }

        val client = Socket("127.0.0.1", server.localPort)
        val (received, envelope) = TransferSession(client).receive("token-1")

        assertContentEquals(payload, received)
        assertEquals(RawTextPolicy.REWRAPPED, envelope.rawTextPolicy)
        assertEquals(TransferCodec.sha256Hex(payload), envelope.sha256)
        assertEquals(3, envelope.blockCount)

        serverThread.join(5000)
        assertFalse(serverThread.isAlive)
        assertEquals(meta, servedEnvelope?.let { TransferMeta(it.appVersion, it.device, it.rawTextPolicy) })
        server.close()
    }

    @Test
    fun `wrong token is rejected on both sides`() {
        val server = ServerSocket(0)
        val serverThread = thread(name = "transfer-server-reject") {
            server.accept().use { s ->
                assertFailsWith<TransferException> {
                    TransferSession(s).serve(ByteArray(16), "right-token", TransferMeta("1", "d", RawTextPolicy.REWRAPPED))
                }
            }
        }

        val client = Socket("127.0.0.1", server.localPort)
        assertFailsWith<TransferException> { TransferSession(client).receive("wrong-token") }

        serverThread.join(5000)
        server.close()
    }
}
