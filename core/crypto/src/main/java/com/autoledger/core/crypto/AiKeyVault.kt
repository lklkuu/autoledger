package com.autoledger.core.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64

/**
 * AI 接口密钥保险箱。
 *
 * 结构照 [PassphraseVault]（同一个 SharedPreferences 文件、同样用 Keystore 主密钥 AES-GCM 包裹），
 * 但**失败语义刻意相反**：
 *
 * | | 密钥丢失时 | 为什么 |
 * |---|---|---|
 * | [PassphraseVault.passphrase] | **抛异常**，绝不静默重新生成 | 库口令丢失 ⇒ 旧数据永久解不开，等于判死刑，必须让用户决策 |
 * | [load] | **返回 null**，AI 静默回落本地规则 | API Key 丢失只是「AI 不可用」，账本与采集完全不受影响，绝不该因此崩掉用户 |
 *
 * 为什么和库口令共用 `autoledger_vault` 这个文件：它已被 `backup_rules.xml` /
 * `data_extraction_rules.xml` 排除在备份之外，正是我们要的「密钥不出设备」；
 * 而密钥与库口令同处一文件、却有不同的失败语义，是**有意的**——
 * 它们的风险等级不同，处置方式也必须不同。
 *
 * ⚠️ 存放的仍然只是**包裹后**的密文（Base64）；没有系统密钥容器，`load()` 一律返回 null。
 */
class AiKeyVault private constructor(
    private val prefs: SharedPreferences,
    private val cryptoBox: CryptoBox?,
) {

    /**
     * 生产构造：主密钥取自 Keystore。
     *
     * `masterKey()` 拿不到主密钥时（首次运行还没生成、或系统密钥容器被重置），
     * 这里**不抛异常**而是把 `cryptoBox` 置空 —— AI 因此不可用，但应用照常启动。
     */
    constructor(
        context: Context,
        keyProvider: KeystoreKeyProvider = KeystoreKeyProvider(),
    ) : this(vaultPrefs(context), openKeyBoxOrNull(keyProvider))

    /** 测试构造：直接给定 [CryptoBox]，绕开 Android Keystore。 */
    internal constructor(context: Context, cryptoBox: CryptoBox) : this(vaultPrefs(context), cryptoBox)

    /**
     * 存密钥（空串视为「清除」，与 [clear] 等价）。
     *
     * @return 是否真的落盘成功；主密钥不可用时返回 false 且不写任何东西。
     */
    fun save(apiKey: String): Boolean {
        val box = cryptoBox ?: return false
        if (apiKey.isEmpty()) {
            clear()
            return true
        }
        val sealed = runCatching { box.sealString(apiKey) }.getOrNull() ?: return false
        // commit() 同步落盘：密钥是用户明确要求保存的东西，不能只写内存就返回成功。
        return prefs.edit().putString(KEY_WRAPPED, sealed).commit()
    }

    /**
     * 读密钥。
     *
     * @return 解开后的明文；**从未保存过 / 密文损坏 / 主密钥不可用**一律返回 null
     *         （调用方据此提示「密钥已失效，请重新填写」或静默回落本地规则，**绝不抛异常**）。
     */
    fun load(): String? {
        val box = cryptoBox ?: return null
        val wrapped = prefs.getString(KEY_WRAPPED, null) ?: return null
        return runCatching { box.openString(wrapped) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** 是否已保存过密钥（不解密，只看有没有密文）。用于「未填密钥」的提示判断。 */
    fun hasKey(): Boolean = prefs.contains(KEY_WRAPPED)

    /** 清除密钥。用于设置页的「清除」按钮。 */
    fun clear() {
        prefs.edit().remove(KEY_WRAPPED).commit()
    }

    companion object {
        /** 与 [PassphraseVault] 同一个 SharedPreferences 文件（该文件已在备份规则中被排除）。 */
        const val PREFS_NAME = PassphraseVault.PREFS_NAME
        const val KEY_WRAPPED = "ai_api_key_wrapped"
    }
}

/** 取密钥所在的 SharedPreferences。与 [PassphraseVault] 同一个文件 —— 该文件已被备份规则排除。 */
private fun vaultPrefs(context: Context): SharedPreferences =
    context.getSharedPreferences(AiKeyVault.PREFS_NAME, Context.MODE_PRIVATE)

/**
 * 取主密钥并建 [CryptoBox]；**拿不到就返回 null 而不是抛异常**。
 *
 * 抽成顶层函数而不是内联在构造参数里：构造委托参数里内联 lambda 会踩到
 * Kotlin 的「构造完成前不得访问 this」限制。
 */
private fun openKeyBoxOrNull(keyProvider: KeystoreKeyProvider): CryptoBox? =
    runCatching { CryptoBox(keyProvider.masterKey()) }.getOrNull()
