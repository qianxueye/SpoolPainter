package com.spoolpainter.app.hardware.pos

import com.spoolpainter.app.hardware.nfc.NdefRecordView
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Conservative NTAG216 Type-2 mapping. See NXP NTAG213_215_216, sections 8.5/8.8.
 * Only CC E1 10 6D xx is accepted; its 872 bytes end at page 221, before all locks/config.
 * Unsupported layouts are readable where possible, but never reformatted speculatively.
 */
internal class Ntag216Memory(private val sdk: PosReaderSdk, private val uid: ByteArray) {
    private suspend fun pages(page: Int): ByteArray {
        currentCoroutineContext().ensureActive()
        val result = sdk.readPages(page)
        currentCoroutineContext().ensureActive()
        if (result.size != 16) throw PosReaderException("Expected four NTAG pages")
        return result
    }

    private suspend fun header(writing: Boolean): ByteArray {
        val h = pages(0)
        val memoryUid = byteArrayOf(h[0], h[1], h[2], h[4], h[5], h[6], h[7])
        if (uid.size != 7 || !uid.contentEquals(memoryUid) || h[0].u() != 4) {
            throw PosReaderException("Tag identity changed or unsupported NTAG manufacturer")
        }
        if (h[12].u() != 0xe1 || h[13].u() != 0x10 || h[14].u() != 0x6d || h[15].u() ushr 4 != 0) {
            throw PosReaderException("Unsupported NTAG216 capability container; no write attempted")
        }
        if (writing) {
            if (h[15].u() != 0 || h[10].u() != 0 || h[11].u() != 0) {
                throw PosReaderException("NTAG is read-only or statically locked")
            }
            // Verify address units before relying on the SDK's page-number write API.
            if (!pages(1).copyOfRange(0, 12).contentEquals(h.copyOfRange(4, 16))) {
                throw PosReaderException("POS SDK page addressing could not be verified")
            }
            val config = pages(226)
            if (config.take(3).any { it.u() != 0 }) throw PosReaderException("NTAG dynamic locks are set")
            if (config[7].u() != 0xff) throw PosReaderException("NTAG password protection is configured")
            if (config[4].u() and 0xc0 != 0) throw PosReaderException("NTAG UID/counter mirror is enabled")
        }
        return h
    }

    private suspend fun userMemory(): ByteArray {
        val bytes = ByteArray(CAPACITY)
        for (offset in bytes.indices step 16) {
            val data = pages(4 + offset / 4)
            data.copyInto(bytes, offset, 0, minOf(16, bytes.size - offset))
        }
        return bytes
    }

    suspend fun readRecords(): List<NdefRecordView>? {
        header(false)
        // Fetch only the TLV header and declared NDEF payload. Reading unused pages
        // prolongs the RF lease and can fail after a complete empty/short tag was read.
        val chunks = mutableMapOf<Int, ByteArray>()
        suspend fun byteAt(index: Int): Int {
            if (index !in 0 until CAPACITY) throw PosReaderException("Truncated Type-2 TLV")
            val chunk = index / 16
            val data = chunks[chunk] ?: pages(4 + chunk * 4).also { chunks[chunk] = it }
            return data[index % 16].u()
        }
        var offset = 0
        while (offset < CAPACITY) {
            when (val type = byteAt(offset++)) {
                0 -> continue
                0xfe -> return null
                else -> {
                    var length = byteAt(offset++)
                    if (length == 255) {
                        length = (byteAt(offset++) shl 8) or byteAt(offset++)
                    }
                    if (offset + length > CAPACITY) throw PosReaderException("NDEF exceeds capability container")
                    if (type == 3) {
                        if (length == 0) return null
                        val message = ByteArray(length)
                        for (index in message.indices) message[index] = byteAt(offset + index).toByte()
                        return PosNdefCodec.decode(message)
                    }
                    offset += length
                }
            }
        }
        return null
    }

