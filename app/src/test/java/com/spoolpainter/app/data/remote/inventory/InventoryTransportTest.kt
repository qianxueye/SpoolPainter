package com.spoolpainter.app.data.remote.inventory

import com.google.gson.JsonParser
import com.spoolpainter.app.data.local.Settings
import com.spoolpainter.app.data.local.SettingsRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class InventoryTransportTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: InventoryRepository
    private val settings = mockk<SettingsRepository>()
    private fun json(value: String) = JsonParser.parseString(value).asJsonObject
    private val base get() = server.url("/").toString().trimEnd('/')
    private val spool = """{"id":1,"filament":{"id":2},"spool_weight":200,"remaining_weight":800,"used_weight":200,"location":"A","extra":{"card_uids":"\"04A1B2C3D4E5A0\"","tag":"\"keep\""}}"""
    @Before fun setup() {
        server = MockWebServer().apply { start() }
        coEvery { settings.awaitSettings() } answers { Settings(url = base) }
        repository = InventoryRepository(settings, OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).build())
    }
    @After fun close() { server.shutdown() }
    private fun enqueue(value: String, code: Int = 200) { server.enqueue(MockResponse().setResponseCode(code).setBody(value).addHeader("Content-Type", "application/json")) }
    @Test fun `patch gets fresh spool then sends metadata only`() = runTest {
        enqueue(spool.replace("\"remaining_weight\":800", "\"remaining_weight\":650"))
        enqueue(spool.replace("\"location\":\"A\"", "\"location\":\"B\""))
        repository.save("spool", json(spool), json("""{"location":"B"}"""), base)
        assertEquals("GET", server.takeRequest().method)
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("/api/v1/spool/1", patch.path)
        assertEquals("""{"location":"B"}""", patch.body.readUtf8())
    }
    @Test fun `explicit null survives wire serialization`() = runTest {
        enqueue(spool); enqueue(spool)
        repository.save("spool", json(spool), json("""{"location":null,"extra":{"tag":null}}"""), base)
        server.takeRequest()
        assertEquals(json("""{"location":null,"extra":{"tag":null}}"""), json(server.takeRequest().body.readUtf8()))
    }
    @Test fun `gross measure and consumption use correct routes and bodies`() = runTest {
        enqueue(spool); enqueue(spool)
        repository.weight(json(spool), 950.0, true, base)
        server.takeRequest()
        val measure = server.takeRequest()
        assertEquals("PUT", measure.method); assertEquals("/api/v1/spool/1/measure", measure.path)
        assertEquals(950.0, json(measure.body.readUtf8()).number("weight")!!, 0.0)
        enqueue(spool); enqueue(spool)
        repository.weight(json(spool), 25.0, false, base)
        server.takeRequest()
        val use = server.takeRequest()
        assertEquals("/api/v1/spool/1/use", use.path)
        assertEquals(json("""{"use_weight":25.0}"""), json(use.body.readUtf8()))
    }
    @Test fun `missing tare or below tare prevents measurement write`() = runTest {
        enqueue(spool)
        try { repository.weight(json(spool), 100.0, true, base); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(1, server.requestCount)
    }
    @Test fun `binding sends only UID key and rechecks full archived index`() = runTest {
        enqueue("[$spool]"); enqueue(spool); enqueue(spool)
        enqueue("[${spool.replace("04A1B2C3D4E5A0", "04A1B2C3D4E5A0,04A1B2C3D4E5F6")}]")
        repository.bind(json(spool), "04A1B2C3D4E5F6", false, base)
        assertTrue(server.takeRequest().path!!.contains("allow_archived=true"))
        server.takeRequest()
        val patch = json(server.takeRequest().body.readUtf8())
        assertEquals(setOf("extra"), patch.keySet())
        assertEquals(setOf("card_uids"), patch.child("extra").keySet())
        assertEquals("\"04A1B2C3D4E5A0,04A1B2C3D4E5F6\"", patch.child("extra").text("card_uids"))
        assertTrue(server.takeRequest().path!!.contains("allow_archived=true"))
    }
    @Test fun `duplicate UID including archived associations causes no write`() = runTest {
        enqueue("""[{"id":25,"extra":{"card_uids":"\"04A1B2C3D4E5F6\""}},{"id":26,"archived":true,"extra":{"card_uids":"\"04A1B2C3D4E5F6\""}}]""")
        try { repository.bind(json(spool), "04A1B2C3D4E5F6", false, base); fail() } catch (_: IllegalStateException) { }
        assertEquals(1, server.requestCount)
    }
    @Test fun `delete preserves reference errors for operator`() = runTest {
        enqueue("""{"id":2,"name":"PLA"}""")
        enqueue("""{"detail":"Filament has spools"}""", 403)
        try { repository.delete("filament", json("""{"id":2,"name":"PLA"}"""), base); fail() }
        catch (e: InventoryFailure) { assertTrue(e.message!!.contains("403")); assertTrue(e.message!!.contains("Filament has spools")) }
        server.takeRequest(); assertEquals("DELETE", server.takeRequest().method)
    }
    @Test fun `timed out consumption is unknown and never retried`() = runTest {
        enqueue(spool)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        try { repository.weight(json(spool), 25.0, false, base); fail() }
        catch (e: InventoryFailure) { assertTrue(e.unknownOutcome) }
        assertEquals(2, server.requestCount)
    }
    @Test fun `server and gateway failures after consumption are unknown and never retried`() = runTest {
        for (code in listOf(500, 502, 503, 504)) {
            val before = server.requestCount
            enqueue(spool)
            enqueue("""{"detail":"response failed after possible commit"}""", code)
            try { repository.weight(json(spool), 25.0, false, base); fail() }
            catch (e: InventoryFailure) {
                assertTrue("HTTP $code must require readback", e.unknownOutcome)
                assertTrue(e.message!!.contains("HTTP $code"))
                assertTrue(e.message!!.contains("刷新核对"))
            }
            assertEquals(before + 2, server.requestCount)
        }
    }
    @Test fun `read server failure and write validation error remain confirmed failures`() = runTest {
        enqueue("""{"detail":"unavailable"}""", 503)
        try { repository.get("spool", 1); fail() }
        catch (e: InventoryFailure) { assertFalse(e.unknownOutcome) }
        enqueue(spool)
        enqueue("""{"detail":"invalid weight"}""", 422)
        try { repository.weight(json(spool), 25.0, false, base); fail() }
        catch (e: InventoryFailure) { assertFalse(e.unknownOutcome); assertTrue(e.message!!.contains("422")) }
        assertEquals(3, server.requestCount)
    }
    @Test fun `old snapshot provenance blocks print preflight and selection on changed server`() = runTest {
        try { repository.verifyServer("http://old.example"); fail() }
        catch (_: IllegalStateException) { }
        try { repository.get("spool", 1, "http://old.example"); fail() }
        catch (_: IllegalStateException) { }
        assertEquals(0, server.requestCount)
    }
    @Test fun `server changed since viewing rejects mutation before request`() = runTest {
        try { repository.save("spool", json(spool), json("""{"location":"B"}"""), "http://old.example"); fail() }
        catch (_: IllegalStateException) { }
        assertEquals(0, server.requestCount)
    }
}
