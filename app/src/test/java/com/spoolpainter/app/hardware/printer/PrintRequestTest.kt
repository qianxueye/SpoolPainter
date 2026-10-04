package com.spoolpainter.app.hardware.printer

import org.junit.Assert.*
import org.junit.Test

class PrintRequestTest {
    private val label = SpoolLabel(123, "V", "PLA", "#FFFFFF", 12.5, "Shelf")
    @Test fun productNameIsShownSeparatelyFromMaterialType() {
        val request = PrintRequest(label.copy(material = "PETG", name = "PETG Pro"), "https://example.org")
        assertTrue(request.textLines().contains("名称：PETG Pro"))
        assertTrue(request.textLines().contains("材料：PETG"))
    }
    @Test fun missingProductNameFallsBackToMaterial() {
        assertTrue(PrintRequest(label, "https://example.org").textLines().contains("名称：PLA"))
    }
    @Test fun preservesServerBasePathAndPort() {
        assertEquals("http://192.168.45.2:7912/spool/show/123", PrintRequest(label, "http://192.168.45.2:7912/").webUrl())
        assertEquals("https://example.org/proxy/spoolman/spool/show/123", PrintRequest(label, "https://example.org/proxy/spoolman///").webUrl())
        assertEquals("https://example.org/my%20stock/spool/show/123", PrintRequest(label, "https://example.org/my%20stock").webUrl())
    }
    @Test fun interoperableAndWebCodesAreIndependentQrPayloads() {
        assertEquals(listOf("https://example.org/spool/show/123", "WEB+SPOOLMAN:S-123"), PrintRequest(label, "https://example.org", LabelQrMode.BOTH).qrPayloads())
    }
    @Test fun invalidIdAndAmbiguousServerAreRejected() {
        for (server in listOf("file:///tmp/data", "https://user:secret@example.org", "https://example.org/?token=secret", "https://example.org/#tab", "example.org")) {
            assertTrue(runCatching { PrintRequest(label, server).webUrl() }.isFailure)
        }
        assertTrue(runCatching { PrintRequest(label.copy(id = 0), "https://example.org").webUrl() }.isFailure)
    }
    @Test fun defaultTailAndAllConfiguredDistancesMatchDeviceCalibration() {
        assertEquals(PaperTail.ONE_AND_HALF_CM, PaperTail.fromDots(-1))
        assertEquals(listOf(80, 120, 160, 200), PaperTail.entries.map { it.dots })
    }
}
