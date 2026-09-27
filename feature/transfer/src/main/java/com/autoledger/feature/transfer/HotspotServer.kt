package com.autoledger.feature.transfer

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.ServerSocket
import java.security.SecureRandom

/**
 * 旧机侧：拉起本地热点 + 监听连接 + 执行迁移发送。
 *
 * 职责单一：只负责「Android 设备能力（LocalOnlyHotspot + ServerSocket）」，
 * 具体的握手 / 分块 / 校验交给纯 JVM 的 [TransferSession]（可单测）。
 */
class HotspotServer(private val context: Context) {

    sealed interface State {
        object Idle : State
        data class Ready(val ticket: TransferTicket) : State
        data class Sending(val sent: Int, val total: Int) : State
        data class Done(val totalBytes: Long) : State
        data class Failed(val message: String) : State
    }

    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var serverSocket: ServerSocket? = null

    /** 拉起热点 + 启动监听，返回配对票据（旧机展示二维码用）。 */
    suspend fun start(): TransferTicket = withContext(Dispatchers.IO) {
        stop() // 幂等：先清理旧状态
        val r = startLocalOnlyHotspot()
        val cfg = r.wifiConfiguration ?: throw IllegalStateException("热点配置为空")
        val ssid = cfg.SSID.removeSurrounding("\"")
        val password = cfg.preSharedKey.orEmpty()
        val token = generateToken()
        val server = ServerSocket(0)
        reservation = r
        serverSocket = server
        val ticket = TransferTicket(ssid, password, token, server.localPort)
        _state.value = State.Ready(ticket)
        ticket
    }

    /** 等待新机连接并发送 payload（阻塞到发送完成或失败）。 */
    suspend fun serve(payload: ByteArray, meta: TransferMeta) {
        val server = serverSocket ?: run { _state.value = State.Failed("尚未启动"); return }
        val token = (_state.value as? State.Ready)?.ticket?.token ?: return
        withContext(Dispatchers.IO) {
            try {
                server.accept().use { socket ->
                    TransferSession(socket).serve(payload, token, meta) { sent, total ->
                        _state.value = State.Sending(sent, total)
                    }
                }
                _state.value = State.Done(payload.size.toLong())
            } catch (e: Exception) {
                _state.value = State.Failed(e.message ?: "发送失败")
            }
        }
    }

    /** 释放热点与监听 socket。 */
    fun stop() {
        serverSocket?.close()
        serverSocket = null
        reservation?.close()
        reservation = null
    }

    private suspend fun startLocalOnlyHotspot(): WifiManager.LocalOnlyHotspotReservation =
        suspendCancellableCoroutine { cont ->
            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                    if (cont.isActive) cont.resume(r, onCancellation = null)
                }
                override fun onFailed(reason: Int) {
                    if (cont.isActive) cont.resumeWith(Result.failure(IllegalStateException("热点启动失败（reason=$reason）")))
                }
            }, null)
        }

    private fun generateToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
