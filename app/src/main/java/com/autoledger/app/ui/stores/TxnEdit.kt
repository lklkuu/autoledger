package com.autoledger.app.ui.stores

import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.platform.PlatformSource

/**
 * 把「商户名 / 备注 / 消费平台」编辑写回一笔流水（纯函数，便于 JVM 单测）。
 *
 * - 商户名去首尾空白；备注去首尾空白，纯空白归一为 `null`；
 * - **消费平台：用户改过就记为权威值**（`platformSource = USER`、`platformConfidence = 1f`）。
 *   之后任何自动流程（重解析、合并继承、再次 ingest）都不得改写 —— 用户意图优先于自动识别。
 *   未改动时保持原值与原来源。
 * - **重算去重指纹**：商户名参与指纹（见 `LedgerDuplicateResolver.fingerprintOf`）。
 *   自动抓取的流水商户名常缺失（指纹退化为 `金额|blank|来源`），用户补上后必须重算，
 *   否则它与其它渠道的同笔记录对不上，跨渠道去重会失效。
 *
 * 注意：**消费平台不参与指纹**（见 `LedgerDuplicateResolver` 的注释）——
 * 同一笔消费的「通道通知」与「银行短信」平台可能不同（美团 vs 未知），
 * 平台一旦进指纹，一笔消费就会被拆成两条。故改平台**不会**改变指纹，这是刻意设计。
 *
 * @param fingerprintOf 注入的指纹算法，生产环境传 `duplicateResolver::fingerprintOf`
 */
internal fun applyTxnEdit(
    txn: LedgerTransaction,
    counterparty: String,
    note: String?,
    platformId: String = txn.platformId,
    fingerprintOf: (LedgerTransaction) -> String,
): LedgerTransaction {
    val userChangedPlatform = platformId != txn.platformId
    val edited = txn.copy(
        counterparty = counterparty.trim(),
        note = note?.trim()?.ifBlank { null },
        platformId = platformId,
        platformConfidence = if (userChangedPlatform) 1f else txn.platformConfidence,
        platformSource = if (userChangedPlatform) PlatformSource.USER else txn.platformSource,
    )
    return edited.copy(fingerprint = fingerprintOf(edited))
}
