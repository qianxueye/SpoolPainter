package com.spoolpainter.app.ui.screens.printing

import com.spoolpainter.app.hardware.printer.PaperTemplate
import com.spoolpainter.app.hardware.printer.PrintRequest
import com.spoolpainter.app.hardware.printer.SpoolLabel
import org.junit.Assert.*
import org.junit.Test

class ReceiptRetractionControlTest {
    private val receipt = PrintRequest(SpoolLabel(12, "品牌", "PLA", "FFFFFF", 800.0, "A"), "https://example.org")

    @Test fun defaultOffDoesNotWriteAPreference() {
        var writes = 0
        val preference = ReceiptRetractionPreference({ false }, { writes++ })
        assertFalse(preference.state.value)
        assertFalse(receipt.retractBeforePrint)
        assertFalse(receipt.capturePaperOptions().retractBeforePrint)
        assertEquals(0, writes)
    }

    @Test fun explicitSwitchPersistsAndDirectSubmissionCapturesItsSelectedValue() {
        var stored = false
        val preference = ReceiptRetractionPreference({ stored }, { stored = it })
        preference.set(true)
        val reopened = ReceiptRetractionPreference({ stored }, { stored = it })
        assertTrue(reopened.state.value)
        val enabled = receipt.copy(retractBeforePrint = reopened.state.value).capturePaperOptions()
        assertTrue(enabled.retractBeforePrint)
        reopened.set(false)
        assertFalse(stored)
        assertTrue(enabled.retractBeforePrint)
        assertFalse(receipt.capturePaperOptions().retractBeforePrint)
    }

    @Test fun fixedLabelsCannotImportReceiptReversePreference() {
        val fixed = receipt.copy(paper = PaperTemplate())
        val captured = fixed.capturePaperOptions()
        assertNotNull(captured.paper)
        assertFalse(captured.retractBeforePrint)
        assertEquals(PaperTemplate(), captured.paper)
        try { fixed.copy(retractBeforePrint = true); fail("fixed label reverse request accepted") } catch (_: IllegalArgumentException) { }
    }

    @Test fun unreadablePreferenceFallsBackToOffWithoutChangingStoredData() {
        var writes = 0
        val preference = ReceiptRetractionPreference({ throw IllegalStateException("wrong stored type") }, { writes++ })
        assertFalse(preference.state.value)
        assertEquals(0, writes)
    }
}
