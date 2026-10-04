package com.spoolpainter.app.hardware.printer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.ceil
import kotlin.math.floor

/** Exactly the bitmap handed to the vendor SDK; no SDK text layout is involved in fixed mode. */
fun renderLabelBitmap(request: PrintRequest): Bitmap {
    val paper = requireNotNull(request.paper) { "请选择定长标签模板" }
    paper.validate()
    val textPaint = Paint().apply {
        color = Color.BLACK
        textSize = paper.fontDots.toFloat()
        typeface = Typeface.DEFAULT
        isAntiAlias = false // Binary pixels match the SDK's monochrome threshold conversion.
    }
    val font = textPaint.fontMetrics
    val top = floor(font.top.toDouble()).toInt()
    val bottom = ceil(font.bottom.toDouble()).toInt()
    val metrics = object : LabelTextMetrics {
        override val lineHeightDots = bottom - top
        override val baselineOffsetDots = -top.toFloat()
        override fun measure(text: String): Float = textPaint.measureText(text)
    }
    val layout = LabelLayout.plan(request, metrics)
    return Bitmap.createBitmap(layout.widthDots, layout.heightDots, Bitmap.Config.ARGB_8888).apply {
        // BitmapDrawable otherwise applies density-dependent intrinsic dimensions in PrinterLayout.
        density = Bitmap.DENSITY_NONE
        eraseColor(Color.WHITE)
        val canvas = Canvas(this)
        val qrPaint = Paint().apply { color = Color.BLACK; isAntiAlias = false }
        layout.elements.forEach { element ->
            when (element) {
                is LabelElement.Text -> canvas.drawText(element.text, element.x.toFloat(), element.baselineY, textPaint)
                is LabelElement.Qr -> {
                    val startX = element.x + element.insetDots
                    val startY = element.y + element.insetDots
                    for (y in 0 until element.symbol.size) for (x in 0 until element.symbol.size) {
                        if (element.symbol.black(x, y)) {
                            val left = startX + x * element.moduleScale
                            val row = startY + y * element.moduleScale
                            canvas.drawRect(left.toFloat(), row.toFloat(), (left + element.moduleScale).toFloat(), (row + element.moduleScale).toFloat(), qrPaint)
                        }
                    }
                }
            }
        }
    }
}

/** Fully render compensation receipts before any motion so rendering errors cannot follow retraction. */
fun renderReceiptBitmap(request: PrintRequest, brandLogo: Bitmap?): Bitmap {
    require(request.paper == null) { "此渲染器只支持连续小票" }
    request.validatePrintOptions()
    val paint = Paint().apply { color = Color.BLACK; textSize = 24f; typeface = Typeface.DEFAULT; isAntiAlias = false }
    val top = floor(paint.fontMetrics.top.toDouble()).toInt()
    val bottom = ceil(paint.fontMetrics.bottom.toDouble()).toInt()
    val metrics = object : LabelTextMetrics {
        override val lineHeightDots = bottom - top
        override val baselineOffsetDots = -top.toFloat()
        override fun measure(text: String) = paint.measureText(text)
    }
    data class TextRow(val text: String, val y: Int, val centered: Boolean)
    data class QrRow(val bitmap: Bitmap, val y: Int)
    val texts = mutableListOf<TextRow>()
    val qrs = mutableListOf<QrRow>()
    var height = brandLogo?.height ?: 0
    fun addText(text: String, centered: Boolean) {
        wrapLabelText(text, PRINTER_WIDTH_DOTS, metrics).forEach { line ->
            texts += TextRow(line, height, centered)
            height += metrics.lineHeightDots
            require(height <= 10_000) { "小票内容超过安全渲染高度，尚未回抽或打印" }
        }
    }
    request.textLines().forEachIndexed { index, text -> addText(text, index == 0) }
    request.qrPayloads().forEach { payload ->
        val bitmap = qrBitmap(payload).apply { density = Bitmap.DENSITY_NONE }
        qrs += QrRow(bitmap, height)
        height += bitmap.height
        require(height <= 10_000) { "小票二维码超过安全渲染高度，尚未回抽或打印" }
        if (payload.startsWith("WEB+SPOOLMAN:")) addText(payload, true)
    }
    return Bitmap.createBitmap(PRINTER_WIDTH_DOTS, height, Bitmap.Config.ARGB_8888).apply {
        density = Bitmap.DENSITY_NONE
        eraseColor(Color.WHITE)
        val canvas = Canvas(this)
        brandLogo?.let { canvas.drawBitmap(it, ((PRINTER_WIDTH_DOTS - it.width) / 2).toFloat(), 0f, null) }
        texts.forEach { row ->
            val x = if (row.centered) (PRINTER_WIDTH_DOTS - paint.measureText(row.text)) / 2f else 0f
            canvas.drawText(row.text, x, row.y + metrics.baselineOffsetDots, paint)
        }
        qrs.forEach { row -> canvas.drawBitmap(row.bitmap, ((PRINTER_WIDTH_DOTS - row.bitmap.width) / 2).toFloat(), row.y.toFloat(), null) }
    }
}
