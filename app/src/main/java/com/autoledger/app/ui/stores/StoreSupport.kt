package com.autoledger.app.ui.stores

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Store 的共享支撑物（异常兜底 / 共享作用域 / 协程感知的 runCatching）。
 *
 * 这三个声明原先是 `AppStores.kt` 顶层的 **private**，AppStores.kt 拆成 9 个
 * 「1 类 1 文件」后，跨文件访问必须放开可见性，故改为 **internal**：
 * 二者在 app 模块内可见范围等价（private 顶层 = 同文件；internal = 同模块），
 * 对模块外的调用方没有任何影响，属纯 move 的必要机械改动。
 */

/**
 * Store 后台协程的异常兜底：任何未捕获异常都只记录日志，绝不带崩进程。
 * （HomeStore 的 Flow 内部另有 .catch 收敛为可见错误态，这里是双保险。）
 */
internal val storeExceptionHandler = CoroutineExceptionHandler { _, throwable ->
    android.util.Log.e("AutoLedger", "Store 后台任务异常", throwable)
}

/**
 * 共享作用域：仅服务「一次性任务型」Store（LedgerStore / CaptureStore / SettingsStore 的 load 跑完即结束）。
 * HomeStore 需要长驻订阅，因此**单独持有自己的可取消作用域**，见其属性 [HomeStore.scope]。
 */
internal val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + storeExceptionHandler)

/**
 * [runCatching] 的「协程感知」版本：放行 [CancellationException]，只把真实错误收敛为 Result。
 *
 * 直接使用 runCatching 会连取消异常一起吞掉 —— 协程被取消后仍继续执行后续代码，
 * 破坏结构化并发（订阅已取消却还在写状态）。这里把取消重新抛出，语义才正确。
 */
internal inline fun <T> catching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
