package com.autoledger.app.ui.stores

import android.net.Uri
import com.autoledger.app.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ------------------------------------------------------------------ 设置

class SettingsStore(private val container: AppContainer) {

    data class State(
        val message: String? = null,
        val working: Boolean = false,
        val learnedRules: Int = 0,
        val transactionCount: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun refresh() {
        storeScope.launch {
            val learned = catching { container.correctionLearner.learnedCount() }.getOrDefault(0)
            val count = catching { container.repository.countAll() }.getOrDefault(0)
            _state.value = _state.value.copy(learnedRules = learned, transactionCount = count)
        }
    }

    fun exportJson(uri: Uri) {
        storeScope.launch {
            _state.value = _state.value.copy(working = true, message = null)
            catching {
                withContext(Dispatchers.IO) {
                    val json = container.backupManager.exportJson("1.0.0", android.os.Build.MODEL)
                    container.applicationContext.contentResolver.openOutputStream(uri)?.use {
                        it.write(json.toByteArray())
                    } ?: error("无法写入文件")
                }
            }.onSuccess { _state.value = _state.value.copy(working = false, message = "导出成功") }
                .onFailure { _state.value = _state.value.copy(working = false, message = "导出失败：${it.message}") }
        }
    }

    fun importJson(uri: Uri) {
        storeScope.launch {
            _state.value = _state.value.copy(working = true, message = null)
            catching {
                withContext(Dispatchers.IO) {
                    val text = container.applicationContext.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: error("无法读取文件")
                    container.backupManager.import(text, com.autoledger.core.backup.BackupManager.MergeStrategy.MERGE_BY_ID)
                }
            }.onSuccess { outcome ->
                _state.value = _state.value.copy(
                    working = false,
                    message = "导入成功：${outcome.transactionsUpserted} 笔流水（档案版本 v${outcome.fileVersion} → v${outcome.migratedToVersion}）",
                )
            }.onFailure { _state.value = _state.value.copy(working = false, message = "导入失败：${it.message}") }
            refresh()
        }
    }

    /** 加密导出：口令派生密钥 + AES-GCM 密封，适合「存网盘 / 发别人」的场景。 */
    fun exportEncrypted(uri: Uri, passphrase: CharArray) {
        storeScope.launch {
            _state.value = _state.value.copy(working = true, message = null)
            catching {
                withContext(Dispatchers.IO) {
                    val json = container.backupManager.exportEncrypted("1.0.0", android.os.Build.MODEL, passphrase)
                    container.applicationContext.contentResolver.openOutputStream(uri)?.use {
                        it.write(json.toByteArray())
                    } ?: error("无法写入文件")
                }
            }.onSuccess { _state.value = _state.value.copy(working = false, message = "加密导出成功") }
                .onFailure { _state.value = _state.value.copy(working = false, message = "加密导出失败：${it.message}") }
        }
    }

    /** 加密导入：先注入口令，再走统一导入管线；口令用完即弃，不落盘。 */
    fun importEncrypted(uri: Uri, passphrase: CharArray) {
        storeScope.launch {
            _state.value = _state.value.copy(working = true, message = null)
            catching {
                withContext(Dispatchers.IO) {
                    val text = container.applicationContext.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: error("无法读取文件")
                    container.backupManager.currentPassphrase = passphrase
                    try {
                        container.backupManager.import(text, com.autoledger.core.backup.BackupManager.MergeStrategy.MERGE_BY_ID)
                    } finally {
                        container.backupManager.currentPassphrase = null
                    }
                }
            }.onSuccess { outcome ->
                _state.value = _state.value.copy(
                    working = false,
                    message = "加密导入成功：${outcome.transactionsUpserted} 笔流水",
                )
            }.onFailure { e ->
                val msg = if (e is javax.crypto.AEADBadTagException || e.cause is javax.crypto.AEADBadTagException) {
                    "口令错误，请重新输入"
                } else {
                    "导入失败：${e.message}"
                }
                _state.value = _state.value.copy(working = false, message = msg)
            }
        }
    }

    fun clearAll() {
        storeScope.launch {
            catching { container.repository.clearAllTransactions() }
            refresh()
        }
    }

    fun resetLearning() {
        storeScope.launch {
            catching { container.correctionLearner.resetLearning() }
            refresh()
        }
    }
}
