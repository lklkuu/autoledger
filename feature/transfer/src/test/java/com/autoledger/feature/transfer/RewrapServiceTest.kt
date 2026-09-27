package com.autoledger.feature.transfer

import com.autoledger.core.crypto.CryptoBox
import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnType
import java.util.Base64
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class RewrapServiceTest {

    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val cryptoBox = CryptoBox(key)
    private val service = RewrapService(cryptoBox)

    private fun txn(rawTextSealed: String?) = LedgerTransaction(
        id = "t1",
        amountMinor = -1230,
        occurredAtMillis = 1L,
        type = TxnType.EXPENSE,
        sourceId = "notify",
        sourceRef = "k1",
        rawTextSealed = rawTextSealed,
    )

    @Test
    fun `unwrap then rewrap round-trips`() {
        val plain = "星巴克 退款到账 ¥12.30"
        // 模拟入库时的密文：seal(ByteArray) + java.util.Base64（等价 sealString 的 NO_WRAP）
        val sealed = Base64.getEncoder().encodeToString(cryptoBox.seal(plain.toByteArray(Charsets.UTF_8)))

        val unwrapped = service.unwrap(txn(sealed))
        assertEquals(plain, unwrapped.rawTextSealed)

        val rewrapped = service.rewrap(unwrapped)
        assertNotEquals(plain, rewrapped.rawTextSealed)

        // 重加密后应能解回原文
        val decrypted = String(cryptoBox.open(Base64.getDecoder().decode(rewrapped.rawTextSealed!!)), Charsets.UTF_8)
        assertEquals(plain, decrypted)
    }

    @Test
    fun `no raw text is left untouched`() {
        val without = txn(null)
        assertEquals(without, service.unwrap(without))
        assertEquals(without, service.rewrap(without))
    }
}
