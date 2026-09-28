package com.autoledger.app.ui.stores

import com.autoledger.core.model.LedgerTransaction

/**
 * 把「商户名 / 备注」编辑写回一笔流水（纯函数，便于 JVM 单测）。
 *
 * - 商户名去首尾空白；备注去首尾空白，纯空白归一为 `null`；
 * - **重算去重指纹**：商户名参与指纹（见 `LedgerDuplicateResolver.fingerprintOf`）。
 *   此前自动抓取的流水商户名常缺失（指纹退化为 `金额|blank|来源`），一旦用户补上商户名，
 *   必须重算指纹，否则它与其它渠道的同笔记录对不上，跨渠道去重会失效。
 *
 * @param fingerprintOf 注入的指纹算法，生产环境传 `duplicateResolver::fingerprintOf`
 */
internal fun applyTxnEdit(
    txn: LedgerTransaction,
    counterparty: String,
    note: String?,
    fingerprintOf: (LedgerTransaction) -> String,
): LedgerTransaction {
    val edited = txn.copy(
        counterparty = counterparty.trim(),
        note = note?.trim()?.ifBlank { null },
    )
    return edited.copy(fingerprint = fingerprintOf(edited))
}
