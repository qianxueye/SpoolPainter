package com.spoolpainter.app.data.remote.inventory

import com.google.gson.JsonParser
import com.spoolpainter.app.ui.screens.inventory.formBody
import org.junit.Assert.*
import org.junit.Test

class InventorySafetyTest {
    private fun json(value: String) = JsonParser.parseString(value).asJsonObject
    @Test fun `metadata edit never resubmits stale weight and retains extras`() {
        val old = json("""{"id":1,"location":"A","remaining_weight":900,"extra":{"tag":"\"original\""}}""")
        val fresh = json("""{"id":1,"location":"A","remaining_weight":700,"extra":{"tag":"\"new\""}}""")
        val patch = InventoryPatch.fromFresh(old, fresh, json("""{"location":"B"}"""))
        assertEquals("""{"location":"B"}""", patch.toString())
    }
    @Test fun `fresh concurrent metadata change is rejected`() {
        assertThrows(IllegalStateException::class.java) { InventoryPatch.fromFresh(json("""{"location":"A"}"""), json("""{"location":"C"}"""), json("""{"location":"B"}""")) }
    }
    @Test fun `per key extra merge preserves concurrent unrelated updates and explicit null`() {
        val old = json("""{"extra":{"tag":"\"old\"","other":"1"}}""")
        val fresh = json("""{"extra":{"tag":"\"old\"","other":"2"}}""")
        val patch = InventoryPatch.fromFresh(old, fresh, json("""{"extra":{"tag":null}}"""))
        assertEquals(json("""{"extra":{"tag":null}}"""), patch)
    }
    @Test fun `already applied field yields no patch`() {
        assertEquals(0, InventoryPatch.fromFresh(json("""{"location":"A"}"""), json("""{"location":"B"}"""), json("""{"location":"B"}""")).size())
    }
    @Test fun `UID index keeps duplicate 25 and 26 including archived spool`() {
        val records = listOf(
            json("""{"id":25,"extra":{"card_uids":"\"04A1B2C3D4E5A0,04A1B2C3D4E5F6,04a1b2c3d4e5f6\""}}"""),
            json("""{"id":26,"archived":true,"extra":{"card_uids":"\"04:a1:b2:c3:d4:e5:f6\""}}"""),
        )
        assertEquals(setOf(25,26), InventoryUids.index(records)["04A1B2C3D4E5F6"])
        assertEquals(setOf(25), InventoryUids.index(records)["04A1B2C3D4E5A0"])
    }
    @Test fun `empty UID is unbound and malformed encoded data blocks index`() {
        assertTrue(InventoryUids.decode(json("""{"id":1,"extra":{"card_uids":"\"\""}}""")).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { InventoryUids.index(listOf(json("""{"id":1,"extra":{"card_uids":"04A1B2C3D4E5F6"}}"""))) }
    }
    @Test fun `UID matching uses exact tokens not substrings`() {
        assertThrows(IllegalArgumentException::class.java) { InventoryUids.normalize("A1B2C3D4E5F6") }
        val record = json("""{"id":1,"extra":{"card_uids":"\" 04-a1-b2-c3-d4-e5-f6 \""}}""")
        assertEquals(listOf("04A1B2C3D4E5F6"), InventoryUids.decode(record))
    }
    @Test fun `editor preserves untouched values and only sends changed location`() {
        val old = json("""{"filament":{"id":5},"location":"A","remaining_weight":800}""")
        val body = formBody("spool", old, mapOf("filament_id" to "5", "location" to "B", "remaining_weight" to "800"), emptyMap(), emptySet())
        assertEquals(json("""{"location":"B"}"""), body)
    }
    @Test fun `editor clears optional field with null and encodes JSON text once`() {
        val old = json("""{"name":"Maker","comment":"Old","extra":{"pressure":"\"old\""}}""")
        val body = formBody("vendor", old, mapOf("name" to "Maker", "comment" to ""), mapOf("pressure" to "\"{\\\"x\\\":1}\""), emptySet())
        assertTrue(body["comment"].isJsonNull)
        assertEquals("\"{\\\"x\\\":1}\"", body.child("extra").text("pressure"))
    }
    @Test fun `create filament requires positive density and diameter`() {
        assertThrows(IllegalArgumentException::class.java) { formBody("filament", null, mapOf("density" to "0", "diameter" to "1.75"), emptyMap(), emptySet()) }
    }
    @Test fun `extra editor rejects unquoted text instead of relying on lenient Gson parsing`() {
        assertThrows(IllegalArgumentException::class.java) {
            formBody("vendor", json("""{"name":"Maker"}"""), mapOf("name" to "Maker"), mapOf("tag" to "unquoted"), emptySet())
        }
    }
    @Test fun `extra editor preserves all valid JSON value types including nested values`() {
        val values = listOf("\"text\"", "0", "-12.5e+2", "true", "false", "null", """{"name":"text","values":[1,false,null,{"value":-0.5}]}""", "[]")
        values.forEach { raw ->
            val body = formBody("vendor", json("""{"name":"Maker"}"""), mapOf("name" to "Maker"), mapOf("tag" to raw), emptySet())
            assertEquals(raw, body.child("extra").text("tag"))
        }
    }
    @Test fun `extra editor rejects invalid primitive tokens at every nesting level`() {
        listOf("01", "+1", "1.", ".5", "NaN", "Infinity", "TRUE", "[unquoted]", """{"value":unquoted}""").forEach { raw ->
            assertThrows("Must reject $raw", IllegalArgumentException::class.java) {
                formBody("vendor", json("""{"name":"Maker"}"""), mapOf("name" to "Maker"), mapOf("tag" to raw), emptySet())
            }
        }
    }
    @Test fun `editor never edits UID through generic extra map`() {
        val body = formBody("vendor", json("""{"name":"Maker"}"""), mapOf("name" to "Maker"), mapOf("card_uids" to "\"04A1B2C3D4E5F6\""), emptySet())
        assertEquals(0, body.size())
    }
}
