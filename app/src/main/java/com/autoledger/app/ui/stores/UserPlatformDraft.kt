package com.autoledger.app.ui.stores

import com.autoledger.core.model.LedgerSchema
import com.autoledger.core.model.UserPlatform
import com.autoledger.core.model.newUserPlatformId
import com.autoledger.core.model.platform.PlatformKind

/**
 * 自定义平台编辑草稿 —— **纯 Kotlin，刻意不放在 AppStores.kt**。
 *
 * 原因很实际：`AppStores.kt` 的**类初始化**里建了 `CoroutineScope(Dispatchers.Main.immediate + ...)`，
 * 在普通 JVM 单测（没有 Android Looper）下加载那个文件会直接抛初始化异常。
 * 草稿是纯数据 + 纯校验，单独一个文件后就能被普通 JUnit 直接覆盖，不必引入 Robolectric。
 */

/**
 * 自定义平台编辑草稿：**纯数据 + 纯校验**，抽出来是为了能脱离 UI 单测。
 *
 * 关键词与包名用**多行文本**输入（一行一个），与 `UserPlatformEntity` 里 `\n` 分隔的存法一致；
 * 同时也容忍逗号分隔（用户习惯不同），任何一种都按分隔符切开。
 */
data class UserPlatformDraft(
    val id: String? = null,
    val displayName: String = "",
    val kind: PlatformKind = PlatformKind.ORDER,
    val strongKeywords: String = "",
    val mediumKeywords: String = "",
    val weakKeywords: String = "",
    val packageNames: String = "",
    /** 编辑既有条目时保留它的归档状态；新增恒为 false。 */
    val archived: Boolean = false,
) {

    /**
     * 校验：不过则返回可展示的文案，过了返回 null。
     *
     * 第二条校验（至少一个信号）是硬要求：**没有任何关键词/包名的平台永远识别不出来**，
     * 用户会以为"加了但没用"。与其让一个死条目静静地躺在库里，不如当场拦下。
     */
    fun validate(): String? = when {
        displayName.isBlank() -> "请填写平台名称"
        signals().isEmpty() -> "至少填一个关键词或通知包名，否则这个平台永远识别不出来"
        else -> null
    }

    /** 名称 + 三档关键词 + 包名，全部去空白、去空行。 */
    fun signals(): List<String> =
        lines(strongKeywords) + lines(mediumKeywords) + lines(weakKeywords) + lines(packageNames)

    /**
     * 生成领域对象。
     *
     * @param existing 编辑既有条目时传入（保留 `sortOrder` / `createdAtMillis` / `schemaVersion`）；
     *   新增传 null。**`sortOrder` 必须沿用旧值**：否则用户编辑一次名称，这个平台就会跳到列表末尾。
     * @param now 便于单测固定时间
     */
    fun toUserPlatform(existing: UserPlatform? = null, now: Long = System.currentTimeMillis()): UserPlatform =
        UserPlatform(
            // id 为空 = 新增 ⇒ 生成 `user:<UUID>`；有 id = 编辑 ⇒ 沿用（历史流水已经把它存进 platform_id，
            // **绝不能因为改名而换 ID**，否则历史流水会全部变成孤儿 ID）
            id = id?.takeIf { it.isNotBlank() } ?: newUserPlatformId(),
            displayName = displayName.trim(),
            kind = kind,
            strongKeywords = lines(strongKeywords),
            mediumKeywords = lines(mediumKeywords),
            weakKeywords = lines(weakKeywords),
            packageNames = lines(packageNames).toSet(),
            sortOrder = existing?.sortOrder ?: UserPlatform.DEFAULT_SORT_ORDER,
            archived = archived,
            createdAtMillis = existing?.createdAtMillis?.takeIf { it > 0L } ?: now,
            schemaVersion = existing?.schemaVersion ?: LedgerSchema.CURRENT,
        )

    companion object {
        /** 按换行 / 逗号（中英文）切分并清洗。 */
        fun lines(raw: String): List<String> =
            raw.split('\n', '\r', ',', '，').map { it.trim() }.filter { it.isNotEmpty() }
    }
}

/**
 * 领域对象 → 草稿（编辑入口用）。
 *
 * 放在这里而不是 UI 文件里：它是纯粹的字段搬运，且**需要被单测覆盖**
 * （「编辑停用中的平台不该把它悄悄启用」这类回归必须能直接测）。
 */
internal fun UserPlatformDraft.Companion.from(platform: UserPlatform): UserPlatformDraft = UserPlatformDraft(
    id = platform.id,
    displayName = platform.displayName,
    kind = platform.kind,
    strongKeywords = platform.strongKeywords.joinToString("\n"),
    mediumKeywords = platform.mediumKeywords.joinToString("\n"),
    weakKeywords = platform.weakKeywords.joinToString("\n"),
    packageNames = platform.packageNames.joinToString("\n"),
    archived = platform.archived,
)
