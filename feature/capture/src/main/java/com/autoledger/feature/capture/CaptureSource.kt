package com.autoledger.feature.capture

import android.content.Context
import com.autoledger.core.model.RawEnvelope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * **采集渠道插件契约**（工程要求 1：采集渠道模块化可插拔）。
 *
 * 新增一种资金来源 = 新增一个实现类 + 在 [CaptureRegistry] 里注册一行，
 * 不改动解析器、不改动流水线、不改动 UI（UI 按 [CaptureAction] 能力渲染，不硬编码渠道 id）。
 */
interface CaptureSource {

    /** 稳定唯一标识，会写进流水的 sourceId，做数据分析时按它分组 */
    val id: String

    val displayName: String

    val description: String

    /** 该渠道需要用户授权的权限列表；无需授权的渠道返回空 */
    val requiredPermissions: List<String>

    /** 是否需要用户在系统设置里开启（通知监听进入 Setting，而非危险权限弹窗） */
    val needsSystemToggle: Boolean

    /** 该渠道支持的用户操作，采集箱据此渲染按钮（而非按渠道 id 硬编码）。 */
    val actions: List<CaptureAction> get() = emptyList()

    fun isSupported(context: Context): Boolean = true

    fun permissionState(context: Context): PermissionState

    /** 打开本渠道的系统设置页（如通知使用权）。默认无操作。 */
    fun openSystemSettings(context: Context) {}

    /** 权限状态对应的提示文案，供采集箱展示。 */
    fun statusHint(state: PermissionState): String = when (state) {
        PermissionState.GRANTED -> description
        PermissionState.NOT_GRANTED -> "尚未开启"
        PermissionState.NOT_APPLICABLE -> "无需权限"
    }

    /** 一次性补录历史数据：例如扫描过往短信、让用户选择账单文件 */
    suspend fun pullBacklog(context: Context, args: Map<String, Any?> = emptyMap()): List<RawEnvelope> = emptyList()
}

/** 采集渠道可执行的操作（UI 据此渲染，不硬编码渠道 id）。 */
sealed interface CaptureAction {
    data class OpenSystemSettings(val label: String = "去系统设置开启") : CaptureAction
    data class RequestPermission(val permission: String, val label: String = "授权") : CaptureAction
    data class ScanBacklog(val label: String = "扫描历史") : CaptureAction
    data class PickFile(val mimeType: String, val label: String = "选择文件") : CaptureAction
}

enum class PermissionState { GRANTED, NOT_GRANTED, NOT_APPLICABLE }

/**
 * 采集总线。
 *
 * 通知监听跑在 Service 里、短信扫描跑在协程里、账单导入跑在 UI 线程之外，
 * 它们只往这里扔信封；真正入库由 [IngestPipeline] 统一处理。
 * 这样渠道再多也只有一条写入路径，去重与分类不会被绕过。
 */
object CaptureDispatcher {

    private val listeners = mutableListOf<suspend (RawEnvelope) -> Unit>()

    /**
     * **串行化门**：杜绝「两条通知同刻到达 ⇒ 各自查重都查不到对方 ⇒ 同一笔被记两次」的竞态。
     *
     * ingest 的「查重 → 写入 → 合并」段存在 TOCTOU：两条通知几乎同时到达时，
     * 两个协程各自查重都查不到对方，双双落库且永不合并 —— 而这正是
     * 「一笔数字人民币支付会同时触发银行短信 + 银行 App + 数币 App 多条通知」的核心场景。
     *
     * 分发本身只是转发，串行化不影响吞吐（通知到达频率远低于处理速度）。
     */
    private val ingestGate = Mutex()

    fun subscribe(listener: suspend (RawEnvelope) -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    suspend fun submit(envelope: RawEnvelope) {
        val snapshot = synchronized(listeners) { listeners.toList() }
        ingestGate.withLock {
            snapshot.forEach { runCatching { it(envelope) } }
        }
    }
}
