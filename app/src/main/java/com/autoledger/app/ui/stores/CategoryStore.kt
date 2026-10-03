package com.autoledger.app.ui.stores

import com.autoledger.app.di.AppContainer
import com.autoledger.core.model.Category
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

class CategoryStore(private val container: AppContainer) {

    data class State(
        val expense: List<Category> = emptyList(),
        val income: List<Category> = emptyList(),
        val message: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // B4：实例级作用域 + observeCategories 订阅。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + storeExceptionHandler)
    private var observeJob: Job? = null

    fun load() {
        observeJob?.cancel()
        observeJob = scope.launch { observe() }
    }

    fun close() {
        observeJob?.cancel()
        observeJob = null
        scope.cancel()
    }

    private suspend fun observe() {
        container.repository.observeCategories()
            .flowOn(Dispatchers.Default)
            .collect { categories ->
                val (expense, income) = groupCategoriesByKind(categories)
                _state.value = _state.value.copy(expense = expense, income = income)
            }
    }

    /** 保存分类；校验不过则只提示、不落库。 */
    fun save(draft: CategoryDraft) {
        val error = draft.validate()
        if (error != null) {
            _state.value = _state.value.copy(message = error)
            return
        }
        storeScope.launch {
            val existing = catching { container.repository.listCategories() }
                .getOrDefault(emptyList()).firstOrNull { it.id == draft.id }
            catching { container.repository.upsertCategory(draft.toCategory(existing)) }
                .onFailure { _state.value = _state.value.copy(message = "保存失败：${it.message}") }
                .onSuccess {
                    // B4：observeCategories Flow 会自动刷新列表，这里只设置操作提示。
                    _state.value = _state.value.copy(message = "已保存「${draft.name.trim()}」")
                }
        }
    }

    fun delete(id: String) {
        storeScope.launch {
            catching { container.repository.deleteCategory(id) }
                .onFailure { _state.value = _state.value.copy(message = "删除失败：${it.message}") }
                .onSuccess {
                    _state.value = _state.value.copy(message = "已删除")
                }
        }
    }
}
