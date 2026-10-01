package com.autoledger.app.ui.stores

import com.autoledger.core.model.UserPlatform
import com.autoledger.core.model.platform.PlatformKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 自定义平台「编辑草稿」的校验与派生（纯逻辑，不需要 Activity / Room）。
 *
 * 重点锁三条：
 * 1. **没有信号的平台要被拦住** —— 没有关键词/包名的平台永远识别不出来，
 *    放它落库等于给用户一个"加了但没用"的死条目；
 * 2. **编辑不换 ID** —— 历史流水把 ID 存进了 `platform_id`，换 ID 会让那些流水变成孤儿；
 * 3. **编辑不重置 sortOrder** —— 否则改一次名字，这个平台就会从列表中间跳到末尾。
 */
class UserPlatformDraftTest {

    private fun draft(
        id: String? = null,
        name: String = "京东",
        strong: String = "",
        medium: String = "京东",
        weak: String = "",
        packages: String = "",
    ) = UserPlatformDraft(
        id = id,
        displayName = name,
        kind = PlatformKind.ORDER,
        strongKeywords = strong,
        mediumKeywords = medium,
        weakKeywords = weak,
        packageNames = packages,
    )

    @Test
    fun `a name plus at least one keyword is valid`() {
        assertNull(draft().validate())
        assertNull(draft(name = "山姆", medium = "", packages = "com.samsclub.app").validate(), "包名也算信号")
    }

    @Test
    fun `a blank name is rejected`() {
        assertEquals("请填写平台名称", draft(name = "   ").validate())
    }

    @Test
    fun `a platform with no signals is rejected`() {
        // 这是最重要的一条：没有信号的平台永远不会被识别，用户会以为"加了但没用"
        val error = draft(medium = "", strong = "", weak = "", packages = "").validate()
        assertTrue(error != null && error.contains("识别"), "必须拦住无信号平台，实得=$error")
    }

    @Test
    fun `whitespace only lines are not counted as signals`() {
        assertEquals(
            "请填写平台名称",
            draft(name = "").validate(),
        )
        assertTrue(draft(medium = "  \n  \n").validate() != null, "只有空白的行不算线索")
    }

    @Test
    fun `editing keeps the id so history does not become orphaned`() {
        val existing = UserPlatform(
            id = "user:abc", displayName = "京东", sortOrder = 1005, createdAtMillis = 1_700_000_000_000L,
        )
        val updated = draft(id = "user:abc", name = "京东商城").toUserPlatform(existing, now = 9_999L)
        assertEquals("user:abc", updated.id, "改名绝不能换 ID")
        assertEquals("京东商城", updated.displayName)
    }

    @Test
    fun `editing keeps sort order and creation time`() {
        val existing = UserPlatform(
            id = "user:abc", displayName = "京东", sortOrder = 1005, createdAtMillis = 1_700_000_000_000L,
        )
        val updated = draft(id = "user:abc").toUserPlatform(existing, now = 9_999L)
        assertEquals(1005, updated.sortOrder, "编辑不该把它踢到列表末尾")
        assertEquals(1_700_000_000_000L, updated.createdAtMillis, "创建时间不该被刷新")
        assertNotEquals(9_999L, updated.createdAtMillis)
    }

    @Test
    fun `creating generates a namespaced id and the default sort order`() {
        val created = draft().toUserPlatform(existing = null, now = 9_999L)
        assertTrue(created.id.startsWith(UserPlatform.ID_PREFIX), "实得=${created.id}")
        assertEquals(UserPlatform.DEFAULT_SORT_ORDER, created.sortOrder, "新增接在内置之后")
        assertEquals(9_999L, created.createdAtMillis)
        assertTrue(!created.archived, "新增默认启用")
    }

    @Test
    fun `keywords accept both newlines and commas and get trimmed`() {
        val created = UserPlatformDraft(
            displayName = "京东",
            strongKeywords = "京东支付\n 京东plus ",
            mediumKeywords = "京东，京喜",
            packageNames = " com.jingdong.app.mall \n\n",
        ).toUserPlatform(now = 1L)
        assertEquals(listOf("京东支付", "京东plus"), created.strongKeywords)
        assertEquals(listOf("京东", "京喜"), created.mediumKeywords)
        assertEquals(setOf("com.jingdong.app.mall"), created.packageNames)
    }

    @Test
    fun `archived state survives editing so that editing does not silently revive a platform`() {
        // 用户停用了某平台，随后编辑它的关键词 —— 不该因此把它悄悄重新启用
        val archived = UserPlatform(id = "user:abc", displayName = "京东", archived = true)
        val edited = UserPlatformDraft.from(archived).copy(displayName = "京东商城").toUserPlatform(archived, now = 1L)
        assertTrue(edited.archived, "编辑停用中的平台必须保持停用")
    }
}
