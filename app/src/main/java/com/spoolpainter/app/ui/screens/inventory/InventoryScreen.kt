package com.spoolpainter.app.ui.screens.inventory

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.spoolpainter.app.data.remote.inventory.*
import com.spoolpainter.app.domain.models.SpoolmanSpool
import java.util.Locale

/** Native administration; only explicit confirmed operator actions mutate inventory. */
@Composable
fun InventoryScreen(
    onSettings: () -> Unit,
    onPrint: ((SpoolmanSpool, String) -> Unit)? = null,
    scannedUid: String? = null,
    scannedUidEvent: Long = 0,
    onScanUid: (() -> Unit)? = null,
    viewModel: InventoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf("spool") }
    var query by remember { mutableStateOf("") }
    var archive by remember { mutableStateOf(0) }
    var sort by remember { mutableStateOf(0) }
    var uid by remember { mutableStateOf("") }
    var uidSearchOpen by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var weightMode by remember { mutableStateOf<Boolean?>(null) }
    var advanced by remember(state.selected) { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(Unit) { viewModel.refresh() }
    LaunchedEffect(scannedUid, scannedUidEvent) { if (!scannedUid.isNullOrBlank()) { uid = scannedUid; uidSearchOpen = true } }
    BackHandler(state.selected != null && !state.editorOpen) { viewModel.closeDetail() }
    val canWrite = !state.busy && !state.uncertain && state.snapshot != null
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("库存管理", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = { viewModel.refresh() }, enabled = !state.busy) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新库存", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onSettings, enabled = !state.busy) {
                Icon(Icons.Filled.Settings, contentDescription = "连接设置", tint = MaterialTheme.colorScheme.primary)
            }
        }
        state.snapshot?.let { snapshot ->
            Text("${snapshot.url} · v${snapshot.version}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } ?: Text("尚未连接服务器，请设置地址后刷新", style = MaterialTheme.typography.bodySmall)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { error ->
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(error, style = MaterialTheme.typography.bodySmall)
                    if (state.uncertain) Text("请先刷新核对实际结果，确认前已禁用保存。", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { viewModel.refresh() }, enabled = !state.busy) { Text("刷新并核对") }
                }
            }
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
        val selected = state.selected
        if (selected != null && !state.editorOpen) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = { viewModel.closeDetail() }, enabled = !state.busy) { Text("← 返回列表") }
                Text(selected.inventoryTitle(state.selectedEntity), style = MaterialTheme.typography.titleLarge)
                if (state.selectedEntity == "spool") {
                    val filament = selected.child("filament")
                    Text("${filament.child("vendor").text("name")} · ${filament.text("material")} · ${if (selected.isArchived()) "已归档" else "在库"}")
                    Text("剩余净重 ${grams(selected.number("remaining_weight"))} g", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    Text("空盘 ${grams(selected.number("spool_weight"))} g · 已用 ${grams(selected.number("used_weight"))} g", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("位置：${selected.text("location").ifBlank { "未设置" }}\n批次：${selected.text("lot_nr").ifBlank { "未设置" }}\n备注：${selected.text("comment").ifBlank { "无" }}")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { weightMode = true }, enabled = canWrite) { Text("毛重称量") }
                        OutlinedButton(onClick = { weightMode = false }, enabled = canWrite) { Text("记录消耗") }
                    }
                    if (onPrint != null) Button(onClick = {
                        viewModel.preparePrint { record, sourceUrl -> onPrint(Gson().fromJson(record, SpoolmanSpool::class.java), sourceUrl) }
                    }, enabled = canWrite, modifier = Modifier.fillMaxWidth()) { Text("打印此卷标签") }
                    Text("NFC UID 绑定", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    val existing = runCatching { InventoryUids.decode(selected).joinToString(", ") }
                    Text(existing.getOrElse { "数据异常：${it.message}" }.ifBlank { "尚未绑定" })
                    OutlinedTextField(value = uid, onValueChange = { uid = it }, label = { Text("扫描或输入 UID") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), singleLine = true, enabled = !state.busy)
                    if (onScanUid != null) OutlinedButton(onClick = onScanUid, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("扫描标签") }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { viewModel.lookup(uid) }, enabled = !state.busy && uid.isNotBlank()) { Text("查找") }
                        Button(onClick = { confirm = "bind" }, enabled = canWrite && uid.isNotBlank()) { Text("绑定") }
                        OutlinedButton(onClick = { confirm = "unbind" }, enabled = canWrite && uid.isNotBlank()) { Text("解绑") }
                    }
                    Text("标签已绑定其他耗材时，先解除原绑定。", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { confirm = "archive" }, enabled = canWrite) { Text(if (selected.isArchived()) "恢复到在库" else "归档此卷") }
                }
                if (state.selectedEntity != "spool") {
                    inventoryFields(state.selectedEntity).forEach { field ->
                        val value = if (field.key == "vendor_id") selected.child("vendor").text("name") else selected.text(field.key)
                        if (value.isNotBlank()) Text("${field.label}：$value")
                    }
                    val references = if (state.selectedEntity == "vendor") state.snapshot?.filaments.orEmpty().count { it.child("vendor").number("id")?.toInt() == selected.recordId() }
                        else state.snapshot?.spools.orEmpty().count { it.child("filament").number("id")?.toInt() == selected.recordId() }
                    Text(if (state.selectedEntity == "vendor") "关联耗材：$references 种" else "关联库存：$references 卷（含归档）", style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.edit(state.selectedEntity) }, enabled = canWrite) { Text("编辑") }
                    OutlinedButton(onClick = { confirm = "delete" }, enabled = canWrite) { Text("删除") }
                }
                TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级数据" else "高级数据") }
                if (advanced) Text(Gson().newBuilder().setPrettyPrinting().create().toJson(selected), style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("spool" to "库存", "filament" to "耗材", "vendor" to "厂商").forEach { (value, label) ->
                    FilterChip(selected = tab == value, onClick = { tab = value; query = "" }, label = { Text(label) }, colors = inventoryChipColors())
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { viewModel.edit(tab, create = true) }, enabled = canWrite, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("新建") }
            }
            state.snapshot?.let { snapshot ->
                val active = snapshot.spools.filterNot { it.isArchived() }
                Text("在库 ${active.size} 卷 · 归档 ${snapshot.spools.size - active.size} 卷 · 剩余 ${grams(active.sumOf { it.number("remaining_weight") ?: 0.0 })} g", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("搜索库存") }, placeholder = { Text("名称、材料、位置、批次或 UID", style = MaterialTheme.typography.bodySmall) }, leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), singleLine = true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (tab == "spool") TextButton(onClick = { archive = (archive + 1) % 3 }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text(listOf("在库", "含归档", "仅归档")[archive]) }
                TextButton(onClick = { sort = (sort + 1) % 3 }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text(listOf("编号 ↓", "编号 ↑", "名称 ↑")[sort]) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { uidSearchOpen = !uidSearchOpen }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text(if (uidSearchOpen) "收起 UID" else "查找 UID") }
                if (onScanUid != null) IconButton(onClick = onScanUid, enabled = !state.busy) {
                    Icon(Icons.Filled.Nfc, contentDescription = "扫描标签", tint = MaterialTheme.colorScheme.primary)
                }
            }
            if (uidSearchOpen) Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = uid, onValueChange = { uid = it }, label = { Text("UID（含归档）") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(20.dp), singleLine = true)
                IconButton(onClick = { viewModel.lookup(uid) }, enabled = !state.busy && uid.isNotBlank()) {
                    Icon(Icons.Filled.Search, contentDescription = "查找 UID", tint = MaterialTheme.colorScheme.primary)
                }
            }
            val records = state.snapshot?.let { when (tab) { "filament" -> it.filaments; "vendor" -> it.vendors; else -> it.spools } }.orEmpty()
                .filter { tab != "spool" || archive == 1 || it.isArchived() == (archive == 2) }
                .filter { query.isBlank() || it.toString().contains(query.trim(), ignoreCase = true) }
                .let { list -> when (sort) { 1 -> list.sortedBy { it.recordId() }; 2 -> list.sortedBy { it.inventoryTitle(tab).substringAfter(' ') }; else -> list.sortedByDescending { it.recordId() } } }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (records.isEmpty()) item { Text(if (state.busy) "正在获取库存…" else "没有匹配记录") }
                items(records, key = { "$tab-${it.recordId()}" }) { record ->
                    Card(modifier = Modifier.fillMaxWidth().clickable(enabled = !state.busy) { viewModel.select(tab, record.recordId()) }, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(record.inventoryTitle(tab), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (tab == "spool") {
                                Text("${record.child("filament").child("vendor").text("name")} · ${record.child("filament").text("material")} · ${grams(record.number("remaining_weight"))} g", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${record.text("location")} ${record.text("lot_nr")}${if (record.isArchived()) " · 已归档" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else if (tab == "filament") Text("${record.child("vendor").text("name")} · ${record.text("material")} · ${record.text("diameter")} mm")
                        }
                    }
                }
                item {
                    Text("更多服务器功能", style = MaterialTheme.typography.titleMedium)
                    Text("自定义字段定义、外部耗材库、导出、备份与服务器设置可在完整管理界面操作。", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { state.snapshot?.let { uriHandler.openUri(it.url + "/") } }, enabled = state.snapshot != null) { Text("完整管理") }
                        TextButton(onClick = { state.snapshot?.let { uriHandler.openUri(it.url + "/dashboard") } }, enabled = state.snapshot != null) { Text("统计") }
                        TextButton(onClick = { state.snapshot?.let { uriHandler.openUri(it.url + "/settings") } }, enabled = state.snapshot != null) { Text("服务器设置") }
                    }
                }
            }
        }
    }
    if (state.editorOpen) InventoryEditor(state, onClose = viewModel::closeEditor, onSave = viewModel::save)
    confirm?.let { action ->
        val description = when (action) {
            "delete" -> "确认删除 ${state.selected?.inventoryTitle(state.selectedEntity)}？此操作无法撤销。" + when (state.selectedEntity) { "vendor" -> "关联耗材的厂商引用将被清空。"; "filament" -> "如仍有库存引用该耗材，服务器可能拒绝删除。"; else -> "历史重量与 UID 关联也将随记录删除。" }
            "archive" -> "确认${if (state.selected?.isArchived() == true) "恢复" else "归档"}此卷？UID 关联会保留。"
            "bind" -> "将 UID $uid 绑定到 #${state.selected?.text("id")}？只更新服务器关联，不写入标签内存。"
            else -> "只从此卷移除 UID $uid，保留其他 UID 和自定义字段？"
        }
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text("确认操作") }, text = { Text(description) }, confirmButton = {
            TextButton(onClick = { confirm = null; when (action) { "delete" -> viewModel.delete(); "archive" -> viewModel.archive(); "bind" -> viewModel.uid(uid, false); else -> viewModel.uid(uid, true) } }, enabled = canWrite) { Text("确认") }
        }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } })
    }
    weightMode?.let { measure -> WeightDialog(measure, state.selected, onClose = { weightMode = null }, onSave = { grams -> weightMode = null; viewModel.weight(grams, measure) }) }
}

