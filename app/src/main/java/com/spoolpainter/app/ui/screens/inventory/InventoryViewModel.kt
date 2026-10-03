package com.spoolpainter.app.ui.screens.inventory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import com.spoolpainter.app.data.remote.inventory.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InventoryUiState(
    val snapshot: InventorySnapshot? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val uncertain: Boolean = false,
    val selectedEntity: String = "spool",
    val selected: JsonObject? = null,
    val editorOpen: Boolean = false,
    val creating: Boolean = false,
    val fields: List<JsonObject> = emptyList(),
)

@HiltViewModel
class InventoryViewModel @Inject constructor(private val repository: InventoryRepository) : ViewModel() {
    private val _state = MutableStateFlow(InventoryUiState())
    val state = _state.asStateFlow()
    init { refresh() }

    fun clearNotice() { _state.value = _state.value.copy(error = null, message = null) }
    fun closeDetail() { if (!_state.value.busy) _state.value = _state.value.copy(selected = null, editorOpen = false) }
    fun closeEditor() { if (!_state.value.busy) _state.value = _state.value.copy(editorOpen = false) }
    fun refresh() = read {
        val snapshot = repository.snapshot()
        val old = _state.value
        val list = when (old.selectedEntity) { "filament" -> snapshot.filaments; "vendor" -> snapshot.vendors; else -> snapshot.spools }
        _state.value = old.copy(snapshot = snapshot, selected = old.selected?.let { selected -> list.find { it.recordId() == selected.recordId() } }, uncertain = false, editorOpen = false)
    }
    fun select(entity: String, id: Int) = read {
        val url = requireNotNull(_state.value.snapshot) { "请先刷新库存" }.url
        val record = repository.get(entity, id, url)
        _state.value = _state.value.copy(selectedEntity = entity, selected = record, editorOpen = false)
    }
    fun edit(entity: String, create: Boolean = false) = read {
        val url = requireNotNull(_state.value.snapshot) { "请先刷新库存" }.url
        val current = if (create) null else _state.value.selected?.let { repository.get(entity, it.recordId(), url) }
        val fields = repository.fields(entity, url)
        _state.value = _state.value.copy(selectedEntity = entity, selected = current, editorOpen = true, creating = create, fields = fields)
    }
    fun save(body: JsonObject) = mutate {
        val s = _state.value
        val result = repository.save(s.selectedEntity, if (s.creating) null else s.selected, body, s.snapshot!!.url)
        _state.value = s.copy(selected = result, editorOpen = false, creating = false)
        "已保存"
    }
    fun archive() = mutate {
        val s = _state.value
        val record = requireNotNull(s.selected)
        val result = repository.save("spool", record, JsonObject().apply { addProperty("archived", !record.isArchived()) }, s.snapshot!!.url)
        _state.value = s.copy(selected = result)
        if (result.isArchived()) "已归档" else "已恢复"
    }
    fun delete() = mutate {
        val s = _state.value
        repository.delete(s.selectedEntity, requireNotNull(s.selected), s.snapshot!!.url)
        _state.value = s.copy(selected = null, editorOpen = false)
        "已删除"
    }
    fun weight(grams: Double, measure: Boolean) = mutate {
        val s = _state.value
        val result = repository.weight(requireNotNull(s.selected), grams, measure, s.snapshot!!.url)
        _state.value = s.copy(selected = result)
        if (measure) "毛重称量已保存，剩余重量以服务器结果为准" else "已记录消耗"
    }
    fun uid(input: String, remove: Boolean) = mutate {
        val s = _state.value
        val result = repository.bind(requireNotNull(s.selected), input, remove, s.snapshot!!.url)
        _state.value = s.copy(selected = result)
        if (remove) "已解绑并复查" else "已绑定并复查"
    }
    fun preparePrint(onReady: (JsonObject, String) -> Unit) = read {
        val s = _state.value
        check(!s.uncertain) { "请先刷新核对上次操作结果" }
        check(s.selectedEntity == "spool") { "请选择库存记录" }
        val selected = requireNotNull(s.selected) { "请选择库存记录" }
        val url = requireNotNull(s.snapshot) { "请先刷新库存" }.url
        repository.verifyServer(url)
        onReady(selected, url)
    }
    fun lookup(input: String) = read {
        val url = requireNotNull(_state.value.snapshot) { "请先刷新库存" }.url
        val found = repository.lookup(input, url)
        check(found.size <= 1) { "UID 重复关联：${found.joinToString { "#${it.recordId()}" }}。请先在服务器核对，禁止自动选择或改绑。" }
        _state.value = _state.value.copy(selectedEntity = "spool", selected = found.singleOrNull(), message = if (found.isEmpty()) "此 UID 尚未绑定，可选择库存后绑定" else "已找到 UID 对应库存")
    }
    private fun read(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null, message = null)
        viewModelScope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { _state.value = _state.value.copy(error = e.message ?: "操作失败") }
            finally { _state.value = _state.value.copy(busy = false) }
        }
    }
    private fun mutate(block: suspend () -> String) {
        if (_state.value.busy || _state.value.uncertain) return
        val sourceUrl = _state.value.snapshot?.url ?: return
        _state.value = _state.value.copy(busy = true, error = null, message = null)
        viewModelScope.launch {
            try {
                val message = block()
                _state.value = _state.value.copy(message = message)
                try {
                    val refreshed = repository.snapshot()
                    check(refreshed.url == sourceUrl) { "服务器地址在操作期间发生变化" }
                    repository.verifyServer(sourceUrl)
                    _state.value = _state.value.copy(snapshot = refreshed)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    // Never attach a different server's snapshot to the completed
                    // mutation's selected record, even when numeric IDs coincide.
                    _state.value = _state.value.copy(
                        snapshot = null, selected = null, editorOpen = false,
                        creating = false, fields = emptyList(), uncertain = true,
                        error = "操作已成功提交至 $sourceUrl，但库存复查失败：${e.message}。请刷新后重新选择库存并核对。",
                    )
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.value = _state.value.copy(error = e.message ?: "操作失败", uncertain = e is InventoryFailure && e.unknownOutcome) }
            finally { _state.value = _state.value.copy(busy = false) }
        }
    }
}
