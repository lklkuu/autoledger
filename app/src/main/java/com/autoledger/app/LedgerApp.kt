package com.autoledger.app

import android.app.Application
import android.os.Build
import android.os.Process
import android.util.Log
import com.autoledger.app.di.AppContainer
import java.io.File

class LedgerApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        container = AppContainer(this)
        container.bootstrap()
    }

    /**
     * 全局未捕获异常兜底：把崩溃栈写到应用外部存储的 crash/ 目录，再交回默认处理。
     * 目的：真机闪退时能拿到完整堆栈，而不是只有一句"应用已停止"。
     * 位置：Android/data/com.autoledger.app/files/crash/（或手机文件管理器「内部存储/Android/data/.../files/crash」）。
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val base = getExternalFilesDir(null) ?: filesDir
                val dir = File(base, "crash").apply { mkdirs() }
                val file = File(dir, "crash-${System.currentTimeMillis()}.txt")
                file.writeText(
                    buildString {
                        appendLine("time=${System.currentTimeMillis()}")
                        appendLine("android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                        appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
                        appendLine("abi=${Build.SUPPORTED_ABIS.joinToString(",")}")
                        appendLine("thread=${thread.name}")
                        appendLine()
                        appendLine(Log.getStackTraceString(throwable))
                    }
                )
            }
            // 交回默认 handler（打印到 logcat 并弹系统崩溃框）；若无默认则直接结束进程
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
            }
        }
    }
}
