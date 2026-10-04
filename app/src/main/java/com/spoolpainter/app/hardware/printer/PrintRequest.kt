package com.spoolpainter.app.hardware.printer

import java.net.URI
import java.util.Locale

data class SpoolLabel(
    val id: Int,
    val vendor: String,
    val material: String,
    val color: String,
    val remainingGrams: Double?,
    val location: String,
    val name: String = "",
)

enum class LabelQrMode(val title: String) {
    WEB("网页二维码"), SPOOLMAN("Spoolman 识别码"), BOTH("网页与识别码"),
}

enum class PaperTail(val dots: Int, val title: String) {
    ONE_CM(80, "1 cm"), ONE_AND_HALF_CM(120, "1.5 cm"), TWO_CM(160, "2 cm"), TWO_AND_HALF_CM(200, "2.5 cm");
    companion object {
        fun fromDots(dots: Int) = entries.firstOrNull { it.dots == dots } ?: ONE_AND_HALF_CM
    }
}

data class PrintRequest(
    val label: SpoolLabel,
    val serverUrl: String,
    val qrMode: LabelQrMode = LabelQrMode.WEB,
    val paper: PaperTemplate? = null,
) {
    fun webUrl(): String {
        require(label.id > 0) { "耗材编号无效" }
        val base = URI(serverUrl.trim())
        require(base.scheme?.lowercase() in setOf("http", "https") && !base.host.isNullOrBlank()) { "请设置有效的服务器地址" }
        require(base.rawUserInfo == null && base.rawQuery == null && base.rawFragment == null) { "服务器地址不能包含账号、查询参数或片段" }
        return base.toASCIIString().trimEnd('/') + "/spool/show/${label.id}"
    }

    fun qrPayloads(): List<String> {
        val web = webUrl() // Validate the selected server even for the interoperable ID format.
        val spoolman = "WEB+SPOOLMAN:S-${label.id}"
        return when (qrMode) {
            LabelQrMode.WEB -> listOf(web)
            LabelQrMode.SPOOLMAN -> listOf(spoolman)
            LabelQrMode.BOTH -> listOf(web, spoolman)
        }
    }

    fun textLines(): List<String> = LabelField.entries.filter { it != LabelField.QR }.map(::fieldText)

    fun fieldText(field: LabelField): String = cleanText(when (field) {
        LabelField.ID -> "耗材 #${label.id}"
        LabelField.NAME -> "名称：${label.name.ifBlank { label.material.ifBlank { "未设置" } }}"
        LabelField.VENDOR -> "品牌：${label.vendor.ifBlank { "未设置" }}"
        LabelField.MATERIAL -> "材料：${label.material.ifBlank { "未设置" }}"
        LabelField.COLOR -> "颜色：${label.color.ifBlank { "未设置" }}"
        LabelField.REMAINING -> "剩余：" + (label.remainingGrams?.takeIf { it.isFinite() }?.let { String.format(Locale.CHINA, "%.1f g", it) } ?: "未知")
        LabelField.LOCATION -> "位置：${label.location.ifBlank { "未设置" }}"
        LabelField.SERVER -> "服务器：${serverUrl.trim().trimEnd('/')}"
        LabelField.QR -> error("二维码使用图像布局")
    })

    /** Fixed stock advances only its configured gap, never the continuous-receipt tail. */
    fun feedDots(receiptTail: PaperTail): Int {
        paper?.validate()
        return paper?.gapDots ?: receiptTail.dots
    }

    private fun cleanText(value: String) = value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')
}
