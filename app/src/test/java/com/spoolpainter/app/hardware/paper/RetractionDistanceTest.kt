package com.spoolpainter.app.hardware.paper

import org.junit.Assert.*
import org.junit.Test

class RetractionDistanceTest {
    @Test fun defaultAndEdgesUseExactOneDecimalCentimeters() {
        assertEquals(120, RetractionDistance().units)
        assertEquals("1.5", RetractionDistance().centimetersText)
        assertEquals("0.1", RetractionDistance(8).centimetersText)
        assertEquals("3.0", RetractionDistance(240).centimetersText)
        assertEquals(1.5, RetractionDistance().centimeters, 0.0)
    }
    @Test fun decimalParsingHasNoFloatingPointRoundingOrSilentClamping() {
        assertEquals(120, RetractionDistance.fromCentimeters(" 1.5 ").units)
        assertEquals(120, RetractionDistance.fromCentimeters("1.50").units)
        assertEquals(8, RetractionDistance.fromCentimeters("0.1").units)
        assertEquals(240, RetractionDistance.fromCentimeters("3.0").units)
        for (text in listOf("", "0", "0.09", "1.55", "3.1", "-1", "NaN", "Infinity", "1,5")) {
            assertTrue("must reject $text", runCatching { RetractionDistance.fromCentimeters(text) }.isFailure)
        }
    }
    @Test fun unsupportedRawUnitsCannotCreateAValue() {
        for (units in listOf(0, -8, 7, 9, 241, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertTrue(runCatching { RetractionDistance(units) }.isFailure)
        }
    }
}
