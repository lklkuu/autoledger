package com.autoledger.feature.ai

import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * AI 请求的传输端口。
 *
 * 抽成接口是为了让 [AiTypeRefiner] 的全部判定逻辑（模式门控 / 超时 / 回落 / 置信度阈值）
 * 能在**纯 JVM 单测**里跑 —— 单测不该、也发不出真实网络请求。
 */
public interface AiTransport {
    /**
     * 发起一次 POST。
     *
     * @return 响应体原文；任何失败（连接、超时、非 2xx、读取异常）都**抛异常**，
     *         由 [AiTypeRefiner] 统一 `runCatching` 兜住并回落本地。
     */
    fun post(endpoint: String, apiKey: String, body: String): String
}

/**
 * 基于 [HttpURLConnection] 的实现。
 *
 * ⚠️ **刻意不引入 OkHttp**：v1.1.6 刚把 release APK 从 33.7 MB 压到 7.8 MB（ABI 分包 + R8 + 资源收敛），
 * 本项目连二维码都只用纯 JVM 的 zxing-core、能不引依赖就不引。一次只发一个请求、
 * 一次只读一段响应体，HttpURLConnection 足够。
 *
 * 请求体用 OpenAI 兼容形状（`model` / `messages` / `temperature`），
 * 这样主流服务可直接对接；密钥走 `Authorization: Bearer`。
 */
class HttpAiTransport : AiTransport {

    override fun post(endpoint: String, apiKey: String, body: String): String {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (code !in 200..299) {
                // 只把状态码带出去：错误响应体可能含服务端提示，回落原因分类里不需要它，
                // 更不能把它写进日志（可能含原文片段）。
                throw AiTransportException("HTTP $code")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        /**
         * 网络层自己的超时（毫秒）。比 [AiTypeRefiner] 的整体预算略短，
         * 让「整体预算」先触发、把回落原因稳定归到 TIMEOUT 而不是 socket 异常。
         */
        const val CONNECT_TIMEOUT_MS = 1_500
        const val READ_TIMEOUT_MS = 1_800
    }
}

/** 传输层失败（连接 / 超时 / 非 2xx）。只携带**分类信息**，不含任何响应体内容。 */
class AiTransportException(message: String) : RuntimeException(message)
