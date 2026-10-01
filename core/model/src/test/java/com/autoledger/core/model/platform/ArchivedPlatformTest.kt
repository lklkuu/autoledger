package com.autoledger.core.model.platform

import com.autoledger.core.model.UserPlatform
import com.autoledger.core.model.newUserPlatformId
import com.autoledger.core.model.toPlatformEntry
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * **自定义平台归档语义（R7）**的护栏。
 *
 * 停用（软删除）最容易漏的一条：停用后**历史流水仍要能显示该平台的名称**。
 * 若把归档条目从目录里彻底摘掉，那些流水的 `platformId` 就变成孤儿 ID，
 * 展示塌成「未知平台」——用户看到自己记过的平台没了，只会以为数据坏了。
 *
 * 因此这里锁死两条相反的语义：**展示必须查得到，识别与指派必须查不到**。
 */
class ArchivedPlatformTest {

    private val archivedJd = UserPlatform(
        id = "user:jd", displayName = "京东", kind = PlatformKind.ORDER,
        mediumKeywords = listOf("京东"), sortOrder = 1000, archived = true,
    )

    private fun withRegistered(entries: List<PlatformEntry>, block: () -> Unit) {
        try {
            entries.forEach { PlatformCatalog.register(it) }
            block()
        } finally {
            PlatformCatalog.resetExtras()
        }
    }

    @Test
    fun `an archived platform is still resolvable for display`() {
        withRegistered(listOf(archivedJd.toPlatformEntry())) {
            // R7：历史流水引用的 ID 必须仍能查到名称
            assertEquals("京东", PlatformCatalog.displayNameOf("user:jd"))
            assertNotNull(PlatformCatalog.find("user:jd"), "归档条目必须仍在目录里，否则历史流水显示会塌成未知平台")
        }
    }

    @Test
    fun `an archived platform is excluded from the selectable list`() {
        withRegistered(listOf(archivedJd.toPlatformEntry())) {
            assertTrue(
                PlatformCatalog.selectable().none { it.id == "user:jd" },
                "已停用的平台不该再被指派给新流水",
            )
            // 但它仍在 all() 里（展示与搜索需要）
            assertTrue(PlatformCatalog.all().any { it.id == "user:jd" })
        }
    }

    @Test
    fun `archiving flips only the flag not the identity`() {
        withRegistered(listOf(archivedJd.toPlatformEntry())) {
            val active = PlatformCatalog.find("user:jd")!!.copy(archived = false)
            PlatformCatalog.register(active)
            // 恢复后立刻可指派 —— 且 **ID 不变**（历史流水靠它关联，改名/停用都不能换 ID）
            assertEquals("user:jd", PlatformCatalog.selectable().first { it.id == "user:jd" }.id)
            assertEquals("京东", PlatformCatalog.displayNameOf("user:jd"))
        }
    }

    @Test
    fun `a custom platform sorts after every built in platform`() {
        // 「内置在前、自定义在后」靠 sortOrder 实现，不靠注册顺序
        withRegistered(listOf(archivedJd.toPlatformEntry())) {
            val ids = PlatformCatalog.all().map { it.id }
            val builtInLast = ids.indexOfLast { it == "unionpay" }
            val customAt = ids.indexOf("user:jd")
            assertTrue(customAt > builtInLast, "自定义平台必须排在内置之后，实得顺序=$ids")
            assertEquals(PlatformCatalog.UNKNOWN_ID, ids.last(), "unknown 永远最后")
        }
    }

    @Test
    fun `toPlatformEntry carries the archived flag through`() {
        // 这条如果断了，上面全部语义都会失效（归档条目会以"启用"身份混进候选）
        assertTrue(archivedJd.toPlatformEntry().archived)
        assertTrue(!archivedJd.copy(archived = false).toPlatformEntry().archived)
    }

    @Test
    fun `generated ids are namespaced so they can never collide with built in ones`() {
        val generated = newUserPlatformId()
        assertTrue(generated.startsWith(UserPlatform.ID_PREFIX))
        assertNull(PlatformCatalog.find(generated), "内置目录不可能含随机 UUID 的 ID")
    }
}
