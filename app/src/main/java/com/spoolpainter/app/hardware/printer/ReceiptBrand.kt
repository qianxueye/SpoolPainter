package com.spoolpainter.app.hardware.printer

import java.util.Locale
import kotlin.math.floor
import kotlin.math.min

/** Only explicit vendor identities select artwork; unknown names retain their printed text. */
enum class ReceiptBrand {
    INSLOGIC, POLYMAKER, SNAPMAKER, BAMBU_LAB, KEXCELLED;

    companion object {
        fun resolve(vendor: String): ReceiptBrand? = when (vendor.trim().uppercase(Locale.ROOT)) {
            "INSLOGIC" -> INSLOGIC
            "POLYMAKER" -> POLYMAKER
            "SNAPMAKER" -> SNAPMAKER
            "BAMBU LAB", "BAMBULAB", "拓竹" -> BAMBU_LAB
            "KEXCELLED" -> KEXCELLED
            else -> null
        }
    }
}

/** Dot geometry shared by the SDK bitmap header and the fully rendered receipt. */
data class ReceiptLogoLayout(val widthDots: Int, val artworkHeightDots: Int) {
    val leftDots: Int get() = (PRINTER_WIDTH_DOTS - widthDots) / 2
    val contentTopDots: Int get() = artworkHeightDots + GAP_DOTS

    companion object {
        const val MAX_WIDTH_DOTS = 320
        const val MAX_HEIGHT_DOTS = 96
        const val GAP_DOTS = 8

        fun fit(sourceWidth: Int, sourceHeight: Int): ReceiptLogoLayout {
            require(sourceWidth > 0 && sourceHeight > 0) { "品牌标志尺寸无效，尚未打印" }
            val scale = min(MAX_WIDTH_DOTS.toDouble() / sourceWidth, MAX_HEIGHT_DOTS.toDouble() / sourceHeight)
            val width = floor(sourceWidth * scale).toInt()
            val height = floor(sourceHeight * scale).toInt()
            require(width > 0 && height > 0) { "品牌标志无法按比例放入小票，尚未打印" }
            return ReceiptLogoLayout(width, height)
        }
    }
}