private fun grams(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "未知"

@Composable
private fun WeightDialog(measure: Boolean, spool: JsonObject?, onClose: () -> Unit, onSave: (Double) -> Unit) {
    var raw by remember { mutableStateOf("") }
    var kg by remember { mutableStateOf(false) }
    val value = raw.toDoubleOrNull()?.times(if (kg) 1000 else 1)?.takeIf { it.isFinite() && it >= 0 }
    val tare = spool?.number("spool_weight")
    val valid = value != null && if (measure) tare != null && value >= tare else value > 0
    AlertDialog(onDismissRequest = onClose, title = { Text(if (measure) "称量整卷毛重" else "记录本次消耗") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (measure) "输入秤上整卷重量，包含空盘。服务器将计算剩余净重。此卷空盘：${grams(tare)} g。" else "输入本次消耗的净重。这是扣减量，不是剩余量；请求结果未知时请先核对。")
            if (measure && tare == null) Text("尚未明确设置此卷空盘重量。请取消后编辑空盘重量，再称量。", color = MaterialTheme.colorScheme.error)
            OutlinedTextField(value = raw, onValueChange = { raw = it }, label = { Text(if (kg) "千克 kg" else "克 g") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            Row { Checkbox(checked = kg, onCheckedChange = { kg = it }); Text("输入单位为千克（kg）") }
            if (value != null && measure && tare != null) Text("预计剩余净重：${grams(value - tare)} g（最终以服务器为准）")
        }
    }, confirmButton = { TextButton(onClick = { value?.let(onSave) }, enabled = valid) { Text("确认提交") } }, dismissButton = { TextButton(onClick = onClose) { Text("取消") } })
}

@Composable
private fun InventoryEditor(state: InventoryUiState, onClose: () -> Unit, onSave: (JsonObject) -> Unit) {
    val baseline = if (state.creating) null else state.selected
    val fields = remember(state.selectedEntity) { inventoryFields(state.selectedEntity) }
    val values = remember(baseline, state.creating) { mutableStateMapOf<String, String>().apply { fields.forEach { put(it.key, formValue(baseline, it.key)) } } }
    val extras = remember(baseline, state.fields) { mutableStateMapOf<String, String>().apply {
        state.fields.forEach { field -> field.text("key").takeIf { it.isNotBlank() && it != "card_uids" }?.let { put(it, "") } }
        baseline?.child("extra")?.entrySet()?.forEach { (key, value) -> if (key != "card_uids") put(key, value.takeUnless { it.isJsonNull }?.asString.orEmpty()) }
    } }
    val cleared = remember { mutableStateListOf<String>() }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = { if (!state.busy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
            Column(Modifier.imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${if (state.creating) "新建" else "编辑"}${when (state.selectedEntity) { "vendor" -> "厂商"; "filament" -> "耗材"; else -> "库存" }}", style = MaterialTheme.typography.headlineSmall)
                if (state.selectedEntity == "filament" && !state.creating) Text("这是共享耗材记录；修改会影响所有引用它的库存。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text("仅保存修改过的字段。清空已有可选字段会设为空值；剩余重量仅在你修改该项时提交。", style = MaterialTheme.typography.bodySmall)
                (error ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    fields.forEach { field ->
                        if (field.key in setOf("filament_id", "vendor_id")) {
                            val options = if (field.key == "filament_id") state.snapshot?.filaments.orEmpty() else state.snapshot?.vendors.orEmpty()
                            ReferencePicker(field, values[field.key].orEmpty(), options, !state.busy) { values[field.key] = it }
                        } else OutlinedTextField(value = values[field.key].orEmpty(), onValueChange = { values[field.key] = it }, label = { Text(field.label + if (field.required) " *" else "") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), minLines = if (field.key == "comment") 2 else 1, keyboardOptions = KeyboardOptions(keyboardType = if (field.number) KeyboardType.Decimal else KeyboardType.Text))
                    }
                    if (extras.isNotEmpty()) {
                        Text("自定义字段", style = MaterialTheme.typography.titleMedium)
                        Text("按服务器 JSON 值编辑：文本用双引号包围，例如 \"已烘干\"；数字直接输入。嵌套 JSON 文本仍须编码成字符串。仅发送改变的键。", style = MaterialTheme.typography.bodySmall)
                    }
                    extras.keys.sorted().forEach { key ->
                        OutlinedTextField(value = extras[key].orEmpty(), onValueChange = { extras[key] = it }, label = { Text(key) }, enabled = !state.busy && key !in cleared, modifier = Modifier.fillMaxWidth())
                        Row { Checkbox(checked = key in cleared, onCheckedChange = { if (it) cleared.add(key) else cleared.remove(key) }, enabled = !state.busy); Text("清除此字段") }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose, enabled = !state.busy) { Text("取消") }
                    Button(onClick = { try { val body = formBody(state.selectedEntity, baseline, values, extras, cleared.toSet()); error = null; onSave(body) } catch (e: Exception) { error = e.message } }, enabled = !state.busy && !state.uncertain) { Text("保存") }
                }
            }
        }
    }
}

@Composable
private fun ReferencePicker(field: InventoryField, value: String, options: List<JsonObject>, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = options.find { it.text("id") == value }
    Column {
        Text(field.label + if (field.required) " *" else "", style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(selected?.inventoryTitle(if (field.key == "filament_id") "filament" else "vendor") ?: if (value.isBlank()) "请选择" else "#$value") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                if (!field.required) DropdownMenuItem(text = { Text("无厂商") }, onClick = { expanded = false; onSelect("") })
                options.forEach { record -> DropdownMenuItem(text = { Text(record.inventoryTitle(if (field.key == "filament_id") "filament" else "vendor")) }, onClick = { expanded = false; onSelect(record.text("id")) }) }
            }
        }
        if (options.isEmpty()) Text("请先在对应列表中新建记录", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun inventoryChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
)
