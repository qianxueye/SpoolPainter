package com.spoolpainter.app.hardware.printer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.lang.reflect.Proxy

/** Reflection surface checked against the device's vendor-sdk.jar; no SDK is packaged. */
class KozenPrinterPort(private val context: Context) : PrinterPort {
    private var manager: Any? = null
    private val managerClass by lazy { Class.forName("com.pos.sdk.printer.POIPrinterManager") }
    private val lineClass by lazy { Class.forName("com.pos.sdk.printer.models.PrintLine") }
    private val textClass by lazy { Class.forName("com.pos.sdk.printer.models.TextPrintLine") }
    private val bitmapClass by lazy { Class.forName("com.pos.sdk.printer.models.BitmapPrintLine") }
    private val listenerClass by lazy { Class.forName("com.pos.sdk.printer.POIPrinterManager\$IPrinterListener") }
    // The proxy must stay strongly reachable until the native job is finished.
    private var listenerProxy: Any? = null

    override fun open() {
        try {
            if (manager == null) manager = managerClass.getConstructor(Context::class.java).newInstance(context)
            managerClass.getMethod("open").invoke(manager)
        } catch (e: ReflectiveOperationException) {
            runCatching { close() }
            throw IllegalStateException("无法连接内置打印机，请检查 POS 服务和打印权限", e)
        } catch (e: LinkageError) {
            runCatching { close() }
            throw IllegalStateException("设备上的 POS 打印库不可用", e)
        }
    }

    override fun state(): Int = if (manager == null) 4 else managerClass.getMethod("getPrinterState").invoke(manager) as Int

    override fun prepare(request: PrintRequest) {
        managerClass.getMethod("cleanCache").invoke(manager)
        val add = managerClass.getMethod("addPrintLine", lineClass)
        val constructor = textClass.getConstructor(String::class.java, Int::class.javaPrimitiveType, Float::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        request.textLines().forEachIndexed { index, text ->
            add.invoke(manager, constructor.newInstance(text, if (index == 0) 1 else 0, 24f, index == 0))
        }
        request.qrPayloads().forEach { payload ->
            add.invoke(manager, bitmapClass.getConstructor(Bitmap::class.java, Int::class.javaPrimitiveType).newInstance(qrBitmap(payload), 1))
            if (payload.startsWith("WEB+SPOOLMAN:")) add.invoke(manager, constructor.newInstance(payload, 1, 24f, false))
        }
    }

    override fun begin(listener: PrinterPort.Listener) {
        listenerProxy = Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { proxy, method, args ->
            when (method.name) {
                "onStart" -> listener.onStart()
                "onFinish" -> listener.onFinish()
                "onError" -> listener.onError(args?.get(0) as Int, args?.getOrNull(1) as? String ?: "")
                "toString" -> return@newProxyInstance "SpoolPainterPrinterListener"
                "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                "equals" -> return@newProxyInstance proxy === args?.getOrNull(0)
            }
            null
        }
        managerClass.getMethod("beginPrint", listenerClass).invoke(manager, listenerProxy)
    }

    override fun feed(dots: Int) {
        managerClass.getMethod("setLineWrapPixels", Int::class.javaPrimitiveType).invoke(manager, 1)
        managerClass.getMethod("lineWrapPixels", Int::class.javaPrimitiveType).invoke(manager, dots)
    }

    override fun close() {
        try { manager?.let { managerClass.getMethod("close").invoke(it) } }
        finally { manager = null; listenerProxy = null }
    }
}

/** No remote image service: both preview and print use the same local encoder. */
fun qrBitmap(payload: String): Bitmap {
    val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 256, 256, mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.CHARACTER_SET to "UTF-8"))
    val pixels = IntArray(matrix.width * matrix.height) { index -> if (matrix[index % matrix.width, index / matrix.width]) Color.BLACK else Color.WHITE }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}
