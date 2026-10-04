package com.spoolpainter.app.ui.screens.printing

import com.google.gson.JsonParser
import com.spoolpainter.app.hardware.printer.LabelField
import com.spoolpainter.app.hardware.printer.PaperTemplate
import org.junit.Assert.*
import org.junit.Test

class PaperTemplateStoreTest {
    @Test fun firstLaunchUsesReceiptWithoutWritingSettings() {
        var writes = 0
        val store = PaperTemplateStore({ null }, { writes++; true })
        assertNull(store.state.value.selected)
        assertTrue(store.state.value.profiles.isEmpty())
        assertEquals(0, writes)
    }

    @Test fun namedGeometryFieldsAndSelectionSurviveRestart() {
        var disk: String? = null
        val paper = PaperTemplate(name = "货架标签", widthMm = 42.0, heightMm = 80.0, gapMm = 3.5,
            offsetXmm = 1.25, offsetYmm = 2.5, marginMm = 3.0, fontDots = 24, qrMm = 20.0,
            selectedFields = setOf(LabelField.ID, LabelField.NAME, LabelField.QR))
        val store = PaperTemplateStore({ disk }, { disk = it; true }, { "profile-1" })
        store.save(null, paper)
        val reloaded = PaperTemplateStore({ disk }, { disk = it; true })
        assertEquals(PaperTemplateProfile("profile-1", paper), reloaded.state.value.selected)
        reloaded.select(null)
        assertNull(PaperTemplateStore({ disk }, { true }).state.value.selected)
        assertEquals(1, reloaded.state.value.profiles.size)
    }

    @Test fun editingKeepsIdentityAndDeletingSelectionReturnsToReceipt() {
        var disk: String? = null
        val store = PaperTemplateStore({ disk }, { disk = it; true }, { "profile-1" })
        store.save(null, PaperTemplate(name = "第一版"))
        store.save("profile-1", PaperTemplate(name = "第二版", heightMm = 90.0))
        assertEquals(1, store.state.value.profiles.size)
        assertEquals("profile-1", store.state.value.selectedId)
        assertEquals(90.0, store.state.value.selected!!.paper.heightMm, 0.0)
        store.delete("profile-1")
        val restored = decodePaperTemplates(disk)
        assertTrue(restored.profiles.isEmpty())
        assertNull(restored.selectedId)
    }

    @Test fun unsafeStoredOffsetsDoNotRestoreOrRewriteAnInvalidSelection() {
        val good = PaperTemplateProfile("good", PaperTemplate(name = "正常"))
        val root = JsonParser.parseString(encodePaperTemplates(PaperTemplateSelection(listOf(good), "good"))).asJsonObject
        val unsafe = root.getAsJsonArray("profiles")[0].asJsonObject.deepCopy().apply {
            addProperty("id", "unsafe"); addProperty("offset_y_mm", -2.0)
        }
        root.getAsJsonArray("profiles").add(unsafe)
        root.addProperty("selected_id", "unsafe")
        var writes = 0
        val restored = PaperTemplateStore({ root.toString() }, { writes++; true })
        assertEquals(listOf(good), restored.state.value.profiles)
        assertNull(restored.state.value.selected)
        assertEquals(0, writes)
        assertEquals(PaperTemplateSelection(), decodePaperTemplates("not JSON"))
    }

    @Test fun duplicateNamesAndFailedWritesLeaveThePreviousSelectionUntouched() {
        var successfulWrite = true
        var nextId = 0
        val store = PaperTemplateStore({ null }, { successfulWrite }, { "profile-${++nextId}" })
        store.save(null, PaperTemplate(name = "标签"))
        val before = store.state.value
        try { store.save(null, PaperTemplate(name = " 标签 ")); fail("duplicate name accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(before, store.state.value)
        successfulWrite = false
        try { store.select(null); fail("failed save accepted") } catch (_: IllegalStateException) { }
        assertEquals(before, store.state.value)
    }

    @Test fun fractionalFontAndUnknownFieldsAreRejectedDuringRecovery() {
        val serialized = encodePaperTemplates(PaperTemplateSelection(listOf(PaperTemplateProfile("one", PaperTemplate())), "one"))
        for (mutation in listOf<(com.google.gson.JsonObject) -> Unit>(
            { it.addProperty("font_dots", 20.5) },
            { it.getAsJsonArray("fields").add("UNKNOWN") },
            { it.addProperty("width_mm", 49.0) },
        )) {
            val root = JsonParser.parseString(serialized).asJsonObject
            mutation(root.getAsJsonArray("profiles")[0].asJsonObject)
            assertEquals(PaperTemplateSelection(), decodePaperTemplates(root.toString()))
        }
    }
}
