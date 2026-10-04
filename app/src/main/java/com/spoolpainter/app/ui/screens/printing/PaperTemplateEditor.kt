package com.spoolpainter.app.ui.screens.printing

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.spoolpainter.app.hardware.printer.LabelField
import com.spoolpainter.app.hardware.printer.PaperTemplate

@Composable
internal fun PaperTemplateSelector(
    selection: PaperTemplateSelection,
    enabled: Boolean,
    onSelect: (String?) -> Unit,
    onAdd: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("纸张模板", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Text(selection.selected?.paper?.name ?: "小票纸", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Filled.ExpandMore, contentDescription = "选择纸张模板")
                }
                DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 300.dp)) {
                    DropdownMenuItem(text = { Text("小票纸（连续纸）") }, onClick = { expanded = false; onSelect(null) })
                    selection.profiles.forEach { profile ->
                        DropdownMenuItem(text = { Text(profile.paper.name) }, onClick = { expanded = false; onSelect(profile.id) })
                    }
                }
            }
            IconButton(onClick = onAdd, enabled = enabled) { Icon(Icons.Filled.Add, contentDescription = "新建纸张模板") }
            if (selection.selected != null) {
                IconButton(onClick = onEdit, enabled = enabled) { Icon(Icons.Filled.Edit, contentDescription = "编辑纸张模板") }
                IconButton(onClick = onDelete, enabled = enabled) { Icon(Icons.Filled.Delete, contentDescription = "删除纸张模板") }
            }
        }
        selection.selected?.paper?.let { paper ->
            Text("打印区域 ${compactMm(paper.widthMm)} × ${compactMm(paper.heightMm)} mm · 间隙 ${compactMm(paper.gapMm)} mm", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PaperTemplateEditor(
    profile: PaperTemplateProfile?,
    onClose: () -> Unit,
    onSave: (String?, PaperTemplate) -> Unit,
) {
    val original = remember(profile) { profile?.paper ?: PaperTemplate() }
    var name by remember(original) { mutableStateOf(original.name) }
    val values = remember(original) { mutableStateMapOf(
        "width" to compactMm(original.widthMm), "height" to compactMm(original.heightMm),
        "gap" to compactMm(original.gapMm), "margin" to compactMm(original.marginMm),
        "offset_x" to compactMm(original.offsetXmm), "offset_y" to compactMm(original.offsetYmm),
        "font" to original.fontDots.toString(), "qr" to compactMm(original.qrMm),
    ) }
    var fields by remember(original) { mutableStateOf(original.selectedFields.toSet()) }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
            Column(Modifier.imePadding().systemBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (profile == null) "新建纸张模板" else "编辑纸张模板", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = onClose) { Text("取消") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("模板名称") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp))
                    Text("纸张尺寸", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TemplateNumberField("打印区域宽度 mm", values.getValue("width"), Modifier.weight(1f)) { values["width"] = it }
                        TemplateNumberField("标签高度 mm", values.getValue("height"), Modifier.weight(1f)) { values["height"] = it }
                    }
                    Text("打印区域宽度 20–48 mm；高度 20–200 mm。请按实际标签设置。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TemplateNumberField("标签间隙 mm", values.getValue("gap"), Modifier.weight(1f)) { values["gap"] = it }
                        TemplateNumberField("页边距 mm", values.getValue("margin"), Modifier.weight(1f)) { values["margin"] = it }
                    }
                    Text("位置微调", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TemplateNumberField("向右偏移 mm", values.getValue("offset_x"), Modifier.weight(1f)) { values["offset_x"] = it }
                        TemplateNumberField("向下偏移 mm", values.getValue("offset_y"), Modifier.weight(1f)) { values["offset_y"] = it }
                    }
                    Text("偏移可为 0，只能向右或向下；不会倒退送纸。首张标签请先人工对齐。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("文字与二维码", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TemplateNumberField("字号 px", values.getValue("font"), Modifier.weight(1f), integer = true) { values["font"] = it }
                        TemplateNumberField("二维码尺寸 mm", values.getValue("qr"), Modifier.weight(1f)) { values["qr"] = it }
                    }
                    Text("字号 12–48 px；二维码 10–44 mm，含留白。内容超出标签时会提示并禁用打印。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("显示字段", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LabelField.entries.forEach { field ->
                            FilterChip(selected = field in fields, onClick = { fields = if (field in fields) fields - field else fields + field }, label = { Text(field.title) }, colors = paperEditorChipColors())
                        }
                    }
                }
                error?.let { message ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer), shape = RoundedCornerShape(16.dp)) {
                        Text(message, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Button(onClick = {
                    try {
                        fun number(key: String): Double = values.getValue(key).trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw IllegalArgumentException("请填写有效的尺寸或字号")
                        val font = number("font")
                        require(font % 1.0 == 0.0 && font in 12.0..48.0) { "字号须为 12–48 的整数" }
                        val paper = PaperTemplate(
                            name = name.trim(), widthMm = number("width"), heightMm = number("height"), gapMm = number("gap"),
                            offsetXmm = number("offset_x"), offsetYmm = number("offset_y"), marginMm = number("margin"),
                            fontDots = font.toInt(), qrMm = number("qr"), selectedFields = fields.toSet(),
                        )
                        paper.validate()
                        onSave(profile?.id, paper)
                        onClose()
                    } catch (e: Exception) { error = e.message ?: "模板设置无效，请检查后重试" }
                }, modifier = Modifier.fillMaxWidth()) { Text("保存模板") }
            }
        }
    }
}

@Composable
private fun TemplateNumberField(label: String, value: String, modifier: Modifier, integer: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label, style = MaterialTheme.typography.labelMedium) }, singleLine = true,
        modifier = modifier, shape = RoundedCornerShape(20.dp), keyboardOptions = KeyboardOptions(keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal))
}

private fun compactMm(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

@Composable
private fun paperEditorChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
)
