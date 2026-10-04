package com.autoledger.app.update

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Release 响应解析的护栏（**独立于 [VersionCompareTest]**，因为它需要真实 `org.json`）。
 *
 * 为什么单独一个类：`VersionCompareTest` 保持**纯 JVM**（不碰 Android、不碰网络、不碰 org.json），
 * 而 `org.json` 在纯 JVM 的 android.jar 里是 stub —— 在那个类里跑 JSON 断言会
 * 「什么都没验证」地通过。故按测试运行环境拆开，与 core/backup 的 SettingsBackupCompatTest 同一理由。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReleaseJsonTest {

    @Test
    fun `release json is parsed into ReleaseInfo`() {
        val info = parseReleaseJson(
            """{"tag_name":"v1.2.0","name":"小版本更新","body":"修复若干问题","html_url":"https://example.invalid/r/1"}""",
        )

        assertEquals("v1.2.0", info.tagName)
        assertEquals("小版本更新", info.name)
        assertEquals("修复若干问题", info.notes)
    }

    @Test
    fun `missing name falls back to the tag instead of showing a blank title`() {
        val info = parseReleaseJson("""{"tag_name":"v1.2.0","body":"只有正文"}""")

        assertEquals("v1.2.0", info.tagName)
        assertEquals("v1.2.0", info.name, "name 缺失时应退回 tag")
        assertEquals("只有正文", info.notes)
        assertEquals("", info.htmlUrl, "缺失字段给空串而不是抛异常")
    }

    @Test
    fun `garbage json throws so the checker can turn it into a readable failure`() {
        val threw = runCatching { parseReleaseJson("not json at all") }.isFailure
        assertTrue(threw, "整体结构不合法时必须抛，由 GitHubUpdateChecker 的兜底 catch 转成可读原因")
    }
}
