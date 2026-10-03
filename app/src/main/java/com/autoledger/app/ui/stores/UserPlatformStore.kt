package com.autoledger.app.ui.stores

import com.autoledger.app.di.AppContainer
import com.autoledger.core.model.UserPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class UserPlatformStore(private val container: AppContainer) {

    data class State(
        /** 启用中的（参与识别与指派）。 */
        val active: List<UserPlatform> = emptyList(),
        /** 已停用的（不再识别，但历史流水仍显示其名称）。 */
        val archived: List<UserPlatform> = emptyList(),
        val message: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + storeExceptionHandler)

    fun load() {
        scope.launch { refresh() }
    }

    fun close() {
        scope.cancel()
    }

    private suspend fun refresh() {
        val all = catching { container.repository.listUserPlatforms(includeArchived = true) }
            .getOrDefault(emptyList())
        _state.value = _state.value.copy(
            active = all.filter { !it.archived }.sortedBy { it.sortOrder },
            archived = all.filter { it.archived }.sortedBy { it.sortOrder },
        )
    }

    /** 新增或更新一个自定义平台。校验不过只提示、不落库。 */
    fun save(draft: UserPlatformDraft) {
        val error = draft.validate()
        if (error != null) {
            _state.value = _state.value.copy(message = error)
            return
        }
        scope.launch {
            val existing = draft.id?.takeIf { it.isNotBlank() }?.let { id ->
                catching { container.repository.listUserPlatforms(includeArchived = true) }
                    .getOrDefault(emptyList()).firstOrNull { it.id == id }
            }
            val platform = draft.toUserPlatform(existing)
            catching {
                container.repository.upsertUserPlatform(platform)
                container.syncUserPlatformsToCatalog()
            }
                .onFailure { _state.value = _state.value.copy(message = "保存失败：${it.message}") }
                .onSuccess { _state.value = _state.value.copy(message = "已保存「${platform.displayName}」") }
            refresh()
        }
    }

    /**
     * 停用 / 恢复。
     *
     * 停用走**软删除**（`archived = true`，行保留）：历史流水的 `platformId` 指向它，
     * 物理删除会让那些流水变成孤儿 ID、展示塌成「未知平台」（R7）。
     * 恢复就是把它重新写回 `archived = false` —— 复用 upsert，不需要额外的 DAO 方法。
     */
    fun setArchived(id: String, archived: Boolean) {
        scope.launch {
            val current = catching { container.repository.listUserPlatforms(includeArchived = true) }
                .getOrDefault(emptyList()).firstOrNull { it.id == id }
            if (current == null) {
                _state.value = _state.value.copy(message = "这条平台已经不在了")
                return@launch
            }
            catching {
                container.repository.upsertUserPlatform(current.copy(archived = archived))
                container.syncUserPlatformsToCatalog()
            }
                .onFailure { _state.value = _state.value.copy(message = "操作失败：${it.message}") }
                .onSuccess {
                    _state.value = _state.value.copy(
                        message = if (archived) "已停用「${current.displayName}」（历史流水仍显示该名称）"
                        else "已恢复「${current.displayName}」",
                    )
                }
            refresh()
        }
    }
}
