package com.spoolpainter.app.hardware.paper

import java.math.BigDecimal

/** Requested raw units, using the app's nominal eight-unit/mm sizing assumption. */
data class RetractionDistance(val units: Int = DEFAULT_UNITS) {
    init { PaperProtocol.validateRetractionUnits(units) }
    val centimeters: Double get() = units / 80.0
    val centimetersText: String get() = BigDecimal.valueOf(units.toLong())
        .divide(BigDecimal.valueOf(80)).setScale(1).toPlainString()

    companion object {
        const val DEFAULT_UNITS = 120
        fun fromCentimeters(text: String): RetractionDistance {
            require(!text.contains(',')) { "请使用小数点，例如 1.5" }
            val value = text.trim().toBigDecimalOrNull()
            require(value != null && value >= BigDecimal("0.1") && value <= BigDecimal("3.0")) { "回抽距离须为 0.1–3.0 cm" }
            require(value.stripTrailingZeros().scale() <= 1) { "回抽距离最多保留一位小数" }
            return RetractionDistance(value.multiply(BigDecimal.valueOf(80)).intValueExact())
        }
    }
}
