package com.spoolpainter.app.hardware.pos

import com.spoolpainter.app.hardware.nfc.NdefRecordView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class Ntag216MemoryTest {
    private fun records(size: Int = 64) = listOf(NdefRecordView(2, "application/json".toByteArray(), ByteArray(size) { (it % 127).toByte() }))
    @Test fun `short and extended NDEF commit length last and read back exactly`() = runTest {
        for (size in listOf(64, 300, 840)) {
            val sdk = FakePosSdk()
            val memory = Ntag216Memory(sdk, sdk.uid)
            val protectedBefore = sdk.memory.copyOfRange(222 * 4, sdk.memory.size)
            memory.writeRecords(records(size))
            assertEquals(records(size), memory.readRecords())
            assertEquals(4, sdk.writes.first().first)
            assertArrayEquals(byteArrayOf(3, 0, 0xfe.toByte(), 0), sdk.writes.first().second)
            assertEquals(4, sdk.writes.last().first)
            assertTrue(sdk.writes.all { it.first in 4..221 && it.second.size == 4 })
            assertArrayEquals(protectedBefore, sdk.memory.copyOfRange(222 * 4, sdk.memory.size))
        }
    }
    @Test fun `empty NDEF does not read unused pages after the first user chunk`() = runTest {
        val fake = FakePosSdk()
        byteArrayOf(3, 0, 0xfe.toByte(), 0).copyInto(fake.memory, 16)
        val reads = mutableListOf<Int>()
        val sdk = object : PosReaderSdk by fake {
            override fun readPages(page: Int): ByteArray {
                reads += page
                if (page > 4) throw PosReaderException("RF failed on unused page")
                return fake.readPages(page)
            }
        }
        assertNull(Ntag216Memory(sdk, fake.uid).readRecords())
        assertEquals(listOf(0, 4), reads)
    }
    @Test fun `short NDEF reads only pages containing its declared payload`() = runTest {
        val fake = FakePosSdk()
        val expected = records(64)
        Ntag216Memory(fake, fake.uid).writeRecords(expected)
        val encodedSize = PosNdefCodec.encode(expected).size + 2
        val lastReadPage = 4 + ((encodedSize - 1) / 16) * 4
        val reads = mutableListOf<Int>()
        val sdk = object : PosReaderSdk by fake {
            override fun readPages(page: Int): ByteArray {
                reads += page
                if (page > lastReadPage) throw PosReaderException("RF failed after complete payload")
                return fake.readPages(page)
            }
        }
        assertEquals(expected, Ntag216Memory(sdk, fake.uid).readRecords())
        assertEquals(lastReadPage, reads.last())
        assertEquals(reads.distinct(), reads)
    }
    @Test fun `declared overcapacity NDEF is rejected before fetching its payload`() = runTest {
        val fake = FakePosSdk()
        byteArrayOf(3, 0xff.toByte(), 4, 0).copyInto(fake.memory, 16)
        val sdk = object : PosReaderSdk by fake {
            override fun readPages(page: Int): ByteArray {
                check(page <= 4) { "must not read invalid payload" }
                return fake.readPages(page)
            }
        }
        try { Ntag216Memory(sdk, fake.uid).readRecords(); fail("must reject") }
        catch (e: PosReaderException) { assertTrue(e.message!!.contains("exceeds")) }
    }
    @Test fun `locked password protected or malformed capability refuses every write`() = runTest {
        val mutations: List<(FakePosSdk) -> Unit> = listOf(
            { it.memory[10] = 1 }, { it.memory[11] = 1 },
            { it.memory[226 * 4] = 1 }, { it.memory[227 * 4 + 3] = 4 },
            { it.memory[227 * 4] = 0x40 }, { it.memory[15] = 15 },
            { it.memory[14] = 0x7f }, { it.memory[16] = 1 },
        )
        for (mutate in mutations) {
            val sdk = FakePosSdk(); mutate(sdk)
            try { Ntag216Memory(sdk, sdk.uid).writeRecords(records()); fail("must reject") }
            catch (_: PosReaderException) { }
            assertTrue(sdk.writes.isEmpty())
        }
    }
    @Test fun `over capacity leaves existing bytes intact`() = runTest {
        val sdk = FakePosSdk(); val before = sdk.memory.copyOf()
        try { Ntag216Memory(sdk, sdk.uid).writeRecords(records(872)); fail("must reject") }
        catch (_: PosReaderException) { }
        assertArrayEquals(before, sdk.memory)
        assertTrue(sdk.writes.isEmpty())
    }
    @Test fun `interrupted write keeps NDEF length zero`() = runTest {
        val sdk = FakePosSdk().apply { cancelAtPage = 6 }
        try { Ntag216Memory(sdk, sdk.uid).writeRecords(records()); fail("must cancel") }
        catch (_: CancellationException) { }
        assertEquals(0, sdk.memory[17].u())
        assertEquals(1, sdk.writes.count { it.first == 4 })
    }
    @Test fun `readback mismatch never reports success`() = runTest {
        val sdk = FakePosSdk().apply { corruptReadback = true }
        try { Ntag216Memory(sdk, sdk.uid).writeRecords(records()); fail("must reject") }
        catch (e: PosReaderException) { assertTrue(e.message!!.contains("verification failed")) }
        assertEquals(1, sdk.writes.size)
    }
    @Test fun `vendor session exposes UID only and blocks writes`() = runTest {
        val sdk = FakePosSdk().apply { cardType = 2 }
        val session = SdkTagSession(sdk, sdk.card())
        val raw = session.read()
        assertEquals("04010203040506", raw.uid.toString())
        assertEquals(listOf("android.nfc.tech.MifareClassic"), raw.techList)
        assertNull(raw.records)
        try { session.writeRecords(records()); fail("vendor writes blocked") }
        catch (_: PosReaderException) { }
        assertTrue(sdk.writes.isEmpty())
        session.expire()
        try { session.read(); fail("expired session") } catch (_: IllegalStateException) { }
    }
    @Test fun `malformed NDEF cannot escape bounded decode`() {
        for (data in listOf(byteArrayOf(), byteArrayOf(0xc2.toByte(), 1, 0x7f, -1, -1, -1))) {
            try { PosNdefCodec.decode(data); fail("must reject") } catch (_: PosReaderException) { }
        }
    }
}
