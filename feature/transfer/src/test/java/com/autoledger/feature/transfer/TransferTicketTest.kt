package com.autoledger.feature.transfer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TransferTicketTest {

    @Test
    fun `encode and decode round-trip`() {
        val ticket = TransferTicket("AutoLedger-5G", "p@ssw0rd", "abc123", 8080)
        assertEquals(ticket, TransferTicket.decode(ticket.encode()))
    }

    @Test
    fun `decode rejects malformed input`() {
        assertNull(TransferTicket.decode("a|b|c"))
        assertNull(TransferTicket.decode("a|b|c|d"))
        assertNull(TransferTicket.decode("a|b|c|99999"))
        assertNull(TransferTicket.decode(""))
        assertNull(TransferTicket.decode("a|b|c|0"))
    }
}
