package com.spoolpainter.app.hardware.pos

import android.content.Context
import java.lang.reflect.InvocationTargetException

/** Uses only the device's optional shared library; no manufacturer artifact is bundled. */
internal class ReflectivePosReaderSdk(context: Context) : PosReaderSdk {
    private val loader = context.classLoader
    private val managerClass = Class.forName("com.pos.sdk.cardreader.POICardManager", true, loader)
    private val byteArrayClass = Class.forName("com.pos.sdk.utils.PosByteArray", true, loader)
    private val manager = managerClass.getMethod("getDefault", Context::class.java).invoke(null, context)
        ?: throw PosReaderException("POS card service unavailable")
    private val picc = managerClass.getMethod("getPiccCardReader").invoke(manager)
        ?: throw PosReaderException("POS PICC reader unavailable")
    private val mifare = managerClass.getMethod("getMifareCardReader").invoke(manager)
        ?: throw PosReaderException("POS Mifare reader unavailable")

    override fun openPicc() = call(picc, "open") as Int
    override fun closePicc() = call(picc, "close") as Int
    override fun openMifare(type: Int) = call(mifare, "open", type) as Int
    override fun closeMifare() = call(mifare, "close") as Int
    override fun detect() = call(mifare, "detectEx", "M") as Int
    override fun card(): PosCard {
        val info = call(mifare, "getCardReaderInfo") ?: throw PosReaderException("POS returned no card information")
        val uid = (info.javaClass.getField("mSerialNum").get(info) as? ByteArray)?.copyOf()
            ?: throw PosReaderException("POS returned no UID")
        if (uid.isEmpty()) throw PosReaderException("POS returned an empty UID")
        return PosCard(uid, info.javaClass.getField("mCardType").getInt(info))
    }

    override fun readPages(page: Int): ByteArray {
        require(page in 0..227)
        val output = byteArrayClass.getConstructor().newInstance()
        // Ultralight READ returns four 4-byte pages. SDK read(address, length, out).
        checkCode("read page $page", call(mifare, "read", page, 16, output) as Int)
        val count = byteArrayClass.getField("len").getInt(output)
        val bytes = byteArrayClass.getField("buffer").get(output) as? ByteArray
        if (count != 16 || bytes == null || bytes.size < count) {
            throw PosReaderException("POS page read returned $count bytes; expected 16")
        }
        return bytes.copyOf(count)
    }

    override fun writePage(page: Int, data: ByteArray) {
        // A second boundary at the transport prevents lock/config/manufacturer writes.
        require(page in 4..221 && data.size == 4)
        checkCode("write page $page", call(mifare, "write", page, data) as Int)
    }

    private fun call(target: Any, name: String, vararg args: Any): Any? {
        val types = args.map {
            when (it) {
                is Int -> Int::class.javaPrimitiveType!!
                is String -> String::class.java
                is ByteArray -> ByteArray::class.java
                else -> it.javaClass
            }
        }.toTypedArray()
        return try {
            target.javaClass.getMethod(name, *types).invoke(target, *args)
        } catch (e: InvocationTargetException) {
            throw (e.targetException ?: e)
        }
    }

    companion object {
        fun available(context: Context): Boolean = try {
            Class.forName("com.pos.sdk.cardreader.POICardManager", false, context.classLoader)
            true
        } catch (_: ClassNotFoundException) {
            false
        } catch (_: LinkageError) {
            false
        }
    }
}

internal fun checkCode(operation: String, code: Int) {
    if (code != 0) throw PosReaderException("POS $operation failed (0x${(code and 0xffff).toString(16)})")
}
