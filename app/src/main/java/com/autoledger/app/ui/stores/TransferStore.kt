package com.autoledger.app.ui.stores

import android.os.Build
import com.autoledger.app.di.AppContainer
import com.autoledger.core.backup.BackupManager
import com.autoledger.feature.transfer.HotspotServer
import com.autoledger.feature.transfer.RawTextPolicy
import com.autoledger.feature.transfer.RewrapService
import com.autoledger.feature.transfer.TransferClient
import com.autoledger.feature.transfer.TransferMeta
import com.autoledger.feature.transfer.TransferTicket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 换机迁移状态机（C4）。
 *
 * 旧机（发送方）：导出（rawText 用旧机主密钥解密为明文）→ 拉起热点 → 返回票据 → 等待连接 → 分块发送。
 * 新机（接收方）：扫码得到票据 → 连热点 → 分块接收 → SHA-256 校验 → rawText 用新机主密钥重加密 → 导入。
 */
class TransferStore(private val container: AppContainer) {

    sealed interface State {
        object Idle : State
        /** 旧机：热点已就绪，等待新机连接（ticket 供二维码 / 手动输入）。 */
        data class ReadyToSend(val ticket: TransferTicket) : State
        /** 传输进行中。 */
        data class Progress(val done: Int, val total: Int) : State
        data class Finished(val summary: String) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val rewrap = RewrapService(container.cryptoBox)
    private val server = HotspotServer(container.applicationContext)
    private val client = TransferClient(container.applicationContext)

    /** 旧机：导出（rawText 解密）+ 拉起热点；发送在后台等待新机连接。 */
    fun startSend() {
        scope.launch {
            try {
                val ticket = server.start()
                _state.value = State.ReadyToSend(ticket)
                val json = container.backupManager.exportJson(APP_VERSION, Build.MODEL, rewrap::unwrap)
                val payload = json.toByteArray(Charsets.UTF_8)
                val meta = TransferMeta(APP_VERSION, Build.MODEL, RawTextPolicy.REWRAPPED)
                server.serve(payload, meta) { sent, total -> _state.value = State.Progress(sent, total) }
                _state.value = State.Finished("已发送 ${payload.size} 字节，请在新机确认导入结果")
            } catch (e: Exception) {
                _state.value = State.Failed(e.message ?: "发送失败")
            }
        }
    }

    /** 新机：扫码/输入票据后，接收 + rawText 重加密导入。 */
    fun startReceive(ticket: TransferTicket) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            _state.value = State.Failed("接收迁移需要 Android 10 及以上系统")
            return
        }
        scope.launch {
            try {
                val (payload, _) = client.receive(ticket) { received, total ->
                    _state.value = State.Progress(received, total)
                }
                val json = String(payload, Charsets.UTF_8)
                val outcome = container.backupManager.import(
                    json, BackupManager.MergeStrategy.MERGE_BY_ID, rewrap::rewrap,
                )
                _state.value = State.Finished("导入成功：${outcome.transactionsUpserted} 笔流水")
            } catch (e: Exception) {
                _state.value = State.Failed(e.message ?: "接收失败")
            }
        }
    }

    fun reset() {
        server.stop()
        _state.value = State.Idle
    }

    fun close() {
        server.stop()
        scope.cancel()
    }

    companion object {
        const val APP_VERSION = "1.0.0"
    }
}
