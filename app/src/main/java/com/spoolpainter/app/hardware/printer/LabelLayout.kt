package com.spoolpainter.app.hardware.printer

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** Font measurement is injectable so fit/wrapping/coordinates can be tested without Android. */
interface LabelTextMetrics {
    val lineHeightDots: Int
    val baselineOffsetDots: Float
    fun measure(text: String): Float
}

data class QrSymbol(val size: Int, val pixels: BooleanArray) {
    fun black(x: Int, y: Int): Boolean = pixels[y * size + x]
}

fun encodeLabelQr(payload: String): QrSymbol {
    // Zero requested size gives the module matrix itself, including the four-module quiet zone.
    val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.CHARACTER_SET to "UTF-8"))
    return QrSymbol(matrix.width, BooleanArray(matrix.width * matrix.height) { matrix[it % matrix.width, it / matrix.width] })
}

sealed interface LabelElement {
    data class Text(val text: String, val x: Int, val baselineY: Float) : LabelElement
    data class Qr(val x: Int, val y: Int, val boxDots: Int, val symbol: QrSymbol, val moduleScale: Int) : LabelElement {
        val insetDots: Int get() = (boxDots - symbol.size * moduleScale) / 2
    }
}

data class LabelLayout(
    val widthDots: Int,
    val heightDots: Int,
    val paperLeftDots: Int,
    val paperWidthDots: Int,
    val elements: List<LabelElement>,
) {
    companion object {
        fun plan(request: PrintRequest, metrics: LabelTextMetrics, qrEncoder: (String) -> QrSymbol = ::encodeLabelQr): LabelLayout {
            val paper = requireNotNull(request.paper) { "请选择定长标签模板" }
            paper.validate()
            request.qrPayloads() // Server/id validation is identical to the receipt path.
            require(metrics.lineHeightDots > 0 && metrics.baselineOffsetDots.isFinite() && metrics.baselineOffsetDots in 0f..metrics.lineHeightDots.toFloat()) { "字体度量无效" }
            val paperLeft = (PRINTER_WIDTH_DOTS - paper.widthDots) / 2
            val margin = mmToDots(paper.marginMm)
            val left = paperLeft + margin + mmToDots(paper.offsetXmm)
            val availableWidth = paper.widthDots - 2 * margin - mmToDots(paper.offsetXmm)
            val bottom = paper.heightDots - margin
            var top = margin + mmToDots(paper.offsetYmm)
            val elements = mutableListOf<LabelElement>()
            fun fits(height: Int) {
                require(top + height <= bottom) { "内容超出标签高度，请增加高度、缩小字号或二维码，或减少字段；完整名称不会截断" }
            }
            LabelField.entries.filter { it in paper.selectedFields && it != LabelField.QR }.forEach { field ->
                wrapLabelText(request.fieldText(field), availableWidth, metrics).forEach { line ->
                    fits(metrics.lineHeightDots)
                    elements += LabelElement.Text(line, left, top + metrics.baselineOffsetDots)
                    top += metrics.lineHeightDots
                }
            }
            if (LabelField.QR in paper.selectedFields) {
                if (elements.isNotEmpty()) top += 4
                val size = mmToDots(paper.qrMm)
                require(size <= availableWidth) { "二维码超过可打印宽度，请减小二维码或边距" }
                request.qrPayloads().forEachIndexed { index, payload ->
                    if (index > 0) top += 4
                    fits(size)
                    val symbol = qrEncoder(payload)
                    require(symbol.size > 0 && symbol.pixels.size == symbol.size * symbol.size) { "二维码数据无效" }
                    val scale = size / symbol.size
                    require(scale >= 2) { "二维码太小，无法以至少 2 点整倍数打印，请增大二维码" }
                    elements += LabelElement.Qr(left + (availableWidth - size) / 2, top, size, symbol, scale)
                    top += size
                }
            }
            return LabelLayout(PRINTER_WIDTH_DOTS, paper.heightDots, paperLeft, paper.widthDots, elements)
        }
    }
}

/** Wrap by Unicode code point; preserve every character, including spaces and surrogate pairs. */
fun wrapLabelText(text: String, widthDots: Int, metrics: LabelTextMetrics): List<String> {
    require(widthDots > 0) { "可打印宽度不足" }
    val lines = mutableListOf<String>()
    var line = ""
    var offset = 0
    while (offset < text.length) {
        val count = Character.charCount(text.codePointAt(offset))
        val token = text.substring(offset, offset + count)
        val tokenWidth = metrics.measure(token)
        require(tokenWidth.isFinite() && tokenWidth <= widthDots) { "单个字符超出可打印宽度，请减小字号" }
        val candidate = line + token
        if (metrics.measure(candidate) > widthDots && line.isNotEmpty()) {
            lines += line
            line = token
        } else line = candidate
        offset += count
    }
    if (line.isNotEmpty() || lines.isEmpty()) lines += line
    return lines
}
