package com.spoolpainter.app.data.remote.inventory

import com.spoolpainter.app.domain.primitives.SpoolQrPayload
import org.junit.Assert.*
import org.junit.Test

class SpoolQrPayloadTest {
    private val server = "http://inventory.example:7912"

    @Test fun `standard spool scheme resolves positive identifier`() {
        assertEquals(123, SpoolQrPayload.spoolId("WEB+SPOOLMAN:S-123", server))
        assertEquals(25, SpoolQrPayload.spoolId(" web+spoolman:s-25 \n", server))
    }
    @Test fun `verified spool URL resolves only under configured server base`() {
        assertEquals(123, SpoolQrPayload.spoolId("$server/spool/show/123", server))
        assertEquals(123, SpoolQrPayload.spoolId("$server/spool/show/123/", "$server/"))
        assertEquals(1, SpoolQrPayload.spoolId("https://INVENTORY.example:443/stock/spool/show/1", "https://inventory.example/stock/"))
    }
    @Test fun `verified root selection URL accepts only a single positive spool selection`() {
        assertEquals(123, SpoolQrPayload.spoolId("$server/?sel=spool:123", server))
        assertEquals(123, SpoolQrPayload.spoolId("$server?sel=spool%3A123", server))
        assertEquals(123, SpoolQrPayload.spoolId("$server/stock/?sel=spool:123", "$server/stock"))
        listOf("$server/?sel=filament:123", "$server/?sel=spool:0", "$server/?sel=spool:1&other=2", "$server/?sel=spool:1&sel=spool:2", "$server/spool/show/1?sel=spool:2", "http://other.example:7912/?sel=spool:1").forEach { payload ->
            assertThrows(payload, IllegalArgumentException::class.java) { SpoolQrPayload.spoolId(payload, server) }
        }
    }
    @Test fun `foreign origin credentials and misleading path cannot redirect inventory selection`() {
        listOf(
            "http://other.example:7912/spool/show/1",
            "https://inventory.example:7912/spool/show/1",
            "http://inventory.example:80/spool/show/1",
            "http://inventory.example.evil:7912/spool/show/1",
            "http://user@inventory.example:7912/spool/show/1",
            "$server/other/spool/show/1",
            "$server/spool/show/%31",
            "$server/spool/show/1?server=other",
            "$server/spool/show/1#other",
            "$server/spool/show/../1",
        ).forEach { payload -> assertThrows(payload, IllegalArgumentException::class.java) { SpoolQrPayload.spoolId(payload, server) } }
    }
    @Test fun `filament QR invalid IDs and arbitrary content are rejected`() {
        listOf("WEB+SPOOLMAN:F-1", "WEB+SPOOLMAN:S-0", "WEB+SPOOLMAN:S--1", "WEB+SPOOLMAN:S-2147483648", "WEB+SPOOLMAN:S-1/2", "$server/filament/show/1", "$server/spool/show/0", "hello", "javascript:alert(1)").forEach { payload ->
            assertThrows(payload, IllegalArgumentException::class.java) { SpoolQrPayload.spoolId(payload, server) }
        }
    }
}
