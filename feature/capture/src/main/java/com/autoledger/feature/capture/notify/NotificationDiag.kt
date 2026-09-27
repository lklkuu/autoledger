package com.autoledger.feature.capture.notify

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 通知采集诊断：排查「为什么一笔都没记录」。
 *
 * 记录两件事：
 * 1. 监听服务**是否已连接**（onListenerConnected / onListenerDisconnected）；
 * 2. **最近收到的每条通知**及其解析结果（命中哪条规则 / 因何被丢弃）。
 *
 * 这样用户和开发者一眼就能分清是「通知根本没到 App」还是「到了但没解析成功」。
 * 内存 StateFlow 供 UI 实时读；同时持久化最近 N 条（跨进程重启保留）。
 */
object NotificationDiag {

    data class Entry(
        val timeMillis: Long,
        val packageName: String,
        val title: String,
        val body: String,
        /** 结果：命中的规则 / 丢弃原因（常驻通知、折叠摘要、未命中规则、无金额…）。 */
        val outcome: String,
    )

    private const val PREFS = "autoledger_notif_diag"
    private const val KEY_JSON = "entries_json"
    private const val MAX = 30

    private var appContext: Context? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        _entries.value = load()
    }

    fun setConnected(value: Boolean) {
        _connected.value = value
    }

    fun record(entry: Entry) {
        val next = (listOf(entry) + _entries.value).take(MAX)
        _entries.value = next
        persist(next)
    }

    fun clear() {
        _entries.value = emptyList()
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.remove(KEY_JSON)?.apply()
    }

    private fun persist(entries: List<Entry>) {
        val ctx = appContext ?: return
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("t", e.timeMillis)
                put("p", e.packageName)
                put("title", e.title)
                put("body", e.body)
                put("o", e.outcome)
            })
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_JSON, arr.toString()).apply()
    }

    private fun load(): List<Entry> {
        val ctx = appContext ?: return emptyList()
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_JSON, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(o.optLong("t"), o.optString("p"), o.optString("title"), o.optString("body"), o.optString("o"))
            }
        }.getOrDefault(emptyList())
    }
}
