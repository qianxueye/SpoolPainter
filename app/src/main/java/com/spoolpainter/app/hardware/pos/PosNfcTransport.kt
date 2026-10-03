package com.spoolpainter.app.hardware.pos

import com.spoolpainter.app.hardware.nfc.NdefRecordView
import com.spoolpainter.app.hardware.nfc.RawTagRead

/** A real, scoped POS session. No android.nfc.Tag is synthesized. */
interface PosTagSession {
    suspend fun read(): RawTagRead
    suspend fun readRecords(): List<NdefRecordView>?
    suspend fun writeRecords(records: List<NdefRecordView>)
}

interface PosNfcTransport {
    fun isAvailable(): Boolean
    /** One armed scan: returns after one tag, timeout or failure, with balanced cleanup. */
    suspend fun runForeground(onTag: suspend (PosTagSession) -> Unit)
}

internal data class PosCard(val uid: ByteArray, val type: Int)

/** SDK calls are blocking Binder calls, invoked only on the reader's IO dispatcher. */
internal interface PosReaderSdk {
    fun openPicc(): Int
    fun closePicc(): Int
    fun openMifare(type: Int): Int
    fun closeMifare(): Int
    fun detect(): Int
    fun card(): PosCard
    fun readPages(page: Int): ByteArray
    fun writePage(page: Int, data: ByteArray)
}

internal class PosReaderException(message: String) : java.io.IOException(message)
internal class PosCleanupException(message: String, cause: Throwable) : java.io.IOException(message, cause)
