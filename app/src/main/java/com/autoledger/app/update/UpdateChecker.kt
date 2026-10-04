package com.autoledger.app.update

import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** GitHub Release 的最小信息集（只取设置页要用的四个字段）。 */
data class ReleaseInfo(
    val tagName: String,
    val name: String,
    val notes: String,
    val htmlUrl: String,
)

/**
 * 「检查更新」的 UI 状态机。
 *
 * 每个分支都必须对应**一句用户能看懂的话** —— 静默失败是这个功能最容易犯的错：
 * 用户点了按钮却什么也没发生，只会以为应用卡了。
 */
sealed interface UpdateUiState {
    /** 还没点过。 */
    data object Idle : UpdateUiState

    /** 正在请求（按钮禁用，防连点）。 */
    data object Checking : UpdateUiState

    /** 已是最新。[current] 用于回显当前版本号。 */
    data class UpToDate(val current: String) : UpdateUiState

    /** 有新版本。[notes] 已截断，[url] 指向该 Release 页面。 */
    data class NewAvailable(val latest: String, val notes: String, val url: String) : UpdateUiState

    /** 检查失败。[reason] 是**给用户看的可读原因**，不是异常堆栈、也不含 URL。 */
    data class Failed(val reason: String) : UpdateUiState
}

/** 检查器端口。返回 `Result`：成功且 `null` = 无新版本。 */
interface UpdateChecker {
    suspend fun check(): Result<ReleaseInfo?>
}

/**
 * 检查 GitHub Release 是否比当前版本新。
 *
 * 只用 [HttpURLConnection]，与 `feature:ai` 的 `AiTransport` 同一套做法：
 * 1.1.6 刚把 release APK 从 33.7 MB 压到 7.8 MB（ABI 分包 + R8 + 资源收敛），
 * 这个项目连二维码都只用纯 JVM 的 zxing-core —— **不引入 OkHttp**。
 *
 * @param endpoint 便于测试注入；生产用默认的 GitHub API 地址。
 * @param connectTimeoutMs / readTimeoutMs 同上。
 */
class GitHubUpdateChecker(
    private val endpoint: String = GITHUB_LATEST_RELEASE_API,
    private val connectTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
) : UpdateChecker {

    /**
     * 永不抛异常：任何失败都包成 `Result.failure`，且 message 是**用户可读的中文原因**，
     * 不含异常堆栈、不含 URL（URL 本身不算敏感，但报错文案里堆地址只会让人更困惑）。
     *
     * ⚠️ 网络调用**必须**在 [Dispatchers.IO] 上跑：`HttpURLConnection` 是阻塞调用，
     * 在主线程直接连会抛 `NetworkOnMainThreadException`（API 26+ 强检）—— 真机上
     * 「检查更新」会 100% 崩。这里与 `feature/ai` 的 `AiTypeRefiner.decide` 同一套
     * 「IO 调度 + 硬超时 + 可中断」范式：`HttpURLConnection` 光靠 `withTimeoutOrNull`
     * 取消不了（超时只在阻塞返回后才被观察到），必须有 `runInterruptible`。
     */
    override suspend fun check(): Result<ReleaseInfo?> = try {
        val raw = withContext(Dispatchers.IO) {
            withTimeoutOrNull(NETWORK_BUDGET_MS) {
                runInterruptible { fetchReleaseJsonBlocking() }
            }
        } ?: throw CheckUpdateException(REASON_TIMEOUT)
        Result.success(parseReleaseJson(raw))
    } catch (e: Exception) {
        Result.failure(CheckUpdateException(e.toUserReason()))
    }

    /**
     * 阻塞地取一次响应体。**只在 [check] 的 IO 上下文里被调用**，不要直接调。
     *
     * 只负责 HTTP + 状态码；「异常 → 用户文案」的翻译在 [toUserReason]，两者不混：
     * 这样"403 是限流"这种产品规则一眼能看到。
     */
    private fun fetchReleaseJsonBlocking(): String {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "AutoLedger-Android")
        }
        try {
            val code = conn.responseCode
            if (code == HTTP_FORBIDDEN || code == HTTP_TOO_MANY_REQUESTS) {
                throw CheckUpdateException("请求过于频繁，请稍后再试")
            }
            if (code !in 200..299) {
                throw CheckUpdateException("GitHub 返回异常状态（$code），请稍后再试")
            }
            return conn.inputStream.bufferedReader().use(BufferedReader::readText)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 把技术异常翻译成用户能看懂的原因。
     *
     * **刻意逐个匹配而不是直接用 e.message** —— 否则用户会看到
     * `Failed to connect to api.github.com/...` 这种带地址的原生报错。
     */
    private fun Throwable.toUserReason(): String = when (this) {
        is CheckUpdateException -> message ?: REASON_GENERIC
        is SocketTimeoutException -> "网络超时，请检查网络后重试"
        is UnknownHostException -> "连不上 GitHub，请检查网络后重试"
        else -> REASON_GENERIC   // JSON 解析失败 / SSL / IO 等一律兜底
    }

    companion object {
        const val GITHUB_LATEST_RELEASE_API =
            "https://api.github.com/repos/lklkuu/autoledger/releases/latest"
        const val DEFAULT_TIMEOUT_MS = 8_000
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_TOO_MANY_REQUESTS = 429
        /** 整个网络往返的硬预算，比单次 socket 超时长一点，让"点了没反应"的感知上限明确。 */
        private const val NETWORK_BUDGET_MS = 12_000L
        private const val REASON_TIMEOUT = "网络超时，请检查网络后重试"
        private const val REASON_GENERIC = "检查更新失败，请稍后再试"
    }
}