    suspend fun writeRecords(records: List<NdefRecordView>) {
        header(true)
        val existing = userMemory()
        // Do not overwrite control/proprietary TLVs or guess reserved memory maps.
        if (existing[0].u() != 3 && existing.any { it.u() != 0 }) {
            throw PosReaderException("Unsupported Type-2 TLV layout; existing data preserved")
        }
        if (existing[0].u() == 3) validateExistingTail(existing)
        val message = PosNdefCodec.encode(records)
        val prefix = if (message.size < 255) byteArrayOf(3, message.size.toByte())
            else byteArrayOf(3, 0xff.toByte(), (message.size ushr 8).toByte(), message.size.toByte())
        val encoded = prefix + message + byteArrayOf(0xfe.toByte())
        if (encoded.size > CAPACITY) throw PosReaderException("NDEF exceeds NTAG216 capability container")
        val pageCount = (encoded.size + 3) / 4
        val final = existing.copyOf(pageCount * 4)
        encoded.copyInto(final)
        suspend fun writeChecked(page: Int, data: ByteArray) {
            currentCoroutineContext().ensureActive()
            // Prevent a tag replacement during a long transfer from being written.
            header(false)
            require(page in 4..221 && data.size == 4)
            sdk.writePage(page, data)
            currentCoroutineContext().ensureActive()
            if (!pages(page).copyOf(4).contentEquals(data)) throw PosReaderException("verification failed: NTAG page $page readback mismatch")
        }
        // Commit the NDEF length last in one page write. An interrupted transfer stays empty.
        writeChecked(4, byteArrayOf(3, 0, 0xfe.toByte(), 0))
        for (index in 1 until pageCount) writeChecked(4 + index, final.copyOfRange(index * 4, index * 4 + 4))
        writeChecked(4, final.copyOfRange(0, 4))
        if (readRecords() != records) throw PosReaderException("verify mismatch: NDEF readback")
    }

    private fun validateExistingTail(bytes: ByteArray) {
        var offset = 2
        var length = bytes[1].u()
        if (length == 255) { length = (bytes[2].u() shl 8) or bytes[3].u(); offset = 4 }
        if (offset + length > bytes.size) throw PosReaderException("Existing NDEF length is invalid")
        offset += length
        while (offset < bytes.size && bytes[offset].u() == 0) offset++
        if (offset < bytes.size && bytes[offset].u() != 0xfe) {
            throw PosReaderException("Additional Type-2 TLVs are present; existing data preserved")
        }
    }

    companion object { private const val CAPACITY = 0x6d * 8 }
}

internal fun Byte.u() = toInt() and 0xff

/** Platform-independent NDEF codec; complete unchunked records only. */
internal object PosNdefCodec {
    fun encode(records: List<NdefRecordView>): ByteArray {
        require(records.isNotEmpty()) { "An NDEF message must have a record" }
        val out = java.io.ByteArrayOutputStream()
        records.forEachIndexed { index, r ->
            require(r.type.size <= 255 && r.tnf.toInt() in 0..6)
            val short = r.payload.size < 256
            out.write((if (index == 0) 0x80 else 0) or (if (index == records.lastIndex) 0x40 else 0) or (if (short) 0x10 else 0) or r.tnf.toInt())
            out.write(r.type.size)
            if (short) out.write(r.payload.size) else for (shift in listOf(24, 16, 8, 0)) out.write(r.payload.size ushr shift)
            out.write(r.type)
            out.write(r.payload)
        }
        return out.toByteArray()
    }
    fun decode(bytes: ByteArray): List<NdefRecordView> {
        var offset = 0
        val result = mutableListOf<NdefRecordView>()
        fun byte(): Int {
            if (offset >= bytes.size) throw PosReaderException("Truncated NDEF record")
            return bytes[offset++].u()
        }
        while (offset < bytes.size) {
            val flags = byte()
            if ((flags and 0x20) != 0 || ((flags and 0x80) != 0) != result.isEmpty()) throw PosReaderException("Unsupported NDEF record flags")
            val typeSize = byte()
            val length = if (flags and 0x10 != 0) byte().toLong() else
                (byte().toLong() shl 24) or (byte().toLong() shl 16) or (byte().toLong() shl 8) or byte().toLong()
            val idSize = if (flags and 8 != 0) byte() else 0
            if (length > bytes.size - offset - typeSize - idSize || flags and 7 == 7) throw PosReaderException("Invalid NDEF record length")
            val type = bytes.copyOfRange(offset, offset + typeSize)
            offset += typeSize + idSize
            val payload = bytes.copyOfRange(offset, offset + length.toInt())
            offset += length.toInt()
            result += NdefRecordView((flags and 7).toShort(), type, payload)
            if (flags and 0x40 != 0) {
                if (offset != bytes.size) throw PosReaderException("Bytes follow the final NDEF record")
                return result
            }
        }
        throw PosReaderException("NDEF message has no final record")
    }
}
