package com.spoolpainter.app.ui.screens.printing

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.spoolpainter.app.hardware.printer.LabelField
import com.spoolpainter.app.hardware.printer.PaperTemplate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** One local profile; receipt paper is represented by a null selection. */
data class PaperTemplateProfile(val id: String, val paper: PaperTemplate)

data class PaperTemplateSelection(
    val profiles: List<PaperTemplateProfile> = emptyList(),
    val selectedId: String? = null,
) {
    val selected: PaperTemplateProfile? get() = profiles.firstOrNull { it.id == selectedId }
}

/** Pure storage adapter so disk recovery and atomic selection updates are testable on the JVM. */
class PaperTemplateStore(
    read: () -> String?,
    private val write: (String) -> Boolean,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutableState = MutableStateFlow(decodePaperTemplates(read()))
    val state = mutableState.asStateFlow()

    fun select(id: String?) {
        require(id == null || mutableState.value.profiles.any { it.id == id }) { "纸张模板不存在，请重新选择" }
        persist(mutableState.value.copy(selectedId = id))
    }

    fun save(id: String?, paper: PaperTemplate) {
        val normalized = paper.copy(name = paper.name.trim(), selectedFields = paper.selectedFields.toSet())
        normalized.validate()
        val current = mutableState.value
        require(id == null || current.profiles.any { it.id == id }) { "纸张模板不存在，请重新新建" }
        require(current.profiles.none { it.id != id && it.paper.name == normalized.name }) { "模板名称已存在，请使用其他名称" }
        val profile = PaperTemplateProfile(id ?: newId(), normalized)
        val profiles = if (id == null) current.profiles + profile else current.profiles.map { if (it.id == id) profile else it }
        persist(PaperTemplateSelection(profiles, profile.id))
    }

    fun delete(id: String) {
        val current = mutableState.value
        val profiles = current.profiles.filterNot { it.id == id }
        persist(PaperTemplateSelection(profiles, current.selectedId.takeUnless { it == id }))
    }

    private fun persist(value: PaperTemplateSelection) {
        check(write(encodePaperTemplates(value))) { "无法保存纸张模板，请重试" }
        mutableState.value = value
    }
}

internal fun encodePaperTemplates(value: PaperTemplateSelection): String = JsonObject().apply {
    addProperty("version", 1)
    value.selectedId?.let { addProperty("selected_id", it) }
    add("profiles", JsonArray().apply {
        value.profiles.forEach { profile -> add(JsonObject().apply {
            addProperty("id", profile.id)
            val paper = profile.paper
            addProperty("name", paper.name)
            addProperty("width_mm", paper.widthMm)
            addProperty("height_mm", paper.heightMm)
            addProperty("gap_mm", paper.gapMm)
            addProperty("offset_x_mm", paper.offsetXmm)
            addProperty("offset_y_mm", paper.offsetYmm)
            addProperty("margin_mm", paper.marginMm)
            addProperty("font_dots", paper.fontDots)
            addProperty("qr_mm", paper.qrMm)
            add("fields", JsonArray().apply { paper.selectedFields.sortedBy { it.ordinal }.forEach { add(it.name) } })
        }) }
    })
}.toString()

/** Malformed profiles are ignored; missing/invalid selection safely falls back to receipt paper. */
internal fun decodePaperTemplates(raw: String?): PaperTemplateSelection = runCatching {
    if (raw.isNullOrBlank()) return@runCatching PaperTemplateSelection()
    val root = JsonParser.parseString(raw).asJsonObject
    require(root.get("version").asInt == 1)
    val seen = mutableSetOf<String>()
    val profiles = root.getAsJsonArray("profiles").mapNotNull { element -> runCatching {
        val item = element.asJsonObject
        val id = item.get("id").asString
        require(id.isNotBlank())
        val font = item.get("font_dots").asDouble
        require(font.isFinite() && font % 1.0 == 0.0 && font in 12.0..48.0)
        val paper = PaperTemplate(
            name = item.get("name").asString,
            widthMm = item.get("width_mm").asDouble,
            heightMm = item.get("height_mm").asDouble,
            gapMm = item.get("gap_mm").asDouble,
            offsetXmm = item.get("offset_x_mm").asDouble,
            offsetYmm = item.get("offset_y_mm").asDouble,
            marginMm = item.get("margin_mm").asDouble,
            fontDots = font.toInt(),
            qrMm = item.get("qr_mm").asDouble,
            selectedFields = item.getAsJsonArray("fields").map { LabelField.valueOf(it.asString) }.toSet(),
        )
        paper.validate()
        require(seen.add(id))
        PaperTemplateProfile(id, paper)
    }.getOrNull() }
    val selected = root.get("selected_id")?.takeUnless { it.isJsonNull }?.asString
    PaperTemplateSelection(profiles, selected?.takeIf { id -> profiles.any { it.id == id } })
}.getOrElse { PaperTemplateSelection() }
