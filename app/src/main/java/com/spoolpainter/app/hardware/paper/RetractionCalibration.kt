package com.spoolpainter.app.hardware.paper

import java.math.BigDecimal

/** Device-local correction, independent of raster DPI and the requested centimeter setting. */
data class RetractionCalibration(val percent: Int = DEFAULT_PERCENT) {
    init { require(percent in 50..300) { "回抽校准倍率须为 0.50–3.00" } }
    val text: String get() = BigDecimal.valueOf(percent.toLong()).movePointLeft(2).setScale(2).toPlainString()

    fun rawUnits(distance: RetractionDistance): Int {
        // Round to one complete four-phase electrical cycle (two raw feed counts).
        val result = ((distance.units * percent + 100) / 200) * 2
        PaperProtocol.validateRetractionUnits(result)
        return result
    }

    companion object {
        // Owner observation: the former 2 cm setting moved approximately 1 cm.
        const val DEFAULT_PERCENT = 200
        fun parse(text: String): RetractionCalibration {
            require(!text.contains(',')) { "请使用小数点，例如 2.00" }
            val value = text.trim().toBigDecimalOrNull()
            require(value != null && value >= BigDecimal("0.50") && value <= BigDecimal("3.00")) { "回抽校准倍率须为 0.50–3.00" }
            require(value.stripTrailingZeros().scale() <= 2) { "校准倍率最多保留两位小数" }
            return RetractionCalibration(value.movePointRight(2).intValueExact())
        }
    }
}

/** Immutable requested distance and calibration; raw motor counts never masquerade as centimeters. */
data class RetractionMotionRequest(
    val distance: RetractionDistance,
    val calibration: RetractionCalibration = RetractionCalibration(),
) {
    val rawUnits: Int = calibration.rawUnits(distance)
}
