package com.autoledger.core.crypto

import android.content.Context
import android.util.Base64

/**
 * 数据库口令保险箱。
 *
 * SQLCipher 需要一个 32 字节口令，但它不能硬编码、也不能明文写进 sp。
 * 做法：首次运行随机生成 -> 用 Keystore 主密钥 AES-GCM 包裹 -> base64 落盘。
 * 这样即使拿到 sp 文件，没有系统密钥容器也解不出口令。
 */
class PassphraseVault(
    context: Context,
    keyProvider: KeystoreKeyProvider = KeystoreKeyProvider(),
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val cryptoBox = CryptoBox(keyProvider.masterKey())

    fun passphrase(): ByteArray {
        val wrapped = prefs.getString(KEY_WRAPPED, null)
        if (wrapped != null) {
            // 口令已存在但解不开：绝不能静默重新生成一个新口令——
            // 那样新口令对不上已加密的库，等于判了现有数据死刑。必须显式抛错交给用户决策。
            try {
                return cryptoBox.open(Base64.decode(wrapped, Base64.NO_WRAP))
            } catch (e: Exception) {
                throw PassphraseCorruptionException(
                    "数据库口令已存在但无法解密（可能是系统密钥容器被重置）。" +
                        "数据未被改动，请先尝试系统层面的恢复，切勿卸载应用。",
                    e,
                )
            }
        }
        // 首次生成：用 commit() 同步落盘，保证「口令写成功」先于「建库」，进程被杀也不会出现加密库对不上口令。
        val fresh = cryptoBox.randomPassphrase()
        val sealed = Base64.encodeToString(cryptoBox.seal(fresh), Base64.NO_WRAP)
        val written = prefs.edit().putString(KEY_WRAPPED, sealed).commit()
        if (!written) {
            throw IllegalStateException("无法持久化数据库口令，拒绝在此状态下建库")
        }
        return fresh
    }

    companion object {
        const val PREFS_NAME = "autoledger_vault"
        const val KEY_WRAPPED = "db_passphrase_wrapped"
        const val KEY_PLAINTEXT = "db_plaintext_fallback"
        const val KEY_ENC_FAIL = "enc_failure_count"

        /** 连续加密失败次数。用于"失败达上限后放弃加密并明确告知"，杜绝静默降级。 */
        fun encryptionFailureCount(context: Context): Int =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_ENC_FAIL, 0)

        /** 记录一次加密失败，返回累加后的次数（commit 同步落盘）。 */
        fun recordEncryptionFailure(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val next = prefs.getInt(KEY_ENC_FAIL, 0) + 1
            prefs.edit().putInt(KEY_ENC_FAIL, next).commit()
            return next
        }

        /** 加密成功后清零失败计数。 */
        fun clearEncryptionFailures(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(KEY_ENC_FAIL).commit()
        }

        /** 是否曾经写入过包裹口令（= 可能存在已加密的库）。无需构造保险箱即可判断。 */
        fun hasStoredPassphrase(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).contains(KEY_WRAPPED)

        /** 用户/系统是否已决定降级为明文存储。 */
        fun isPlaintextFallback(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_PLAINTEXT, false)

        /** 记录降级决定（commit 同步落盘，避免下次启动又去尝试加密导致状态不一致）。 */
        fun setPlaintextFallback(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_PLAINTEXT, enabled).commit()
        }
    }
}

/** 口令保险箱损坏：数据未改动，应用应提示而非崩溃后重建。 */
class PassphraseCorruptionException(message: String, cause: Throwable) : RuntimeException(message, cause)
