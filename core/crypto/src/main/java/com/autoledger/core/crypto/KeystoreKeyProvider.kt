package com.autoledger.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore 中的主密钥（工程要求 3：密钥单独保护）。
 *
 * 主密钥由系统 TEE/StrongBox 持有，**任何情况下都不能被应用读出私钥材料**，
 * 只能用它做加解密运算。数据库口令、导出密钥、原始通知文本都由它派生/包裹。
 */
class KeystoreKeyProvider {

    fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        ks.getKey(MASTER_ALIAS, null)?.let { return it as SecretKey }
        return generate()
    }

    private fun generate(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            MASTER_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .setRandomizedEncryptionRequired(true)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val MASTER_ALIAS = "autoledger_master_key_v1"
        const val GCM_TAG_BITS = 128
        const val IV_BYTES = 12

        fun gcmSpec(iv: ByteArray) = GCMParameterSpec(GCM_TAG_BITS, iv)
    }
}
