package com.spoolpainter.app.ui.screens.printing

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.spoolpainter.app.hardware.printer.LabelQrMode
import com.spoolpainter.app.hardware.printer.PaperTemplate
import com.spoolpainter.app.hardware.printer.LabelField
import com.spoolpainter.app.hardware.printer.PaperTail
import com.spoolpainter.app.hardware.printer.PrintRequest
import com.spoolpainter.app.hardware.printer.SpoolLabel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PrintPreparationTest {
    private val request = PrintRequest(SpoolLabel(123, "Old", "PLA", "000000", 999.0, "Old shelf"), "https://example.org/base/", LabelQrMode.BOTH)
    private fun fixture(): JsonObject = JsonParser.parseString("""{
        "id":123,"remaining_weight":123.5,"location":"Fresh shelf",
        "filament":{"name":"PETG Pro","material":"PETG","color_hex":"FFFFFF","vendor":{"name":"Fresh vendor"}}
    }""").asJsonObject

    @Test fun fetchesFreshFieldsAndPreservesQrModeBeforeEnqueue() = runTest {
        val submitted = mutableListOf<PrintRequest>()
        var reads = 0
        val worker = PrintPreparation(backgroundScope, { "https://example.org/base" }, { id ->
            assertEquals(123, id); reads++; fixture()
        }, { true }, { value, tail -> assertEquals(PaperTail.ONE_AND_HALF_CM, tail); submitted += value; true })
        assertTrue(worker.start(request, PaperTail.ONE_AND_HALF_CM))
        assertEquals(PrintPreparationState.Loading, worker.state.value)
        runCurrent()
        assertEquals(1, reads)
        assertEquals(SpoolLabel(123, "Fresh vendor", "PETG", "FFFFFF", 123.5, "Fresh shelf", name = "PETG Pro"), submitted.single().label)
        assertEquals(LabelQrMode.BOTH, submitted.single().qrMode)
        assertEquals("https://example.org/base", submitted.single().serverUrl)
        assertEquals(PrintPreparationState.Ready(request, submitted.single()), worker.state.value)
    }

    @Test fun duplicateTapsDuringFetchCannotPrintTwice() = runTest {
        val fetched = CompletableDeferred<JsonObject>()
        var dispatches = 0
        val worker = PrintPreparation(backgroundScope, { request.serverUrl }, { fetched.await() }, { true }, { _, _ -> dispatches++; true })
        assertTrue(worker.start(request, PaperTail.ONE_CM))
        assertFalse(worker.start(request, PaperTail.ONE_CM))
        runCurrent()
        assertFalse(worker.start(request, PaperTail.ONE_CM))
        assertEquals(0, dispatches)
        fetched.complete(fixture())
        runCurrent()
        assertEquals(1, dispatches)
    }

    @Test fun changedServerBeforeFetchRejectsWithoutReadingOrPrinting() = runTest {
        val worker = PrintPreparation(backgroundScope, { "https://another.example" }, { error("must not fetch") }, { true }, { _, _ -> error("must not print") })
        worker.start(request, PaperTail.ONE_CM)
        runCurrent()
        assertTrue((worker.state.value as PrintPreparationState.Failed).message.contains("服务器地址已改变"))
    }

    @Test fun changedServerDuringFetchRejectsWithoutPrinting() = runTest {
        var url = request.serverUrl
        val worker = PrintPreparation(backgroundScope, { url }, { url = "https://another.example"; fixture() }, { true }, { _, _ -> error("must not print") })
        worker.start(request, PaperTail.ONE_CM)
        runCurrent()
        assertTrue((worker.state.value as PrintPreparationState.Failed).message.contains("读取期间"))
    }

    @Test fun failedFetchDoesNotPrintOrRetryAutomatically() = runTest {
        var reads = 0
        val worker = PrintPreparation(backgroundScope, { request.serverUrl }, { reads++; error("HTTP 404") }, { true }, { _, _ -> error("must not print") })
        worker.start(request, PaperTail.ONE_CM)
        runCurrent()
        runCurrent()
        assertEquals(1, reads)
        assertTrue((worker.state.value as PrintPreparationState.Failed).message.contains("404"))
    }

    @Test fun wrongRecordOrMissingFilamentCannotPrint() = runTest {
        for (body in listOf(fixture().apply { addProperty("id", 456) }, JsonObject().apply { addProperty("id", 123) })) {
            val worker = PrintPreparation(backgroundScope, { request.serverUrl }, { body }, { true }, { _, _ -> error("must not print") })
            worker.start(request, PaperTail.ONE_CM)
            runCurrent()
            assertTrue(worker.state.value is PrintPreparationState.Failed)
        }
    }

    @Test fun leavingBeforeFetchCompletesCancelsPreparationWithoutPrinting() = runTest {
        val scope = TestScope(testScheduler)
        val fetched = CompletableDeferred<JsonObject>()
        var dispatches = 0
        val worker = PrintPreparation(scope, { request.serverUrl }, { fetched.await() }, { true }, { _, _ -> dispatches++; true })
        worker.start(request, PaperTail.ONE_CM)
        runCurrent()
        scope.cancel()
        fetched.complete(fixture())
        runCurrent()
        assertEquals(0, dispatches)
    }
    @Test fun capturesTemplateBeforeFetchAndPreservesItWhenProfilesAreEdited() = runTest {
        val selectedFields = mutableSetOf(LabelField.ID, LabelField.NAME, LabelField.QR)
        val original = PaperTemplate(name = "原模板", heightMm = 80.0, gapMm = 2.0, offsetYmm = 1.0, selectedFields = selectedFields)
        val requestSnapshot = request.copy(qrMode = LabelQrMode.WEB, paper = original).capturePaperOptions()
        val fetched = CompletableDeferred<JsonObject>()
        val submitted = mutableListOf<PrintRequest>()
        var disk: String? = null
        val profiles = PaperTemplateStore({ disk }, { disk = it; true }, { "one" })
        profiles.save(null, original)
        val worker = PrintPreparation(backgroundScope, { request.serverUrl }, { fetched.await() }, { true }, { value, _ -> submitted += value; true })
        worker.start(requestSnapshot, PaperTail.ONE_AND_HALF_CM)
        runCurrent()
        selectedFields.clear()
        profiles.save("one", original.copy(name = "新版", heightMm = 120.0, selectedFields = setOf(LabelField.LOCATION)))
        fetched.complete(fixture())
        runCurrent()
        val captured = submitted.single().paper!!
        assertEquals("原模板", captured.name)
        assertEquals(80.0, captured.heightMm, 0.0)
        assertEquals(2.0, captured.gapMm, 0.0)
        assertEquals(1.0, captured.offsetYmm, 0.0)
        assertEquals(setOf(LabelField.ID, LabelField.NAME, LabelField.QR), captured.selectedFields)
        assertEquals("PETG Pro", submitted.single().label.name)
        assertEquals(LabelQrMode.WEB, submitted.single().qrMode)
    }

    @Test fun captureKeepsOptionalRetractionDuringLatestRecordFetchWithoutFollowingUiChanges() = runTest {
        var stored = false
        val preference = ReceiptRetractionPreference({ stored }, { stored = it })
        preference.set(true)
        var storedUnits = 120
        val distance = RetractionDistancePreference({ storedUnits }, { storedUnits = it })
        distance.set(160)
        val clicked = request.copy(retractBeforePrint = preference.state.value, retractUnits = distance.state.value)
        val captured = clicked.capturePaperOptions()
        val fetched = CompletableDeferred<JsonObject>()
        val submitted = mutableListOf<PrintRequest>()
        val worker = PrintPreparation(backgroundScope, { request.serverUrl }, { fetched.await() }, { true }, { value, _ -> submitted += value; true })
        worker.start(captured, PaperTail.ONE_AND_HALF_CM)
        runCurrent()
        preference.set(false)
        distance.set(40)
        fetched.complete(fixture())
        runCurrent()
        assertTrue(submitted.single().retractBeforePrint)
        assertFalse(preference.state.value)
        assertEquals(160, submitted.single().retractUnits)
        assertEquals(40, distance.state.value)
        assertEquals("PETG Pro", submitted.single().label.name)
        assertEquals(LabelQrMode.BOTH, submitted.single().qrMode)
    }

}
