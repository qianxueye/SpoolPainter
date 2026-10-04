package com.spoolpainter.app.hardware.paper

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.TimeZone

class PaperProtocolTest {
    @Test fun defaultDistanceEncodesOnePointFiveCentimetersAsNegative120() {
        assertEquals(120, RetractionDistance.DEFAULT_UNITS)
        assertEquals("02000802334504FFFFFF88030C", PaperProtocol.retractRequest(2, RetractionDistance.DEFAULT_UNITS).hex())
        PaperProtocol.requireWhitelistedRequest(PaperProtocol.statusRequest(1))
        assertTrue(runCatching { PaperProtocol.successData(byteArrayOf(6), 2, 0x33) }.isFailure)
    }

    @Test fun allSupportedStepsHaveCorrectSignedPayloadAndChecksum() {
        for (units in 8..240 step 8) {
            val packet = PaperProtocol.retractRequest(2, units)
            assertEquals(-units, ByteBuffer.wrap(packet, 7, 4).int)
            assertEquals(packet.last().toInt() and 255, packet.drop(1).dropLast(1).fold(0) { xor, byte -> xor xor (byte.toInt() and 255) })
            PaperProtocol.requireWhitelistedRequest(packet)
        }
        // The formerly fixed 0.5 cm and 1 cm values remain valid choices.
        assertEquals("02000802334504FFFFFFD8035C", PaperProtocol.retractRequest(2, 40).hex())
        assertEquals("02000802334504FFFFFFB00334", PaperProtocol.retractRequest(2, 80).hex())
    }

    @Test fun invalidValuesAndMalformedPacketsNeverReachTheWhitelist() {
        for (units in listOf(Int.MIN_VALUE, -120, 0, 1, 7, 9, 239, 241, 248, Int.MAX_VALUE)) {
            assertTrue("must reject $units", runCatching { PaperProtocol.retractRequest(2, units) }.isFailure)
        }
        for (signed in listOf(Int.MIN_VALUE, -248, -9, 0, 8, 40, 240, Int.MAX_VALUE)) {
            val packet = PaperProtocol.retractRequest(2, 120)
            ByteBuffer.wrap(packet, 7, 4).putInt(signed)
            checksum(packet)
            assertTrue("must reject signed $signed", runCatching { PaperProtocol.requireWhitelistedRequest(packet) }.isFailure)
        }
        for (index in listOf(0, 1, 2, 4, 5, 6, 11, 12)) {
            val bad = PaperProtocol.retractRequest(2, 120).also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertTrue(runCatching { PaperProtocol.requireWhitelistedRequest(bad) }.isFailure)
        }
        assertTrue(runCatching { PaperProtocol.requireWhitelistedRequest(PaperProtocol.retractRequest(2, 120).dropLast(1).toByteArray()) }.isFailure)
    }

    @Test fun collectorStartUsesTwoSecondDeviceZoneLookback() {
        assertEquals("01-01 08:00:01.500", CaptureStartPolicy.lookback(3500, TimeZone.getTimeZone("GMT+08:00")))
    }

    private fun checksum(bytes: ByteArray) {
        bytes[bytes.lastIndex] = bytes.drop(1).dropLast(1).fold(0) { xor, byte -> xor xor (byte.toInt() and 255) }.toByte()
    }
    private fun ByteArray.hex() = joinToString("") { "%02X".format(it.toInt() and 255) }
}
