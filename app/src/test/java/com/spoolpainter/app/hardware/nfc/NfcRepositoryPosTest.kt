package com.spoolpainter.app.hardware.nfc

import com.spoolpainter.app.domain.primitives.CardUid
import com.spoolpainter.app.domain.primitives.NfcIntent
import com.spoolpainter.app.domain.primitives.NfcResult
import com.spoolpainter.app.domain.primitives.TagClassification
import com.spoolpainter.app.hardware.pos.PosNfcTransport
import com.spoolpainter.app.hardware.pos.PosTagSession
import com.spoolpainter.app.support.FakeSettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import io.mockk.mockk
import androidx.activity.ComponentActivity
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NfcRepositoryPosTest {
    private val transport = object : PosNfcTransport {
        override fun isAvailable() = true
        override suspend fun runForeground(onTag: suspend (PosTagSession) -> Unit) = Unit
    }
    @Test fun `POS availability arms with no Android NFC and feeds passive buffer`() = runTest {
        val wrapper = FakeNfcAdapterWrapper().apply { available = false }
        val repo = NfcRepository(wrapper, this, UnconfinedTestDispatcher(testScheduler), MutableClock(0), FakeSettingsRepository(), null, 5000, transport)
        val session = Session()
        repo.handlePosTag(session)
        assertEquals(NfcTestSupport.sampleUid(), repo.lastSeenTag.value?.uid)
        val consumed = repo.consumeLastSeen(NfcIntent.Read) as NfcResult.Success
        assertEquals(TagClassification.Blank, consumed.classification)
        assertNull(repo.consumeLastSeen(NfcIntent.Read))
    }
    @Test fun `armed POS write with matching expected UID verifies and yields same domain payload`() = runTest {
        val repo = NfcRepository(FakeNfcAdapterWrapper(), this, UnconfinedTestDispatcher(testScheduler), MutableClock(0), FakeSettingsRepository(), null, 5000, transport)
        val session = Session()
        val payload = NfcTestSupport.samplePayload()
        repo.arm(NfcIntent.Write(payload, expectedUid = NfcTestSupport.sampleUid()))
        repo.handlePosTag(session)
        assertEquals(1, session.writes)
        assertEquals(1, session.verifyReads)
        assertEquals(NfcResult.Success(NfcTestSupport.sampleUid(), TagClassification.OpenSpool(payload)), repo.state.value)
        assertEquals(NfcTestSupport.sampleUid(), repo.lastSeenTag.value?.uid)
        assertEquals(TagClassification.OpenSpool(payload), repo.lastSeenTag.value?.classification)
    }
    @Test fun `POS expected UID mismatch consumes intent without writing or verifying another tag`() = runTest {
        val repo = NfcRepository(FakeNfcAdapterWrapper(), this, UnconfinedTestDispatcher(testScheduler), MutableClock(0), FakeSettingsRepository(), null, 5000, transport)
        val expected = NfcTestSupport.sampleUid()
        val other = CardUid("04010203040506")
        val wrongTag = Session(uid = other)
        repo.arm(NfcIntent.Write(NfcTestSupport.samplePayload(), expectedUid = expected))
        repo.handlePosTag(wrongTag)
        val error = NfcResult.Error("UID mismatch: expected $expected, detected $other")
        assertEquals(error, repo.state.value)
        assertEquals(0, wrongTag.writes)
        assertEquals(0, wrongTag.verifyReads)
        assertNull(repo.lastSeenTag.value)
        // The rejected write cannot leak into a subsequent presentation without a new arm.
        val matchingTag = Session()
        repo.handlePosTag(matchingTag)
        assertEquals(0, matchingTag.writes)
        assertEquals(0, matchingTag.verifyReads)
        assertEquals(error, repo.state.value)
    }
    @Test fun `POS null expected UID keeps two-tag writes available`() = runTest {
        val repo = NfcRepository(FakeNfcAdapterWrapper(), this, UnconfinedTestDispatcher(testScheduler), MutableClock(0), FakeSettingsRepository(), null, 5000, transport)
        val other = CardUid("04010203040506")
        val tag = Session(uid = other)
        val payload = NfcTestSupport.samplePayload()
        repo.arm(NfcIntent.Write(payload, expectedUid = null))
        repo.handlePosTag(tag)
        assertEquals(1, tag.writes)
        assertEquals(1, tag.verifyReads)
        assertEquals(NfcResult.Success(other, TagClassification.OpenSpool(payload)), repo.state.value)
    }
    @Test fun `POS Classic UID remains bindable but never reaches writer`() = runTest {
        val repo = NfcRepository(FakeNfcAdapterWrapper(), this, UnconfinedTestDispatcher(testScheduler), MutableClock(0), FakeSettingsRepository(), null, 5000, transport)
        val session = Session(classic = true)
        repo.arm(NfcIntent.Read)
        repo.handlePosTag(session)
        assertTrue((repo.state.value as NfcResult.Success).classification is TagClassification.Vendor)
        repo.arm(NfcIntent.Write(NfcTestSupport.samplePayload()))
        repo.handlePosTag(session)
        assertTrue((repo.state.value as NfcResult.Error).reason.contains("vendor-tag protected"))
        assertEquals(NfcTestSupport.sampleUid(), repo.lastSeenTag.value?.uid)
        assertEquals(0, session.writes)
    }
    @Test fun `POS verify mismatch cannot become success`() = runTest {
        val repo = NfcRepository(FakeNfcAdapterWrapper(), this, UnconfinedTestDispatcher(testScheduler), MutableClock(0), FakeSettingsRepository(), null, 5000, transport)
        val session = Session(ignoreWrites = true)
        repo.arm(NfcIntent.Write(NfcTestSupport.samplePayload()))
        repo.handlePosTag(session)
        assertTrue((repo.state.value as NfcResult.Error).reason.contains("verify mismatch"))
        assertEquals(NfcTestSupport.sampleUid(), repo.lastSeenTag.value?.uid)
    }
    @Test fun `POS remains quiet when attached and pause cancels an armed scan`() = runTest {
        var scans = 0
        var cleaned = 0
        val blocking = object : PosNfcTransport {
            override fun isAvailable() = true
            override suspend fun runForeground(onTag: suspend (PosTagSession) -> Unit) {
                scans++
                try { awaitCancellation() } finally { cleaned++ }
            }
        }
        val repo = NfcRepository(FakeNfcAdapterWrapper(), this, UnconfinedTestDispatcher(testScheduler), MutableClock(0), FakeSettingsRepository(), null, 5000, blocking)
        val activity = mockk<ComponentActivity>(relaxed = true)
        repo.attach(activity)
        assertEquals(0, scans)
        repo.arm(NfcIntent.Read)
        assertEquals(1, scans)
        repo.detach()
        repo.attach(activity)
        assertEquals(1, scans)
        assertEquals(1, cleaned)
        repo.detach()
    }
    @Test fun `disarm waits for POS hardware cleanup before returning`() = runTest {
        assertDisarmWaitsForCleanup(detachFirst = false)
    }
    @Test fun `disarm also waits for cleanup of a scan already cancelled by pause`() = runTest {
        assertDisarmWaitsForCleanup(detachFirst = true)
    }
    @Test fun `scan cannot rearm or resume while disarm drains POS cleanup`() = runTest {
        assertDisarmWaitsForCleanup(detachFirst = true, rearmDuringCleanup = true)
    }
    @Test fun `paper hardware pause excludes reader throughout operation and releases afterwards`() = runTest {
        var scans = 0
        val blocking = object : PosNfcTransport {
            override fun isAvailable() = true
            override suspend fun runForeground(onTag: suspend (PosTagSession) -> Unit) {
                scans++
                awaitCancellation()
            }
        }
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = NfcRepository(FakeNfcAdapterWrapper(), this, dispatcher, MutableClock(0), FakeSettingsRepository(), null, 5000, blocking)
        val activity = mockk<ComponentActivity>(relaxed = true)
        repo.attach(activity)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val paper = async(dispatcher) {
            repo.withPosHardwarePaused { entered.complete(Unit); release.await() }
        }
        entered.await()
        repo.arm(NfcIntent.Read)
        repo.detach()
        repo.attach(activity)
        repo.arm(NfcIntent.Read)
        assertEquals(0, scans)
        assertEquals(NfcResult.Idle, repo.state.value)
        release.complete(Unit)
        paper.await()
        repo.arm(NfcIntent.Read)
        assertEquals(1, scans)
        repo.disarm()
    }
    @Test fun `failed paper operation releases POS reader pause without restarting a scan`() = runTest {
        val repo = NfcRepository(FakeNfcAdapterWrapper(), this, UnconfinedTestDispatcher(testScheduler), MutableClock(0), FakeSettingsRepository(), null, 5000, transport)
        assertTrue(runCatching { repo.withPosHardwarePaused { error("paper failure") } }.isFailure)
        assertEquals(NfcResult.Idle, repo.state.value)
        repo.arm(NfcIntent.Read)
        assertEquals(NfcResult.Reading, repo.state.value)
    }
    private suspend fun kotlinx.coroutines.test.TestScope.assertDisarmWaitsForCleanup(detachFirst: Boolean, rearmDuringCleanup: Boolean = false) {
        val cleanupStarted = CompletableDeferred<Unit>()
        val allowCleanup = CompletableDeferred<Unit>()
        var cleaned = false
        var scans = 0
        val blocking = object : PosNfcTransport {
            override fun isAvailable() = true
            override suspend fun runForeground(onTag: suspend (PosTagSession) -> Unit) {
                scans++
                try { awaitCancellation() }
                finally {
                    withContext(NonCancellable) {
                        cleanupStarted.complete(Unit)
                        allowCleanup.await()
                        cleaned = true
                    }
                }
            }
        }
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = NfcRepository(FakeNfcAdapterWrapper(), this, dispatcher, MutableClock(0), FakeSettingsRepository(), null, 5000, blocking)
        repo.attach(mockk<ComponentActivity>(relaxed = true))
        repo.arm(NfcIntent.Read)
        if (detachFirst) repo.detach()
        val disarm = async(dispatcher) { repo.disarm() }
        cleanupStarted.await()
        assertFalse(disarm.isCompleted)
        assertFalse(cleaned)
        if (rearmDuringCleanup) {
            repo.arm(NfcIntent.Read)
            repo.attach(mockk<ComponentActivity>(relaxed = true))
            assertEquals(1, scans)
        }
        allowCleanup.complete(Unit)
        disarm.await()
        assertTrue(cleaned)
        assertEquals(NfcResult.Idle, repo.state.value)
        assertEquals(1, scans)
    }
    private class Session(
        val classic: Boolean = false,
        val ignoreWrites: Boolean = false,
        val uid: CardUid = NfcTestSupport.sampleUid(),
    ) : PosTagSession {
        var records: List<NdefRecordView>? = null
        var writes = 0
        var verifyReads = 0
        override suspend fun read() = RawTagRead(uid, records,
            listOf(if (classic) "android.nfc.tech.MifareClassic" else "android.nfc.tech.Ndef"))
        override suspend fun readRecords(): List<NdefRecordView>? { verifyReads++; return records }
        override suspend fun writeRecords(records: List<NdefRecordView>) { writes++; if (!ignoreWrites) this.records = records }
    }
}
