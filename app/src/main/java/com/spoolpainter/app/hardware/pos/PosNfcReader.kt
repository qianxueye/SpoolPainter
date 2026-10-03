package com.spoolpainter.app.hardware.pos

import com.spoolpainter.app.domain.primitives.CardUid
import com.spoolpainter.app.hardware.nfc.NdefRecordView
import com.spoolpainter.app.hardware.nfc.RawTagRead
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class PosNfcReader(
    private val available: () -> Boolean,
    private val sdkFactory: () -> PosReaderSdk,
    private val dispatcher: CoroutineDispatcher,
) : PosNfcTransport {
    // One lease covers open through final close, including across pause/resume jobs.
    private val readerLease = Mutex()
    private var readerFailure: Throwable? = null
    override fun isAvailable() = available()

    override suspend fun runForeground(onTag: suspend (PosTagSession) -> Unit) = withContext(dispatcher) {
        readerLease.withLock {
            checkReaderState()
            val sdk = sdkFactory()
            // PICC stays open for the entire armed scan. Inner type changes cannot toggle
            // the shared RF hardware or the board's touchscreen compatibility hook.
            withPicc(sdk) {
                val finished = withTimeoutOrNull(20_000) {
                    var delivered = false
                    while (!delivered) {
                        for (type in listOf(1, 2)) {
                            if (delivered) break
                            withMifare(sdk, type) {
                                currentCoroutineContext().ensureActive()
                                val code = sdk.detect()
                                currentCoroutineContext().ensureActive()
                                if ((code and 0xffff) == 0xf37d) return@withMifare
                                checkCode("detect", code)
                                val session = SdkTagSession(sdk, sdk.card())
                                try {
                                    onTag(session)
                                    delivered = true
                                } finally {
                                    session.expire()
                                }
                            }
                            if (!delivered) delay(250)
                        }
                    }
                    true
                }
                if (finished != true) throw PosReaderException("POS NFC scan timed out; present the tag again")
            }
            Unit
        }
    }

    private fun checkReaderState() {
        readerFailure?.let {
            throw PosCleanupException("POS reader state is uncertain; recover the device and restart the app before scanning", it)
        }
    }

    private suspend fun <T> withPicc(sdk: PosReaderSdk, action: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        checkReaderState()
        try {
            // The service asserts its touchscreen compatibility hook before hardware open.
            // Failure can roll back the refcount without restoring that hook. We do not own
            // a proven lease here, so closing blindly could disturb another SDK client.
            checkCode("PICC open", sdk.openPicc())
        } catch (t: Throwable) {
            readerFailure = t
            throw PosCleanupException(
                "POS PICC acquisition failed; recover the device and restart the app before scanning (${t.message})",
                t,
            )
        }
        try {
            currentCoroutineContext().ensureActive()
            return action()
        } finally {
            withContext(NonCancellable) {
                try { checkCode("PICC close", sdk.closePicc()) }
                catch (t: Throwable) {
                    readerFailure = t
                    throw PosCleanupException("POS PICC cleanup failed", t)
                }
            }
        }
    }

    private suspend fun <T> withMifare(sdk: PosReaderSdk, type: Int, action: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        checkCode("Mifare open", sdk.openMifare(type))
        try {
            currentCoroutineContext().ensureActive()
            return action()
        } finally {
            withContext(NonCancellable) {
                try { checkCode("Mifare close", sdk.closeMifare()) }
                catch (t: Throwable) {
                    readerFailure = t
                    throw PosCleanupException("POS Mifare cleanup failed", t)
                }
            }
        }
    }

    internal suspend fun <T> withReaders(sdk: PosReaderSdk, type: Int, action: suspend () -> T): T =
        withPicc(sdk) { withMifare(sdk, type, action) }

}

internal class SdkTagSession(private val sdk: PosReaderSdk, private val card: PosCard) : PosTagSession {
    private var live = true
    private val memory = Ntag216Memory(sdk, card.uid)
    fun expire() { live = false }
    private suspend fun checkLive() {
        check(live) { "POS tag session has ended; present the tag again" }
        currentCoroutineContext().ensureActive()
    }
    override suspend fun read(): RawTagRead {
        checkLive()
        val uid = CardUid.fromBytes(card.uid)
        return if (card.type == 1) {
            RawTagRead(uid, memory.readRecords(), listOf("android.nfc.tech.NfcA", "android.nfc.tech.MifareUltralight", "android.nfc.tech.Ndef"))
        } else {
            // UID-only: no authentication, sector reads or vendor encrypted decoding.
            RawTagRead(uid, null, listOf("android.nfc.tech.MifareClassic"))
        }
    }
    override suspend fun readRecords(): List<NdefRecordView>? {
        checkLive()
        return if (card.type == 1) memory.readRecords() else null
    }
    override suspend fun writeRecords(records: List<NdefRecordView>) {
        checkLive()
        if (card.type != 1) throw PosReaderException("vendor-tag protected: UID-only POS tag")
        memory.writeRecords(records)
    }
}
