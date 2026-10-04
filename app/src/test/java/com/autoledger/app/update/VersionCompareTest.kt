package com.autoledger.app.update

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * 版本号比较的护栏。
 *
 * 这里的每条用例都对应一种**真实会遇到的输入**：本地 `versionName` 是 `1.1.6`，
 * 而 GitHub tag 普遍带 `v` 前缀（`v1.2.0`），预发布版可能带后缀（`1.1.6-beta1`）。
 *
 * 最容易写错的一条是 `1.1.10` vs `1.1.9`：按字符串比字典序会判 `1.1.9` 更大，
 * 于是把新版本说成"已是最新"，用户永远升不了级。
 */
class VersionCompareTest {

    // ------------------------------------------------------------------ 有新版本 / 无新版本

    @Test
    fun `1_1_6 vs 1_1_7 means an update is available`() {
        assertEquals(-1, compareVersions("1.1.6", "1.1.7"), "1.1.6 < 1.1.7")
        assertEquals(1, compareVersions("1.1.7", "1.1.6"), "反向也要成立")
    }

    @Test
    fun `same version means no update`() {
        assertEquals(0, compareVersions("1.1.7", "1.1.7"))
    }

    @Test
    fun `1_1_10 is greater than 1_1_9`() {
        // 字典序在这里会判反（"1.1.9" > "1.1.10"），必须逐段按数字比。
        assertEquals(1, compareVersions("1.1.10", "1.1.9"))
        assertEquals(-1, compareVersions("1.1.9", "1.1.10"))
    }

    // ------------------------------------------------------------------ 前缀 / 段数 / 非法段

    @Test
    fun `v prefix and missing segments are normalized away`() {
        assertEquals(0, compareVersions("v1.2.0", "1.2"), "v 前缀 + 段数补 0 后应相等")
        assertEquals(0, compareVersions("V1.2.0", "1.2.0"), "大写 V 同样要认")
        assertEquals(0, compareVersions("  1.2.0  ", "1.2.0"), "首尾空白要忽略")
        assertEquals(1, compareVersions("v1.2.1", "1.2"), "补齐后仍要能正确比大小")
    }

    @Test
    fun `non numeric segments do not throw and degrade to the numeric prefix`() {
        assertEquals(0, compareVersions("1.1.6-beta1", "1.1.6"))
        assertEquals(0, compareVersions("1.1.6-rc.2", "1.1.6"))
        assertEquals(1, compareVersions("1.1.7-beta1", "1.1.6"), "预发布的新版本仍应算更大")
    }

    @Test
    fun `2_0 beats 1_9_9`() {
        assertEquals(1, compareVersions("2.0", "1.9.9"))
    }

    @Test
    fun `degenerate inputs never throw`() {
        // 空串 / 只有 v / 段里没有数字：都应安全退化，而不是让设置页崩。
        assertEquals(0, compareVersions("", ""))
        assertEquals(0, compareVersions("v", ""))
        assertEquals(-1, compareVersions("", "0.0.1"), "空串视作 0.0.0")
    }

    // ------------------------------------------------------------------ 限流与失败路径

    @Test
    fun `network failure is returned as Result failure and never throws`() = runBlocking {
        // 注入一个不可能连通的地址：既不发真请求到 GitHub，也不用引入 MockWebServer 等新依赖。
        val checker = GitHubUpdateChecker(
            endpoint = "http://127.0.0.1:1/definitely-not-listening",
            connectTimeoutMs = 300,
            readTimeoutMs = 300,
        )

        val result: Result<ReleaseInfo?> = checker.check()

        assertTrue(result.isFailure, "网络失败必须是 Result.failure，而不是抛异常")
        val error = result.exceptionOrNull()
        assertNotNull(error)
        assertTrue(
            error is CheckUpdateException,
            "失败必须是 CheckUpdateException，实际是 ${error.javaClass.simpleName}",
        )
        // 用户看到的原因要能直接读懂：不含堆栈、不含 URL。
        val message = error.message.orEmpty()
        assertTrue(message.isNotBlank(), "失败原因不能为空，否则设置页会显示空白")
        assertTrue(!message.contains("http://") && !message.contains("https://"), "失败原因里不该出现 URL")
        assertTrue(!message.contains("Exception"), "失败原因里不该出现异常类名")
    }
}
