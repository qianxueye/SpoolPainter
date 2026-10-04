package com.spoolpainter.app.ui.screens.inventory

import com.google.gson.*
import com.spoolpainter.app.data.remote.inventory.*

data class InventoryField(val key: String, val label: String, val number: Boolean = false, val required: Boolean = false, val positive: Boolean = false, val integer: Boolean = false, val maxLength: Int = 64)

internal fun inventoryFields(entity: String): List<InventoryField> = when (entity) {
    "vendor" -> listOf(
        InventoryField("name", "厂商名称", required = true), InventoryField("empty_spool_weight", "默认空盘重量（克）", true),
        InventoryField("external_id", "外部 ID", maxLength = 256), InventoryField("comment", "备注", maxLength = 1024),
    )
    "filament" -> listOf(
        InventoryField("name", "耗材名称"), InventoryField("vendor_id", "厂商", true, integer = true), InventoryField("material", "材料"),
        InventoryField("density", "密度（g/cm³）", true, required = true, positive = true), InventoryField("diameter", "直径（毫米）", true, required = true, positive = true),
        InventoryField("weight", "标称净重（克）", true, positive = true), InventoryField("spool_weight", "空盘重量（克）", true), InventoryField("price", "价格", true),
        InventoryField("color_hex", "颜色 HEX（不含 #）"), InventoryField("multi_color_hexes", "多色 HEX（逗号分隔）", maxLength = 1024), InventoryField("multi_color_direction", "多色方向（coaxial / longitudinal）"),
        InventoryField("settings_extruder_temp", "喷嘴温度（℃）", true, integer = true), InventoryField("settings_bed_temp", "热床温度（℃）", true, integer = true),
        InventoryField("article_number", "商品编号"), InventoryField("external_id", "外部 ID", maxLength = 256), InventoryField("comment", "备注", maxLength = 1024),
    )
    else -> listOf(
        InventoryField("filament_id", "耗材", true, required = true, integer = true, positive = true),
        InventoryField("location", "位置"), InventoryField("lot_nr", "批次"), InventoryField("price", "此卷价格", true),
        InventoryField("initial_weight", "初始净重（克）", true), InventoryField("spool_weight", "此卷空盘重量（克）", true),
        InventoryField("remaining_weight", "直接设置剩余净重（克，不含空盘）", true),
        InventoryField("first_used", "首次使用（ISO 8601 时间）"), InventoryField("last_used", "最后使用（ISO 8601 时间）"),
        InventoryField("comment", "备注", maxLength = 1024),
    )
}

internal fun formValue(record: JsonObject?, key: String): String = record?.let { InventoryPatch.value(it, key).takeUnless { v -> v.isJsonNull }?.asString }.orEmpty()

/** Empty changed values clear nullable properties. Untouched empty fields remain absent. */
internal fun formBody(entity: String, baseline: JsonObject?, values: Map<String, String>, extras: Map<String, String>, cleared: Set<String>): JsonObject {
    val body = JsonObject()
    inventoryFields(entity).forEach { field ->
        val raw = values[field.key].orEmpty().trim()
        val original = formValue(baseline, field.key)
        if (baseline != null && raw == original) return@forEach
        if (raw.isBlank()) {
            require(!field.required) { "请填写${field.label}" }
            if (baseline != null && original.isNotBlank()) body.add(field.key, JsonNull.INSTANCE)
        } else if (field.number) {
            val num = raw.toDoubleOrNull()
            require(num != null && num.isFinite() && if (field.positive) num > 0 else num >= 0) { "${field.label}必须是${if (field.positive) "正数" else "非负数"}" }
            require(!field.integer || num % 1.0 == 0.0) { "${field.label}必须是整数" }
            if (field.integer) { require(num <= Int.MAX_VALUE) { "数值过大" }; body.addProperty(field.key, num.toInt()) }
            else body.addProperty(field.key, num)
        } else {
            require(raw.length <= field.maxLength) { "${field.label}最多 ${field.maxLength} 个字符" }
            if (field.key == "color_hex") require(raw.matches(Regex("[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))) { "颜色须为 6 或 8 位 HEX" }
            if (field.key == "multi_color_direction") require(raw in setOf("coaxial", "longitudinal")) { "多色方向须为 coaxial 或 longitudinal" }
            body.addProperty(field.key, raw)
        }
    }
    val extra = JsonObject()
    extras.forEach { (key, raw) ->
        if (key == "card_uids") return@forEach
        val old = baseline?.child("extra")?.get(key)
        if (key in cleared) { if (old != null && !old.isJsonNull) extra.add(key, JsonNull.INSTANCE) }
        else if (raw.isNotBlank() && old?.takeUnless { it.isJsonNull }?.asString != raw) {
            try { StrictInventoryJson.parse(raw) } catch (_: Exception) { throw IllegalArgumentException("自定义字段 $key 必须是有效 JSON 值；文本请加双引号") }
            extra.addProperty(key, raw)
        }
    }
    if (extra.size() > 0) body.add("extra", extra)
    return body
}
