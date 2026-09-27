package com.autoledger.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 流水的「扩展属性」：目前承载标签（tags），并预留地点 / 票据等非索引维度。
 *
 * 序列化为 JSON 存入 [LedgerTransaction.extras]。纯逻辑、无 Android 依赖、可 JVM 单测；
 * 解析对 null / 空 / 损坏内容保持**宽容**（返回 [EMPTY]），不让脏数据把整条流水读崩。
 */
data class TxnExtras(
    val tags: List<String> = emptyList(),
    val location: String? = null,
    val receiptRef: String? = null,
) {
    fun encode(): String {
        val map = mutableMapOf<String, JsonElement>()
        if (tags.isNotEmpty()) map["tags"] = JsonArray(tags.map { JsonPrimitive(it) })
        location?.takeIf { it.isNotBlank() }?.let { map["location"] = JsonPrimitive(it) }
        receiptRef?.takeIf { it.isNotBlank() }?.let { map["receiptRef"] = JsonPrimitive(it) }
        return JsonObject(map).toString()
    }

    /** 生成一个只改标签、保留其它扩展属性的新实例（标签会被规范化）。 */
    fun withTags(newTags: List<String>): TxnExtras = copy(tags = newTags.normalizedTags())

    companion object {
        val EMPTY = TxnExtras()

        /** 解析；null / 空白 / 损坏 / 类型不符一律返回 [EMPTY]。 */
        fun decode(json: String?): TxnExtras {
            if (json.isNullOrBlank()) return EMPTY
            return runCatching {
                val obj = Json.parseToJsonElement(json) as? JsonObject ?: return EMPTY
                TxnExtras(
                    tags = (obj["tags"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                        .orEmpty()
                        .normalizedTags(),
                    location = (obj["location"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() },
                    receiptRef = (obj["receiptRef"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() },
                )
            }.getOrDefault(EMPTY)
        }

        /** 便捷：直接取标签列表。 */
        fun tagsOf(json: String?): List<String> = decode(json).tags
    }
}

/** 标签规范化：去首尾空白、丢弃空项、去重（保序）。 */
fun List<String>.normalizedTags(): List<String> =
    map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/** 该流水的扩展属性（解析失败返回空，不抛）。 */
val LedgerTransaction.txnExtras: TxnExtras get() = TxnExtras.decode(extras)
