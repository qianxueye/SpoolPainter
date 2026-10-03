package com.spoolpainter.app.ui.screens.printing

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spoolpainter.app.data.local.SettingsRepository
import com.spoolpainter.app.data.remote.inventory.InventoryRepository
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
    fun print(request: PrintRequest) = preparationWorker.start(request, mutableTail.value)
    override fun onCleared() { setVisible(false); super.onCleared() }
}
