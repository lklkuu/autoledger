package com.autoledger.core.backup

import com.autoledger.core.model.Direction
import com.autoledger.core.model.LedgerSchema
import com.autoledger.core.model.LedgerTransaction
import com.autoledger.core.model.TxnStatus
import com.autoledger.core.model.TxnType
import com.autoledger.core.model.platform.PlatformCatalog
import com.autoledger.core.model.platform.PlatformSource
import org.json.JSONArray
import org.json.JSONObject

/**
 * 流水的备份 JSON 编解码（**顶层纯函数，不依赖 [BackupManager] 实例**）。
 *
 * 之所以从类内抽出来：备份字段兼容是本项目明确的产品诉求（「保证存量数据不丢失」），
 * 必须能被单测直接覆盖；而 [BackupManager] 的构造需要 Room 实例，
 * 把编解码困在类里会让测试只能去造一个完整数据库，成本高且容易"看起来测了其实没测"。
 *
 * 两个方向都是**宽容**的：
 * - 读：缺失新字段（旧 v4 档案）→ 落默认值（平台为 `unknown`），绝不抛异常；
 *        脏枚举值（远端下发 / 人工改坏）→ `runCatching` 兜底为安全值。
 * - 写：新字段一律写出，保证 v5 档案自洽。
 */

/** 流水 → 备份 JSON。 */
internal fun LedgerTransaction.toJson(): JSONObject = JSONObject().apply {
    put("id", id); put("amountMinor", amountMinor); put("currency", currency)
    put("occurredAtMillis", occurredAtMillis); put("bookedAtMillis", bookedAtMillis)
    put("type", type.name); put("direction", direction.name)
    put("counterparty", counterparty); put("note", note)
    put("platformId", platformId); put("platformConfidence", platformConfidence.toDouble())
    put("platformSource", platformSource.name)
    put("sourceId", sourceId); put("sourceRef", sourceRef)
    put("accountId", accountId); put("categoryId", categoryId)
    put("transferGroupId", transferGroupId); put("fingerprint", fingerprint)
    put("status", status.name); put("confidence", confidence)
    put("rawTextSealed", rawTextSealed); put("extras", extras); put("schemaVersion", schemaVersion)
    put("orderId", orderId); put("refundId", refundId)
    // v6：合并溯源。被吸收的记录会连同它自己的 platformId 一起备份，
    // 所以「这笔记了两次，分别来自微信和银行卡」在导入后仍然可查。
    put("mergedIntoId", mergedIntoId)
}

/** 备份 JSON → 流水列表。字段缺失 / 枚举脏值一律兜底，不抛异常。 */
internal fun JSONArray.toTransactions(): List<LedgerTransaction> = (0 until length()).map { i ->
    val o = getJSONObject(i)
    LedgerTransaction(
        id = o.getString("id"),
        amountMinor = o.getLong("amountMinor"),
        currency = o.optString("currency", "CNY"),
        occurredAtMillis = o.getLong("occurredAtMillis"),
        bookedAtMillis = o.optLong("bookedAtMillis", o.getLong("occurredAtMillis")),
        type = runCatching { TxnType.valueOf(o.getString("type")) }.getOrDefault(TxnType.EXPENSE),
        direction = runCatching { Direction.valueOf(o.optString("direction", "OUT")) }
            .getOrDefault(Direction.OUT),
        counterparty = o.optString("counterparty", ""),
        platformId = o.optString("platformId", PlatformCatalog.UNKNOWN_ID),
        platformConfidence = o.optDouble("platformConfidence", 0.0).toFloat(),
        platformSource = runCatching { PlatformSource.valueOf(o.optString("platformSource", "AUTO")) }
            .getOrDefault(PlatformSource.AUTO),
        note = o.optString("note").takeIf { it.isNotBlank() },
        sourceId = o.optString("sourceId", "manual"),
        sourceRef = o.optString("sourceRef", ""),
        accountId = o.optString("accountId").takeIf { it.isNotBlank() },
        categoryId = o.optString("categoryId").takeIf { it.isNotBlank() },
        transferGroupId = o.optString("transferGroupId").takeIf { it.isNotBlank() },
        fingerprint = o.optString("fingerprint", ""),
        status = runCatching { TxnStatus.valueOf(o.optString("status", "CONFIRMED")) }
            .getOrDefault(TxnStatus.CONFIRMED),
        confidence = o.optDouble("confidence", 1.0).toFloat(),
        rawTextSealed = o.optString("rawTextSealed").takeIf { it.isNotBlank() },
        extras = o.optString("extras").takeIf { it.isNotBlank() },
        orderId = o.optString("orderId").takeIf { it.isNotBlank() },
        refundId = o.optString("refundId").takeIf { it.isNotBlank() },
        // v5 及更早的旧档案没有这个字段 ⇒ optString 兜底为 null，**导入不报错**。
        mergedIntoId = o.optString("mergedIntoId").takeIf { it.isNotBlank() },
        schemaVersion = o.optInt("schemaVersion", LedgerSchema.CURRENT),
    )
}
