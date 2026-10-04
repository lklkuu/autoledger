package com.autoledger.core.crypto

import android.content.Context
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [AiKeyVault] 的防回归护栏。
 *
 * 最重要的一条是第三条：**密文损坏时必须返回 null 而不是抛异常**。
 * 这与 `PassphraseVault.passphrase()` 刻意相反 —— 库口令丢失是「数据判死刑」，
 * 必须抛错让用户决策；API Key 丢失只是「AI 不可用」，为此崩掉用户是本末倒置。
 *
 * 用真实 SharedPreferences（Robolectric）+ 注入的测试密钥（绕开 Android Keystore，
 * 它在 JVM 上不可用），验证的是**包裹/解包与失败语义**，不是 Keystore 本身。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AiKeyVaultTest {

    private lateinit var context: Context
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var vault: AiKeyVault

    /** 测试用主密钥：固定 32 字节，保证同一进程内 seal/open 配对成功。 */
    private val testKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences(AiKeyVault.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        vault = AiKeyVault(context, CryptoBox(testKey))
    }

    @Test
    fun `saved key round trips`() {
        assertTrue(vault.save("sk-test-123456"), "保存应成功")
        assertEquals("sk-test-123456", vault.load())
        assertTrue(vault.hasKey(), "保存后应报告已存密钥")
    }

    @Test
    fun `load returns null when nothing was ever stored`() {
        assertNull(vault.load(), "从未保存过时必须返回 null")
        assertFalse(vault.hasKey(), "从未保存过时不应报告有密钥")
    }

    @Test
    fun `corrupted ciphertext yields null instead of throwing`() {
        // 三种坏法都试：非 Base64、长度不足的合法 Base64、随机字节的合法 Base64。
        val corruptions = listOf(
            "!!! not base64 !!!",
            "AAAA", // 合法 Base64，但长度 < IV 长度
            java.util.Base64.getEncoder().encodeToString(ByteArray(64) { 0x41 }),
        )
        for (bad in corruptions) {
            prefs.edit().putString(AiKeyVault.KEY_WRAPPED, bad).commit()
            // 关键：绝不能抛异常。API Key 丢失只应让 AI 不可用。
            assertNull(vault.load(), "损坏密文 [$bad] 必须返回 null 而不是抛异常")
        }
    }

    @Test
    fun `clear removes the stored key`() {
        vault.save("sk-to-be-cleared")
        assertTrue(vault.hasKey())
        vault.clear()
        assertNull(vault.load(), "清除后必须读不到")
        assertFalse(vault.hasKey())
    }

    @Test
    fun `saving an empty string is equivalent to clearing`() {
        vault.save("sk-x")
        assertTrue(vault.save(""), "空串应视为清除并返回成功")
        assertNull(vault.load())
    }

    @Test
    fun `vault shares the passphrase vault prefs file so it stays out of backups`() {
        // 密钥必须与库口令同处一个已被备份规则排除的文件，否则等于把它送进云备份。
        assertEquals(PassphraseVault.PREFS_NAME, AiKeyVault.PREFS_NAME)
    }
}
