package com.autoledger.feature.platform

import com.autoledger.core.model.UserPlatform
import com.autoledger.core.model.platform.PlatformCatalog
import com.autoledger.core.model.platform.PlatformContext
import com.autoledger.core.model.platform.PlatformKind
import com.autoledger.core.model.toPlatformEntry
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.Test

/**
 * **停用的自定义平台不再参与识别**（R7 的另一半）。
 *
 * 归档条目必须继续留在目录里（历史流水要显示名称），所以"停用"**不能**靠"不注册它"实现 ——
 * 必须由识别引擎**显式跳过** `archived` 条目。本文件锁住这条：
 * 同一段文本，平台启用时识别得到、停用后必须回落。
 *
 * ⚠️ 每个用例结束都必须清空运行时条目（`replaceExtras(emptyList())`）：
 * `PlatformCatalog` 是**进程级可变单例**，不清理会污染同进程内其它测试（这是已知风险 R5）。
 * 这里用 public 的 `replaceExtras` 而不是 `resetExtras` —— 后者是 core:model 的 `internal`，跨模块调不到。
 */
class ArchivedPlatformResolverTest {

    private val resolver = KeywordPlatformResolver()

    private fun resolve(rawText: String, counterparty: String? = null) = resolver.resolve(
        PlatformContext(rawText = rawText, counterparty = counterparty, packageName = "sms:inbox", sourceId = "sms:inbox"),
    )

    /** 用真实的用户自定义平台（`user:<uuid>` ID），走完整的"注册 → 识别"链路。 */
    private fun jd(archived: Boolean) = UserPlatform(
        id = "user:jd-test",
        displayName = "京东",
        kind = PlatformKind.ORDER,
        strongKeywords = listOf("京东支付"),
        mediumKeywords = listOf("京东"),
        sortOrder = 1000,
        archived = archived,
    )

    private fun withCatalog(vararg platforms: UserPlatform, block: () -> Unit) {
        try {
            platforms.forEach { PlatformCatalog.register(it.toPlatformEntry()) }
            block()
        } finally {
            PlatformCatalog.replaceExtras(emptyList())
        }
    }

    @Test
    fun `an active custom platform is recognised from its keywords`() {
        withCatalog(jd(archived = false)) {
            val r = resolve("京东支付 59.00 元")
            assertEquals("user:jd-test", r.platformId, "启用中的自定义平台必须能识别出来")
            assertEquals(PlatformKind.ORDER, PlatformCatalog.find(r.platformId)?.kind)
        }
    }

    @Test
    fun `an archived custom platform is no longer recognised but is still displayable`() {
        withCatalog(jd(archived = true)) {
            val r = resolve("京东支付 59.00 元")

            // 停用 ⇒ 不再识别（这段文本没有任何别的线索 ⇒ 落 unknown）
            assertNotEquals("user:jd-test", r.platformId, "停用的平台不得再被识别出来")
            assertEquals(PlatformCatalog.UNKNOWN_ID, r.platformId)

            // 但展示必须照旧 —— 这正是 R7 的要害：历史流水的平台名不能塌成「未知平台」
            assertEquals("京东", PlatformCatalog.displayNameOf("user:jd-test"))
        }
    }

    @Test
    fun `archiving does not affect other platforms recognition`() {
        // 回归护栏：跳过归档条目不能因为"continue 写错位置"而把整轮识别打断
        withCatalog(jd(archived = true)) {
            val alipay = resolve("支付宝付款 25.80 元")
            assertEquals("alipay", alipay.platformId, "内置平台的识别不受归档条目影响")
        }
    }

    @Test
    fun `a platform archived mid flight stops being recognised immediately`() {
        // 用户在设置里停用一个平台后，**不需要重启**：目录是进程内缓存，
        // 写成功后立刻重建（AppContainer.syncUserPlatformsToCatalog），识别行为随之改变。
        withCatalog(jd(archived = false)) {
            assertEquals("user:jd-test", resolve("京东支付 59.00 元").platformId)

            PlatformCatalog.register(jd(archived = true).toPlatformEntry())
            assertEquals(
                PlatformCatalog.UNKNOWN_ID,
                resolve("京东支付 59.00 元").platformId,
                "重建目录（同 ID 覆盖）后必须立刻生效，不必重启 App",
            )
        }
    }
}
