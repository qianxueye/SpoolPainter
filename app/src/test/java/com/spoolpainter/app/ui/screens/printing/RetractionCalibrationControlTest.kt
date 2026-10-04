package com.spoolpainter.app.ui.screens.printing

import org.junit.Assert.*
import org.junit.Test

class RetractionCalibrationControlTest {
    @Test fun upgradeAddsCorrectionWithoutRewritingExistingDistanceSwitchOrTail() {
        val saved = mutableMapOf("retract_units" to 120, "tail_dots" to 160, "retract_before_print" to 1)
        val calibration = RetractionCalibrationPreference({ saved["retract_calibration_percent"] ?: 200 }, { saved["retract_calibration_percent"] = it })
        assertEquals(200, calibration.state.value)
        assertEquals(3, saved.size)
        calibration.set(203)
        assertEquals(120, saved["retract_units"])
        assertEquals(160, saved["tail_dots"])
        assertEquals(1, saved["retract_before_print"])
        assertEquals(203, RetractionCalibrationPreference({ saved.getValue("retract_calibration_percent") }, {}).state.value)
    }
    @Test fun corruptCalibrationFallsBackAndInvalidSaveDoesNotOverwriteLastValue() {
        assertEquals(200, RetractionCalibrationPreference({ -1 }, { fail("must not write during read") }).state.value)
        var saved = 200
        val calibration = RetractionCalibrationPreference({ saved }, { saved = it })
        assertTrue(runCatching { calibration.set(301) }.isFailure)
        assertEquals(200, saved)
        assertEquals(200, calibration.state.value)
    }
}
