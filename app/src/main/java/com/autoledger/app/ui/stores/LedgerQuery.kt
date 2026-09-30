package com.autoledger.app.ui.stores

import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.platform.PlatformCatalog

/**
 * 账单页搜索匹配（纯函数，便于 JVM 单测）。
 *
 * 搜索范围：**商户名 / 消费平台展示名 / 备注 / 金额**。
 *
 * 为什么平台匹配的是**展示名**而不是内部 ID：用户在列表上看到的是「微信」「支付宝」，
 * 内部存的是 `wechat` / `alipay`。搜 ID 是给机器看的习惯，搜「微信」才是人的习惯，
 * 因此这里匹配 [PlatformCatalog.displayNameOf] 的结果（ID 本身不参与匹配）。
 *
 * 为什么**未识别 / 未收录的平台不参与文本搜索**：这两类的展示名都含「未知」，
 * 一旦纳入文本匹配，输入「平台」「未知」甚至「知」都会把全部未识别流水捞出来，
 * 搜索框就从"找东西"变成"造噪声"。筛这一类有专门的 FilterChip（点一下即筛），不走文本。
 *
 * 金额按**元**的字符串匹配（-1350 → "13.5"），与列表上展示的数字形态一致：
 * 用户照着看到的数字输入就能命中，不需要知道内部存的是分。
 *
 * @param query 用户输入；空白表示「不过滤」（与 [LedgerStore.visibleItems] 的 isNotBlank 判定一致）
 */
internal fun matchesQuery(txn: LedgerTransaction, query: String): Boolean {
    val q = query.trim()
    if (q.isBlank()) return true
    if (txn.counterparty.contains(q, ignoreCase = true)) return true
    if (txn.note?.contains(q, ignoreCase = true) == true) return true
    val platform = PlatformCatalog.find(txn.platformId)
    if (platform != null && platform.id != PlatformCatalog.UNKNOWN_ID &&
        platform.displayName.contains(q, ignoreCase = true)
    ) return true
    return amountYuanText(txn.amountMinor).contains(q)
}

/**
 * 金额的「元」文本，与列表展示保持一致：-1350 → "13.5"，1000 → "10.0"。
 *
 * 刻意保留原有实现（Double.toString）：搜索是子串匹配，形态必须与用户看到的数字逐字相同，
 * 换成别的格式化方式会让"看着像却搜不到"。
 */
internal fun amountYuanText(amountMinor: Long): String =
    (kotlin.math.abs(amountMinor) / 100.0).toString()
