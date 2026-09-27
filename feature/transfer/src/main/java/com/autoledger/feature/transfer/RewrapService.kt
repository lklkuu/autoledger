package com.autoledger.feature.transfer

import com.autoledger.core.crypto.CryptoBox
import com.autoledger.core.model.LedgerTransaction
import java.util.Base64

/**
 * rawTextSealed 的重加密（换机迁移的关键难点）。
 *
 * `rawTextSealed` 用「设备本地 Keystore 主密钥」加密（见 `AppContainer.cryptoBox`），
 * 换机后新机的主密钥不同、解不开原文。因此迁移时：
 *   导出侧 unwrap：密文 → 明文（明文随迁移包走，迁移包整体再用会话密钥加密传输）；
 *   导入侧 rewrap：明文 → 用新机主密钥重新加密。
 *
 * 注意：这里刻意**不用** `CryptoBox.sealString/openString`——它们依赖 `android.util.Base64`，
 * 无法纯 JVM 单测；改用 `seal/open`（纯 `javax.crypto`）+ `java.util.Base64`，产物与
 * `sealString` 的 NO_WRAP base64 完全兼容。
 */
class RewrapService(private val cryptoBox: CryptoBox) {

    /**
     * 导出侧：rawTextSealed（密文）→ 明文。
     * 无原文则原样返回；解密失败抛异常（GCM 认证失败 / 密文损坏），由上层按「DROP 策略」降级。
     */
    fun unwrap(txn: LedgerTransaction): LedgerTransaction {
        val sealed = txn.rawTextSealed ?: return txn
        val bytes = Base64.getDecoder().decode(sealed)
        val plain = cryptoBox.open(bytes)
        return txn.copy(rawTextSealed = String(plain, Charsets.UTF_8))
    }

    /** 导入侧：rawTextSealed（明文）→ 用新机主密钥重新加密。 */
    fun rewrap(txn: LedgerTransaction): LedgerTransaction {
        val plain = txn.rawTextSealed ?: return txn
        val sealed = cryptoBox.seal(plain.toByteArray(Charsets.UTF_8))
        return txn.copy(rawTextSealed = Base64.getEncoder().encodeToString(sealed))
    }
}
