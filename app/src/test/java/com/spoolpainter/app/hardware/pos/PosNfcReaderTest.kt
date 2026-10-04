package com.spoolpainter.app.hardware.pos

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PosNfcReaderTest {
    @Test fun `paired opens close in reverse order on cancellation`() = runTest {
        val sdk = FakePosSdk()
        val reader = PosNfcReader({ true }, { sdk }, StandardTestDispatcher(testScheduler))
        val job = launch { reader.withReaders(sdk, 1) { awaitCancellation() } }
        runCurrent()
        job.cancel()
        runCurrent()
        assertEquals(listOf("picc+", "mifare+1", "mifare-", "picc-"), sdk.calls)
    }
    @Test fun `failed or thrown PICC acquisition quarantines subsequent scans without closing an unowned lease`() = runTest {
        for (throws in listOf(false, true)) {
            val sdk = FakePosSdk().apply {
                if (throws) openPiccFailure = IllegalStateException("Binder failure after dispatch")
                else openPiccCode = -1
            }
            val reader = PosNfcReader({ true }, { sdk }, StandardTestDispatcher(testScheduler))
            try { reader.runForeground { fail("must not deliver a tag") }; fail("must fail acquisition") }
            catch (e: PosCleanupException) { assertTrue(e.message!!.contains("recover the device")) }
            // Even a subsequent apparently working SDK cannot clear unknown board state.
            sdk.openPiccCode = 0
            sdk.openPiccFailure = null
            try { reader.runForeground { fail("quarantined") }; fail("must remain quarantined") }
            catch (e: PosCleanupException) { assertTrue(e.message!!.contains("restart the app")) }
            try { reader.withReaders(sdk, 1) { fail("quarantined acquisition") }; fail("must remain quarantined") }
            catch (_: PosCleanupException) { }
            assertEquals(listOf("picc+"), sdk.calls)
        }
    }
    @Test fun `inner open failure still closes outer reader`() = runTest {
        val sdk = FakePosSdk().apply { openMifareCode = -1 }
        val reader = PosNfcReader({ true }, { sdk }, StandardTestDispatcher(testScheduler))
        try { reader.withReaders(sdk, 2) { fail("must not execute") }; fail("must throw") }
        catch (_: PosReaderException) { }
        assertEquals(listOf("picc+", "mifare+2", "picc-"), sdk.calls)
    }
    @Test fun `inner close failure never skips outer cleanup`() = runTest {
        val sdk = FakePosSdk().apply { closeMifareCode = -1 }
        val reader = PosNfcReader({ true }, { sdk }, StandardTestDispatcher(testScheduler))
        try { reader.withReaders(sdk, 1) { Unit }; fail("must throw") }
        catch (_: PosCleanupException) { }
        assertEquals(listOf("picc+", "mifare+1", "mifare-", "picc-"), sdk.calls)
    }
    @Test fun `one armed scan emits once without repeated PICC toggles`() = runTest {
        val sdk = FakePosSdk()
        val reader = PosNfcReader({ true }, { sdk }, StandardTestDispatcher(testScheduler))
        var count = 0
        reader.runForeground { count++ }
        assertEquals(1, count)
        assertEquals(listOf("picc+", "mifare+1", "mifare-", "picc-"), sdk.calls)
        reader.runForeground { count++ }
        assertEquals(2, count)
    }
    @Test fun `absent tag polling keeps a single outer PICC lease`() = runTest {
        val sdk = FakePosSdk().apply { present = false }
        val reader = PosNfcReader({ true }, { sdk }, StandardTestDispatcher(testScheduler))
        val job = launch { reader.runForeground { fail("absent") } }
        advanceTimeBy(2200); runCurrent()
        assertEquals(1, sdk.calls.count { it == "picc+" })
        assertEquals(0, sdk.calls.count { it == "picc-" })
        job.cancel(); runCurrent()
        assertEquals("picc-", sdk.calls.last())
    }
    @Test fun `concurrent foreground collectors cannot overlap reader leases`() = runTest {
        val sdk = FakePosSdk()
        val reader = PosNfcReader({ true }, { sdk }, StandardTestDispatcher(testScheduler))
        val a = launch { reader.runForeground { awaitCancellation() } }
        val b = launch { reader.runForeground { awaitCancellation() } }
        runCurrent()
        assertEquals(1, sdk.calls.count { it == "picc+" })
        a.cancel(); runCurrent()
        assertEquals(listOf("mifare-", "picc-", "picc+", "mifare+1"), sdk.calls.takeLast(4))
        b.cancel(); runCurrent()
    }
}

internal class FakePosSdk : PosReaderSdk {
    val calls = mutableListOf<String>()
    val writes = mutableListOf<Pair<Int, ByteArray>>()
    val memory = ByteArray(231 * 4)
    val uid = byteArrayOf(4, 1, 2, 3, 4, 5, 6)
    var present = true
    var openPiccCode = 0
    var openPiccFailure: Throwable? = null
    var openMifareCode = 0
    var closeMifareCode = 0
    var cardType = 1
    var corruptReadback = false
    var cancelAtPage: Int? = null
    init {
        uid.copyInto(memory, 0, 0, 3)
        uid.copyInto(memory, 4, 3, 7)
        byteArrayOf(0xe1.toByte(), 0x10, 0x6d, 0).copyInto(memory, 12)
        byteArrayOf(3, 0, 0xfe.toByte(), 0).copyInto(memory, 16)
        memory[227 * 4 + 3] = 0xff.toByte()
    }
    override fun openPicc(): Int {
        calls += "picc+"
        openPiccFailure?.let { throw it }
        return openPiccCode
    }
    override fun closePicc(): Int { calls += "picc-"; return 0 }
    override fun openMifare(type: Int): Int { calls += "mifare+$type"; return openMifareCode }
    override fun closeMifare(): Int { calls += "mifare-"; return closeMifareCode }
    override fun detect() = if (present) 0 else 0xf37d
    override fun card() = PosCard(uid.copyOf(), cardType)
    override fun readPages(page: Int) = memory.copyOfRange(page * 4, page * 4 + 16)
    override fun writePage(page: Int, data: ByteArray) {
        if (page == cancelAtPage) throw CancellationException("removed from foreground")
        writes += page to data.copyOf()
        data.copyInto(memory, page * 4)
        if (corruptReadback) memory[page * 4] = 0
    }
}
