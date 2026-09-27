package com.autoledger.feature.transfer

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.Socket

/**
 * 新机侧：连接旧机热点 + 接收迁移数据。
 *
 * 连接热点用 [WifiNetworkSpecifier]（Android 10+）。拿到 [Network] 后，
 * 通过 `network.socketFactory` 建 Socket 交给纯 JVM 的 [TransferSession]。
 *
 * LocalOnlyHotspot 的网关（旧机）IP 固定为 192.168.43.1。
 */
class TransferClient(private val context: Context) {

    sealed interface State {
        object Idle : State
        object Connecting : State
        data class Receiving(val received: Int, val total: Int) : State
        data class Done(val totalBytes: Long) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** 连接热点 + 接收迁移数据。 */
    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun receive(
        ticket: TransferTicket,
        onProgress: (received: Int, total: Int) -> Unit = { _, _ -> },
    ): Pair<ByteArray, TransferEnvelope> {
        _state.value = State.Connecting
        return withContext(Dispatchers.IO) {
            try {
                val network = connectToHotspot(ticket)
                val socket = network.socketFactory.createSocket(
                    InetAddress.getByName(LOCAL_ONLY_HOTSPOT_GATEWAY), ticket.port,
                ) as Socket
                val (payload, envelope) = TransferSession(socket).receive(ticket.token) { received, total ->
                    _state.value = State.Receiving(received, total)
                    onProgress(received, total)
                }
                _state.value = State.Done(payload.size.toLong())
                payload to envelope
            } catch (e: Exception) {
                _state.value = State.Failed(e.message ?: "接收失败")
                throw e
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun connectToHotspot(ticket: TransferTicket): Network =
        suspendCancellableCoroutine { cont ->
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val spec = WifiNetworkSpecifier.Builder()
                .setSsid(ticket.ssid)
                .setWpa2Passphrase(ticket.password)
                .build()
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(spec)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (cont.isActive) {
                        cm.unregisterNetworkCallback(this)
                        cont.resume(network, onCancellation = null)
                    }
                }
                override fun onUnavailable() {
                    if (cont.isActive) {
                        cm.unregisterNetworkCallback(this)
                        cont.resumeWith(Result.failure(IllegalStateException("连接热点失败")))
                    }
                }
            }
            cm.requestNetwork(request, callback)
        }

    private companion object {
        const val LOCAL_ONLY_HOTSPOT_GATEWAY = "192.168.43.1"
    }
}
