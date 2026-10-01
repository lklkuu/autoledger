package com.autoledger.core.model

import com.autoledger.core.model.platform.PlatformEntry
import com.autoledger.core.model.platform.PlatformKind
import java.util.UUID

/**
 * 用户自定义消费平台（领域类型）。
 *
 * ## 为什么是它自己的类型，而不是直接复用 `PlatformEntry`
 * - [PlatformEntry] 是**目录里的常量**（内置条目不可变），而本类型是**用户数据**：
 *   有主键、有 `archived` 软删除、有创建时间，需要落库、进备份、随用户迁移；
 * - 二者字段几乎一致，但生命周期完全不同：一个是编译期常量，一个是运行期行。
 *   用 [toPlatformEntry] 单向派生，保证「目录里能查到」与「DB 里存得住」两件事各自清晰。
 *
 * ## 软删除（`archived`）而不是物理删除
 * 历史流水的 `platformId` 指向本行。若物理删除，这些流水会变成孤儿 ID，
 * 展示塌成「未知平台」，用户会以为数据坏了（见设计文档风险 R7）。
 * 归档后：**不进识别候选**，但 [com.autoledger.core.model.platform.PlatformCatalog.displayNameOf]
 * 仍能查到正确名称。
 */
data class UserPlatform(
    /**
     * 主键。生成规则见 [newUserPlatformId]：`user:<UUID>`。
     * 前缀让来源一眼可辨，且与内置 ID（wechat / alipay / …）**不可能冲突**。
     */
    val id: String,
    /** 用户输入的名称，调用方负责去首尾空白。**重名允许**（仅提示，不阻断）。 */
    val displayName: String,
    /** 角色。默认 [PlatformKind.ORDER]：用户自定义的多半是「某个商家 / 消费平台」。 */
    val kind: PlatformKind = PlatformKind.ORDER,
    val strongKeywords: List<String> = emptyList(),
    val mediumKeywords: List<String> = emptyList(),
    val weakKeywords: List<String> = emptyList(),
    val packageNames: Set<String> = emptySet(),
    /**
     * 排序值。默认 [DEFAULT_SORT_ORDER]（1000 起），
     * 保证**内置平台永远排在自定义平台前面**。
     */
    val sortOrder: Int = DEFAULT_SORT_ORDER,
    /** 软删除标记。true = 停用：不再进识别候选，但历史流水仍显示原名。 */
    val archived: Boolean = false,
    val createdAtMillis: Long = 0L,
    /** 行级结构版本，与 [LedgerTransaction] 同约定。 */
    val schemaVersion: Int = LedgerSchema.CURRENT,
) {
    companion object {
        /**
         * 自定义平台的排序起点。
         *
         * 内置目录最大 sortOrder 是 90（云闪付），[com.autoledger.core.model.platform.PlatformCatalog.UNKNOWN]
         * 是 `Int.MAX_VALUE` ⇒ 从 1000 起既在内置之后、又在 unknown 之前。
         */
        const val DEFAULT_SORT_ORDER = 1000

        /** ID 前缀。 */
        const val ID_PREFIX = "user:"
    }
}

/**
 * 生成自定义平台 ID：`user:<UUID>`。
 *
 * 用 UUID 而不是自增 / 名称哈希：名称可改、可重名，
 * 而历史流水已经把它存进 `platform_id`，ID 必须**永不变化**。
 */
fun newUserPlatformId(): String = UserPlatform.ID_PREFIX + UUID.randomUUID().toString()

/**
 * 领域类型 → 目录条目（单向派生，供 [com.autoledger.core.model.platform.PlatformCatalog.register] 使用）。
 *
 * 归档条目**也允许**转换并注册：识别引擎只遍历候选，而展示需要它仍在目录里
 * （否则历史流水的平台名会塌成「未知平台」）。
 * 「归档的不进候选」由调用方（注入时过滤 `archived`）或识别层负责，见设计文档 §2.3。
 */
fun UserPlatform.toPlatformEntry(): PlatformEntry = PlatformEntry(
    id = id,
    displayName = displayName,
    kind = kind,
    strongKeywords = strongKeywords,
    mediumKeywords = mediumKeywords,
    weakKeywords = weakKeywords,
    packageNames = packageNames,
    sortOrder = sortOrder,
)
