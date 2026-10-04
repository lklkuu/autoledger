package com.autoledger.app

import com.autoledger.core.database.SettingsDao
import com.autoledger.core.database.toAppSettings
import com.autoledger.core.database.toEntity
import com.autoledger.core.model.AiMode
import com.autoledger.core.model.AppSettings
import com.autoledger.core.model.FreedomGoal
import com.autoledger.core.model.WageProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 用户设置（时薪参数 / 自由基金目标 / 行为开关）。
 *
 * 持久化在 Room（单行 app_settings），金额以「分」存储，随账本备份一起导出/导入，
 * 换机迁移不再丢设置。相比旧版 SharedPreferences+Float：类型安全、可备份、无浮点误差。
 */
class UserSettings(
    private val dao: SettingsDao,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(AppSettings())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    val wage: StateFlow<WageProfile> = _state.map { it.wage }.stateIn(scope, SharingStarted.Eagerly, WageProfile())
    val goal: StateFlow<FreedomGoal> = _state.map { it.goal }.stateIn(scope, SharingStarted.Eagerly, FreedomGoal())
    val autoMerge: StateFlow<Boolean> = _state.map { it.autoMerge }.stateIn(scope, SharingStarted.Eagerly, true)

    /** 启动时从数据库加载一次（幂等，默认值在加载前生效）。 */
    fun init() {
        scope.launch { dao.global()?.let { _state.value = it.toAppSettings() } }
    }

    fun updateWage(wage: WageProfile) = update { it.copy(wage = wage) }

    fun updateGoal(goal: FreedomGoal) = update { it.copy(goal = goal) }

    fun setAutoMerge(enabled: Boolean) = update { it.copy(autoMerge = enabled) }

    /**
     * AI 判定配置（开关 / 模式 / 接口地址 / 模型名）。
     *
     * ⚠️ 这里**没有也不该有** API 密钥：密钥走 `AiKeyVault`（Keystore 包裹 + SharedPreferences，
     * 该文件被排除在备份之外），因此它既不进 Room 也不进备份档案。
     */
    fun setAi(enabled: Boolean, mode: AiMode, endpoint: String, model: String) = update {
        it.copy(aiEnabled = enabled, aiMode = mode, aiEndpoint = endpoint.trim(), aiModel = model.trim())
    }

    private fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_state.value)
        _state.value = next
        scope.launch { dao.upsert(next.toEntity()) }
    }
}
