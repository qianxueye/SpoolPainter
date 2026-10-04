package com.spoolpainter.app.ui.screens.printing

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spoolpainter.app.data.local.SettingsRepository
import com.spoolpainter.app.data.remote.inventory.InventoryRepository
import com.spoolpainter.app.hardware.printer.PaperTemplate
import com.spoolpainter.app.hardware.printer.PaperTail
import com.spoolpainter.app.hardware.printer.PrintRequest
import com.spoolpainter.app.hardware.printer.PrinterController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class PrintingViewModel @Inject constructor(
    private val printer: PrinterController,
    @ApplicationContext context: Context,
    inventory: InventoryRepository,
    settings: SettingsRepository,
) : ViewModel() {
    private val preferences = context.getSharedPreferences("pos_printer", Context.MODE_PRIVATE)
    private val mutableTail = MutableStateFlow(PaperTail.fromDots(preferences.getInt("tail_dots", 120)))
    val tail = mutableTail.asStateFlow()
    private val templateStore = PaperTemplateStore(
        read = { preferences.getString("paper_templates_v1", null) },
        write = { preferences.edit().putString("paper_templates_v1", it).commit() },
    )
    val templates = templateStore.state
    val state = printer.state
    val pending = printer.pending
    private val preparationWorker = PrintPreparation(
        scope = viewModelScope,
        currentServer = { settings.awaitSettings().url },
        fetchSpool = { inventory.get("spool", it) },
        canSubmit = { printer.pending.value == 0 },
        submit = printer::enqueue,
    )
    val preparation = preparationWorker.state

    private var visible = false

    /** The activity-scoped ViewModel can outlive this conditional screen. */
    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        if (value) printer.attach() else printer.detach()
    }
    fun setTail(value: PaperTail) {
        preferences.edit().putInt("tail_dots", value.dots).apply()
        mutableTail.value = value
    }
    fun selectTemplate(id: String?) = templateStore.select(id)
    fun saveTemplate(id: String?, paper: PaperTemplate) = templateStore.save(id, paper)
    fun deleteTemplate(id: String) = templateStore.delete(id)

    // Capture field choices with the request; future profile edits cannot alter an active job.
    fun print(request: PrintRequest) = preparationWorker.start(
        request.capturePaperOptions(),
        mutableTail.value,
    )
    override fun onCleared() { setVisible(false); super.onCleared() }
}

/** Defensive request snapshot used before asynchronous fetching or queueing. */
internal fun PrintRequest.capturePaperOptions(): PrintRequest = copy(
    paper = paper?.copy(selectedFields = paper.selectedFields.toSet()),
)
