package com.spoolpainter.app.data.remote.inventory

import com.google.gson.*

fun JsonObject.text(key: String): String = get(key)?.takeUnless { it.isJsonNull }?.let {
    if (it.isJsonPrimitive) it.asString else it.toString()
} ?: ""
fun JsonObject.number(key: String): Double? = text(key).toDoubleOrNull()
fun JsonObject.child(key: String): JsonObject = get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
fun JsonObject.recordId(): Int = number("id")?.toInt() ?: error("服务器记录缺少 ID")
fun JsonObject.isArchived(): Boolean = text("archived") == "true"
fun JsonObject.inventoryTitle(entity: String): String = when (entity) {
    "spool" -> "#${text("id")} ${child("filament").text("name").ifBlank { child("filament").text("material") }}"
    else -> "#${text("id")} ${text("name").ifBlank { text("material") }}"
}

/** Structural JSON parsing alone accepts bare primitive tokens; validate every leaf. */
internal object StrictInventoryJson {
    private val number = Regex("""-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?""")

    fun parse(raw: String): kotlinx.serialization.json.JsonElement {
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(raw)
        validate(parsed)
        return parsed
    }

    private fun validate(value: kotlinx.serialization.json.JsonElement) {
        when (value) {
            is kotlinx.serialization.json.JsonObject -> value.values.forEach(::validate)
            is kotlinx.serialization.json.JsonArray -> value.forEach(::validate)
            is kotlinx.serialization.json.JsonPrimitive -> require(
                value.isString || value.content in setOf("true", "false", "null") || number.matches(value.content),
            ) { "JSON 包含无效字面量；文本请加双引号" }
        }
    }
}

/** Baseline is the form-opening snapshot. Only operator-changed fields are candidates. */
object InventoryPatch {
    fun value(record: JsonObject, key: String): JsonElement = when (key) {
        "filament_id" -> record.get(key) ?: record.child("filament").get("id") ?: JsonNull.INSTANCE
        "vendor_id" -> record.get(key) ?: record.child("vendor").get("id") ?: JsonNull.INSTANCE
        else -> record.get(key) ?: JsonNull.INSTANCE
    }

    fun fromFresh(baseline: JsonObject, fresh: JsonObject, requested: JsonObject): JsonObject {
        val patch = JsonObject()
        requested.entrySet().forEach { (key, desired) ->
            if (key == "extra") {
                require(desired.isJsonObject) { "自定义字段必须逐项更新" }
                val extra = JsonObject()
                desired.asJsonObject.entrySet().forEach { (field, next) ->
                    val old = value(baseline.child("extra"), field)
                    val current = value(fresh.child("extra"), field)
                    if (next != old && next != current) {
                        check(current == old) { "自定义字段 $field 已被其他客户端修改，请刷新后重试" }
                        extra.add(field, next)
                    }
                }
                if (extra.size() > 0) patch.add("extra", extra)
            } else {
                val old = value(baseline, key)
                val current = value(fresh, key)
                if (desired != old && desired != current) {
                    check(current == old) { "字段 $key 已被其他客户端修改，请刷新后重试" }
                    patch.add(key, desired)
                }
            }
        }
        require(!(patch.has("remaining_weight") && patch.has("used_weight"))) { "不能同时修改剩余重量和已用重量" }
        return patch
    }
}

object InventoryUids {
    fun normalize(input: String): String {
        val uid = input.trim().replace(Regex("[ :\\-]"), "").uppercase()
        require(uid.matches(Regex("[0-9A-F]+")) && uid.length in setOf(8, 14, 20)) { "UID 必须是 4、7 或 10 字节的十六进制值" }
        return uid
    }
    fun decode(record: JsonObject): List<String> {
        val raw = record.child("extra").get("card_uids") ?: return emptyList()
        if (raw.isJsonNull) return emptyList()
        require(raw.isJsonPrimitive && raw.asJsonPrimitive.isString) { "#${record.text("id")} 的 card_uids 不是 JSON 字符串" }
        // Gson's parser accepts unquoted literals as strings; the deployed API
        // requires an actual JSON-encoded text value, so parse strictly here.
        val decoded = try {
            StrictInventoryJson.parse(raw.asString) as? kotlinx.serialization.json.JsonPrimitive
        } catch (_: Exception) { null }
        require(decoded != null && decoded.isString) { "#${record.text("id")} 的 card_uids 编码无效" }
        if (decoded.content.isBlank()) return emptyList()
        return decoded.content.split(',').map { normalize(it) }.distinct()
    }
    fun index(records: List<JsonObject>): Map<String, Set<Int>> {
        val result = mutableMapOf<String, MutableSet<Int>>()
        records.forEach { record -> decode(record).forEach { result.getOrPut(it) { mutableSetOf() }.add(record.recordId()) } }
        return result
    }
    fun encoded(uids: List<String>): String = Gson().toJson(uids.joinToString(","))
}