/** 检查失败。message 必须是用户可读的中文，且不含堆栈/URL。 */
class CheckUpdateException(message: String) : RuntimeException(message)

/**
 * 比较两个版本号（`1.1.6` 与 GitHub 的 `v1.1.6` 这类）。
 *
 * 规则（每条都对应真实会遇到的输入，不是过度设计）：
 * 1. 剥掉 `v` / `V` 前缀与首尾空白 —— GitHub tag 普遍带 `v`，本地 versionName 不带；
 * 2. **在第一个 `-` 或 `+` 处截断**（semver 的预发布 / build 元数据）——
 *    `1.1.6-beta1` 与 `1.1.6-rc.2` 都视作 `1.1.6`；
 * 3. 按 `.` 拆成数字段**逐段比** —— 字符串比较会在 `1.1.10` vs `1.1.9` 上判错（前者字典序更小）；
 * 4. 段数不同时按 0 补齐 —— `1.2` 视作 `1.2.0`；
 * 5. 每段**只取开头连续的数字** —— 段里没有数字就当 0，绝不抛异常。
 *
 * ⚠️ 第 2 条为什么不能省：若只做第 5 条，`1.1.6-rc.2` 会拆成 `[1,1,6,2]`，
 * 比 `1.1.6`（补 0 后 `[1,1,6,0]`）**大**，于是把一个预发布版判成"比正式版新"。
 * 先截断预发布后缀才是 semver 的本意。
 *
 * 纯函数：不碰网络、不碰 Android，可在纯 JVM 单测里钉死。
 *
 * @return a < b 时 -1，a == b 时 0，a > b 时 1
 */
fun compareVersions(a: String, b: String): Int {
    val left = versionSegments(a)
    val right = versionSegments(b)
    val size = maxOf(left.size, right.size)
    for (i in 0 until size) {
        val l = left.getOrElse(i) { 0 }
        val r = right.getOrElse(i) { 0 }
        if (l != r) return if (l < r) -1 else 1
    }
    return 0
}

/**
 * 把版本号拆成数字段。
 *
 * **本项目的版本号形态**：纯数字三段、只按数字递增（`v1.1.6 → v1.1.7`）。所以只做两件事：
 * 1. 剥掉 `v` / `V` 前缀 —— **这不是"兼容奇怪格式"，而是本仓库 tag 的真实形态**
 *    （`git tag` 实测：`v1.1.2` … `v1.1.6`），GitHub `releases/latest` 的
 *    `tag_name` 就带着这个 `v`。
 * 2. **在第一个 `-` / `+` 处截断**（预发布与构建元数据），再按 `.` 拆段取数字。
 *
 * ⚠️ 第 2 条为什么不能省：tag 是人写的，可能带 `-rc.1` / `+build.7` 这类后缀。
 * 不截断的话 `split('.')` 会把后缀里的数字当成新的一段 ——
 * `1.2.0+build.7` ⇒ `[1,2,0,7]`，比 `1.2.0` ⇒ `[1,2,0,0]` **大**，
 * 于是界面提示"发现新版本 1.2.0+build.7"，而用户装的就是这个版本。
 * **100% 可复现的误报，代价是一行 takeWhile。**
 *
 * `toIntOrNull() ?: 0` 只是防止单个数字段解析失败时抛异常（成本一个 token），
 * 不作为"支持畸形版本号"的特性来宣传。
 */
private fun versionSegments(raw: String): List<Int> {
    val trimmed = raw.trim().removePrefix("v").removePrefix("V").trim()
    if (trimmed.isEmpty()) return emptyList()
    val core = trimmed.takeWhile { it != '-' && it != '+' }
    if (core.isEmpty()) return emptyList()
    return core.split('.').map { segment ->
        segment.toIntOrNull() ?: 0
    }
}

/**
 * 解析 `releases/latest` 的响应体。
 *
 * 抽成顶层 `internal` 纯函数是为了能直接单测它 —— 真发网络请求才能覆盖 JSON 解析，
 * 而测试不该依赖外网。字段缺失一律给空串（`optString` 不抛），整体结构不合法时抛
 * `JSONException`，由 [GitHubUpdateChecker] 的兜底 catch 转成用户可读的失败原因。
 */
internal fun parseReleaseJson(text: String): ReleaseInfo {
    val obj = JSONObject(text)
    val tag = obj.optString("tag_name")
    return ReleaseInfo(
        tagName = tag,
        // name 缺失时退回 tag，避免界面上出现空白标题
        name = obj.optString("name").ifBlank { tag },
        notes = obj.optString("body"),
        htmlUrl = obj.optString("html_url"),
    )
}
