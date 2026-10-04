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
