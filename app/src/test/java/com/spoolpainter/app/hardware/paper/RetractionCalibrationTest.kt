package com.spoolpainter.app.hardware.paper

import org.junit.Assert.*
import org.junit.Test

class RetractionCalibrationTest {
    @Test fun ownerMeasuredHalfTravelGetsDoubleCountsWithoutChangingDistance() {
        for ((cm, raw) in listOf("1.0" to 160, "1.5" to 240, "2.0" to 320)) {
            val motion = RetractionMotionRequest(RetractionDistance.fromCentimeters(cm))
            assertEquals(cm, motion.distance.centimetersText)
            assertEquals(raw, motion.rawUnits)
            PaperProtocol.requireWhitelistedRequest(PaperProtocol.retractRequest(2, motion.rawUnits))
        }
    }
    @Test fun everySupportedDistanceAndCalibrationStaysBoundedOnCompleteElectricalCycles() {
        for (units in 8..240 step 8) for (percent in 50..300) {
            val motion = RetractionMotionRequest(RetractionDistance(units), RetractionCalibration(percent))
            assertTrue(motion.rawUnits in 2..720)
            assertEquals(0, motion.rawUnits % 2)
            assertTrue(kotlin.math.abs(motion.rawUnits - units * percent / 100.0) <= 1.0)
        }
    }
    @Test fun calibrationParsingIsExactAndDoesNotSilentlyClamp() {
        assertEquals(200, RetractionCalibration.parse("2.00").percent)
        assertEquals("2.03", RetractionCalibration.parse(" 2.03 ").text)
        for (text in listOf("0.49", "3.01", "2.005", "NaN", "Infinity", "2,0", "")) {
            assertTrue(runCatching { RetractionCalibration.parse(text) }.isFailure)
        }
    }
}
