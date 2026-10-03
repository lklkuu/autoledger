package com.autoledger.app.ui.stores

import android.content.Context
import android.net.Uri
import com.autoledger.app.di.AppContainer
import com.autoledger.core.model.LedgerTransaction
import com.autoledger.feature.capture.CaptureSource
import com.autoledger.feature.capture.PermissionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ------------------------------------------------------------------ 采集

class CaptureStore(private val container: AppContainer) {

    data class SourceRow(
        val source: CaptureSource,
        val state: PermissionState,
        val hint: String,
    )

    data class State(
        val rows: List<SourceRow> = emptyList(),
        val rawQueue: List<LedgerTransaction> = emptyList(),
        val working: Boolean = false,
        val message: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // B4：rawQueue 用 observeRaw 订阅（待确认队列实时刷新）；rows 权限状态依赖 context，进入时刷新一次。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + storeExceptionHandler)
    private var observeJob: Job? = null

    fun load(context: Context) {
        observeJob?.cancel()
        observeJob = scope.launch { observe() }
        refreshRows(context)
    }

    fun close() {
        observeJob?.cancel()
        observeJob = null
        scope.cancel()
    }

    /** 待确认队列实时订阅（写操作后自动刷新）。 */
    private suspend fun observe() {
        container.repository.observeRaw()
            .flowOn(Dispatchers.Default)
            .collect { raw ->
                _state.value = _state.value.copy(
                    rawQueue = raw.sortedByDescending { it.occurredAtMillis },
                )
            }
    }

    /** 渠道权限状态（依赖 context，非 Room 数据，进入页面时刷新一次即可）。 */
    fun refreshRows(context: Context) {
        storeScope.launch {
            val rows = container.captureSources.map { source ->
                val st = source.permissionState(context)
                SourceRow(source, st, source.statusHint(st))
            }
            _state.value = _state.value.copy(rows = rows)
        }
    }

    /** 拉历史：短信 / 账单文件这类需要主动补录的渠道 */
    fun pullBacklog(context: Context, sourceId: String, uri: Uri? = null) {
        val source = container.captureRegistry.find(sourceId) ?: return
        storeScope.launch {
            _state.value = _state.value.copy(working = true, message = null)
            catching {
                withContext(Dispatchers.IO) {
                    source.pullBacklog(context, uri?.let { mapOf("uri" to it) } ?: emptyMap())
                }
            }.onSuccess { envelopes ->
                // 一次补录可能几十条：放进**单个数据库事务**——
                // 把多次提交压缩成一次，缩小"入了一半"的不一致窗口（R5）；
                // 单条解析失败用 catching 容错，不影响其余批次。
                val outcomes = catching {
                    container.repository.inTransaction {
                        envelopes.map { envelope ->
                            catching { container.ingestPipeline.ingest(envelope) }.isSuccess
                        }
                    }
                }.getOrElse { List(envelopes.size) { false } }
                val ok = outcomes.count { it }
                _state.value = _state.value.copy(
                    working = false,
                    message = "已采集 ${envelopes.size} 条，成功入账 $ok 条",
                )
            }.onFailure {
                _state.value = _state.value.copy(working = false, message = "采集失败：${it.message}")
            }
            // B4：待确认队列由 observeRaw 自动刷新，无需手动 refresh。
        }
    }
}
