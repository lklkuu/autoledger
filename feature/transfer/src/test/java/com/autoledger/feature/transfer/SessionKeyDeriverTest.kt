package com.autoledger.feature.transfer

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SessionKeyDeriverTest {

    @Test
    fun `derive produces a 256-bit key deterministically`() {
        val salt = ByteArray(16) { it.toByte() }
        val k1 = SessionKeyDeriver.derive("token-abc", salt)
        val k2 = SessionKeyDeriver.derive("token-abc", salt)
        assertEquals(32, k1.encoded.size)
        assertContentEquals(k1.encoded, k2.encoded)
    }

    @Test
    fun `different tokens derive different keys`() {
        val salt = ByteArray(16) { it.toByte() }
        val k1 = SessionKeyDeriver.derive("token-a", salt)
        val k2 = SessionKeyDeriver.derive("token-b", salt)
        assertFalse(k1.encoded.contentEquals(k2.encoded))
    }

    @Test
    fun `different salts derive different keys`() {
        val s1 = ByteArray(16) { 1 }
        val s2 = ByteArray(16) { 2 }
        val k1 = SessionKeyDeriver.derive("token", s1)
        val k2 = SessionKeyDeriver.derive("token", s2)
        assertFalse(k1.encoded.contentEquals(k2.encoded))
    }
}
