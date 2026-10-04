package com.spoolpainter.app.ui.screens.printing

import com.spoolpainter.app.hardware.paper.RetractionDistance
import com.spoolpainter.app.hardware.printer.PaperTail
import org.junit.Assert.*
import org.junit.Test

class RetractionDistanceControlTest {
    @Test fun absentDistanceDefaultsToOnePointFiveWithoutResettingEnabledSwitchOrTail() {
        val disk = mutableMapOf<String, Any>("retract_before_print" to true, "tail_dots" to 160)
        var writes = 0
        val distance = RetractionDistancePreference(
            { disk["retract_units"] as? Int ?: RetractionDistance.DEFAULT_UNITS },
            { disk["retract_units"] = it; writes++ },
        )
        val enabled = ReceiptRetractionPreference({ disk.getValue("retract_before_print") as Boolean }, { disk["retract_before_print"] = it })
        assertEquals(120, distance.state.value)
        assertTrue(enabled.state.value)
        assertEquals(PaperTail.TWO_CM.dots, disk["tail_dots"])
        assertEquals(0, writes)
        assertFalse(disk.containsKey("retract_units"))
    }

    @Test fun explicitDistancePersistsAcrossRestartAndNeverChangesOtherOptions() {
        val disk = mutableMapOf<String, Any>("retract_before_print" to true, "tail_dots" to 160)
        val preference = RetractionDistancePreference({ disk["retract_units"] as? Int ?: 120 }, { disk["retract_units"] = it })
        preference.set(RetractionDistance.fromCentimeters("0.7").units)
        val restored = RetractionDistancePreference({ disk.getValue("retract_units") as Int }, { disk["retract_units"] = it })
        assertEquals(56, restored.state.value)
        assertEquals("0.7", RetractionDistance(restored.state.value).centimetersText)
        assertEquals(true, disk["retract_before_print"])
        assertEquals(160, disk["tail_dots"])
    }

    @Test fun invalidStoredUnitsFallBackWithoutRewritingExistingData() {
        for (value in listOf(Int.MIN_VALUE, -8, 0, 7, 9, 121, 241, Int.MAX_VALUE)) {
            var writes = 0
            val restored = RetractionDistancePreference({ value }, { writes++ })
            assertEquals("stored $value", 120, restored.state.value)
            assertEquals(0, writes)
        }
        val wrongType = RetractionDistancePreference({ throw ClassCastException("not integer") }, { fail("must not rewrite") })
        assertEquals(120, wrongType.state.value)
    }

    @Test fun validSavedDistancesAreNotMigratedToTheNewDefault() {
        for (value in listOf(8, 40, 80, 120, 160, 240)) {
            val restored = RetractionDistancePreference({ value }, { fail("must not rewrite") })
            assertEquals(value, restored.state.value)
        }
    }

    @Test fun invalidDistanceCannotOverwriteAnExplicitSavedChoice() {
        var disk = 160
        val preference = RetractionDistancePreference({ disk }, { disk = it })
        for (invalid in listOf(0, 4, 81, 248)) {
            try { preference.set(invalid); fail("invalid distance accepted: $invalid") } catch (_: IllegalArgumentException) { }
            assertEquals(160, preference.state.value)
            assertEquals(160, disk)
        }
    }
}
