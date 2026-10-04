package com.spoolpainter.app.hardware.paper

import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class PaperProtocolTest {
    @Test fun onlyFixedRetractAndBareStatusAreEncoded() {
        assertEquals("02000802334504FFFFFFD8035C", PaperProtocol.retractRequest(2).joinToString("") { "%02X".format(it.toInt() and 255) })
        PaperProtocol.requireWhitelistedRequest(PaperProtocol.statusRequest(1))
        PaperProtocol.requireWhitelistedRequest(PaperProtocol.retractRequest(2))
        val forward = byteArrayOf(2, 0, 8, 2, 0x33, 0x45, 4, 0, 0, 0, 0x28, 3, 0x53)
        assertTrue(runCatching { PaperProtocol.requireWhitelistedRequest(forward) }.isFailure)
        assertTrue(runCatching { PaperProtocol.successData(byteArrayOf(6), 2, 0x33) }.isFailure)
    }
    @Test fun collectorStartUsesTwoSecondDeviceZoneLookback() {
        assertEquals("01-01 08:00:01.500", CaptureStartPolicy.lookback(3500, TimeZone.getTimeZone("GMT+08:00")))
    }
}
