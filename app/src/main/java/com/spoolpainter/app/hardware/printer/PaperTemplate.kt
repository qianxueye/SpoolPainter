package com.spoolpainter.app.hardware.printer

import kotlin.math.roundToInt

const val PRINTER_WIDTH_DOTS = 384
// Application calibration from the owner-confirmed tail test; not a measured head DPI.
const val PRINTER_DOTS_PER_MM = 8
fun mmToDots(mm: Double): Int = (mm * PRINTER_DOTS_PER_MM).roundToInt()

enum class LabelField(val title: String) {
    ID("耗材编号"), NAME("完整名称"), VENDOR("品牌"), MATERIAL("材料"), COLOR("颜色"),
    REMAINING("剩余重量"), LOCATION("位置"), SERVER("服务器地址"), QR("二维码"),
}

/** Software geometry for forward-only feeding, not a hardware gap/black-mark sensor mode. */
data class PaperTemplate(
    val name: String = "48 × 60 mm",
    val widthMm: Double = 48.0,
    val heightMm: Double = 60.0,
    val gapMm: Double = 2.0,
    val offsetXmm: Double = 0.0,
    val offsetYmm: Double = 0.0,
    val marginMm: Double = 2.0,
    val fontDots: Int = 20,
    val qrMm: Double = 24.0,
    val selectedFields: Set<LabelField> = setOf(LabelField.ID, LabelField.NAME, LabelField.VENDOR, LabelField.MATERIAL, LabelField.COLOR, LabelField.QR),
) {
    val widthDots: Int get() = mmToDots(widthMm)
    val heightDots: Int get() = mmToDots(heightMm)
    val gapDots: Int get() = mmToDots(gapMm)

    fun validate() {
        require(name.isNotBlank() && name.length <= 80) { "模板名称须为 1–80 个字符" }
        range(widthMm, 20.0, 48.0, "可打印宽度")
        range(heightMm, 20.0, 200.0, "标签高度")
        range(gapMm, 0.0, 20.0, "标签间距")
        range(offsetXmm, 0.0, 48.0, "向右偏移")
        range(offsetYmm, 0.0, 200.0, "向下偏移")
        range(marginMm, 0.0, 10.0, "边距")
        range(qrMm, 10.0, 44.0, "二维码边长")
        require(fontDots in 12..48) { "字号须为 12–48 点" }
        require(selectedFields.isNotEmpty()) { "请至少选择一个打印字段" }
        require(widthDots - 2 * mmToDots(marginMm) - mmToDots(offsetXmm) > 0) { "边距和向右偏移超过可打印宽度" }
        require(heightDots - 2 * mmToDots(marginMm) - mmToDots(offsetYmm) > 0) { "边距和向下偏移超过标签高度" }
    }

    private fun range(value: Double, min: Double, max: Double, title: String) {
        require(value.isFinite() && value in min..max) { "$title 须为 $min–$max mm" }
    }
}
