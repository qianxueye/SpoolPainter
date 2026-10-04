package com.spoolpainter.app.ui.screens.printing

import com.google.gson.JsonObject
import com.spoolpainter.app.data.remote.inventory.child
import com.spoolpainter.app.data.remote.inventory.number
import com.spoolpainter.app.data.remote.inventory.text
import com.spoolpainter.app.hardware.printer.PaperTail
import com.spoolpainter.app.hardware.printer.PrintRequest
import com.spoolpainter.app.hardware.printer.SpoolLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PrintPreparationState {
    data object Idle : PrintPreparationState
    data object Loading : PrintPreparationState
    data class Ready(val original: PrintRequest, val fresh: PrintRequest) : PrintPreparationState
    data class Failed(val message: String) : PrintPreparationState
}

/** App-level read-before-print orchestration; hardware remains independent of server models. */
class PrintPreparation(
    private val scope: CoroutineScope,
    private val currentServer: suspend () -> String,
    private val fetchSpool: suspend (Int) -> JsonObject,
    private val canSubmit: () -> Boolean,
    private val submit: (PrintRequest, PaperTail) -> Boolean,
) {
    private val mutableState = MutableStateFlow<PrintPreparationState>(PrintPreparationState.Idle)
    val state = mutableState.asStateFlow()

    // Called on Main. Mark Loading synchronously so two taps cannot launch two fetches.
    fun start(request: PrintRequest, tail: PaperTail): Boolean {
        if (mutableState.value == PrintPreparationState.Loading || !canSubmit()) return false
        mutableState.value = PrintPreparationState.Loading
        scope.launch {
            try {
                request.qrPayloads()
                val expectedUrl = normalizedServer(request.serverUrl)
                check(normalizedServer(currentServer()) == expectedUrl) { "服务器地址已改变，请重新选择耗材后打印" }
                val spool = fetchSpool(request.label.id)
                check(normalizedServer(currentServer()) == expectedUrl) { "读取期间服务器地址已改变，请重新选择耗材后打印" }
                check(spool.text("id").toIntOrNull() == request.label.id) { "服务器返回的耗材编号不匹配，已取消打印" }
                check(spool.get("filament")?.isJsonObject == true) { "服务器返回的耗材信息不完整，已取消打印" }
                val filament = spool.child("filament")
                val fresh = request.copy(
                    label = SpoolLabel(
                        id = request.label.id,
                        vendor = filament.child("vendor").text("name"),
                        material = filament.text("material"),
                        color = filament.text("color_hex").ifBlank { filament.text("multi_color_hexes") },
                        remainingGrams = spool.number("remaining_weight"),
                        location = spool.text("location"),
                        name = filament.text("name"),
                    ),
                    serverUrl = expectedUrl,
                )
                currentCoroutineContext().ensureActive()
                check(canSubmit()) { "打印机正在处理其他任务，请等待完成后重试" }
                check(submit(fresh, tail)) { "打印机尚未接受任务，请检查打印机状态后重试" }
                mutableState.value = PrintPreparationState.Ready(request, fresh)
            } catch (e: CancellationException) {
                mutableState.value = PrintPreparationState.Idle
                throw e
            } catch (e: Exception) {
                mutableState.value = PrintPreparationState.Failed("准备打印失败：${e.message ?: "无法读取最新耗材信息"}")
            }
        }
        return true
    }

    private fun normalizedServer(value: String) = value.trim().trimEnd('/')
}
