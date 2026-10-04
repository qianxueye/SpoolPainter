package com.spoolpainter.app.ui.screens.printing

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spoolpainter.app.hardware.printer.LabelQrMode
import com.spoolpainter.app.hardware.printer.PaperTail
import com.spoolpainter.app.hardware.printer.PrintRequest
import com.spoolpainter.app.hardware.printer.PrintState
import com.spoolpainter.app.hardware.printer.qrBitmap
import com.spoolpainter.app.hardware.printer.renderLabelBitmap

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrintingScreen(
    request: PrintRequest?,
    onBack: () -> Unit,
    onSelectSpool: () -> Unit = {},
    viewModel: PrintingViewModel = hiltViewModel(),
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val lifecycle = lifecycleOwner.lifecycle
        val observer = LifecycleEventObserver { _, _ ->
            viewModel.setVisible(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        }
        lifecycle.addObserver(observer)
        viewModel.setVisible(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose {
            lifecycle.removeObserver(observer)
            viewModel.setVisible(false)
        }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val tail by viewModel.tail.collectAsStateWithLifecycle()
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    val preparation by viewModel.preparation.collectAsStateWithLifecycle()
    val busy = pending > 0 || preparation == PrintPreparationState.Loading
    val view = LocalView.current
    DisposableEffect(view, busy) {
        val previous = view.keepScreenOn
        if (busy) view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    var mode by remember(request) { mutableStateOf(request?.qrMode ?: LabelQrMode.WEB) }
    var editorOpen by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<PaperTemplateProfile?>(null) }
    var deletingProfile by remember { mutableStateOf<PaperTemplateProfile?>(null) }
    var templateError by remember { mutableStateOf<String?>(null) }
    val selected = request?.copy(qrMode = mode, paper = templates.selected?.paper)
    val prepared = preparation as? PrintPreparationState.Ready
    val displayed = if (prepared?.original == selected) prepared?.fresh ?: selected else selected
    val receiptPreview = remember(displayed) { runCatching { displayed?.takeIf { it.paper == null }?.qrPayloads()?.map { it to qrBitmap(it).asImageBitmap() }.orEmpty() } }
    val fixedPreview = remember(displayed) { runCatching { displayed?.takeIf { it.paper != null }?.let(::renderLabelBitmap) } }
    val previewError = if (selected?.paper != null) fixedPreview.exceptionOrNull() else receiptPreview.exceptionOrNull()
    val previewReady = selected != null && if (selected.paper != null) fixedPreview.getOrNull() != null else receiptPreview.isSuccess
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("打印耗材标签", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onBack) { Text("返回") }
        }
        OutlinedButton(onClick = onSelectSpool, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (selected == null) "选择耗材" else "更换耗材") }
        PaperTemplateSelector(
            selection = templates, enabled = !busy,
            onSelect = { id -> try { viewModel.selectTemplate(id); templateError = null } catch (e: Exception) { templateError = e.message } },
            onAdd = { editingProfile = null; editorOpen = true },
            onEdit = { editingProfile = templates.selected; editorOpen = true },
            onDelete = { deletingProfile = templates.selected },
        )
        templateError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (selected == null) {
            Text("请从库存选择一卷耗材，再预览和打印标签。")
        } else {
            Text("二维码内容", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                LabelQrMode.entries.forEach { option ->
                    FilterChip(selected = mode == option, onClick = { mode = option }, label = { Text(option.title) }, enabled = !busy, colors = printChipColors())
                }
            }
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (selected.paper == null) "小票内容预览" else "标签实际排版预览", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (selected.paper != null) {
                        fixedPreview.getOrNull()?.let { bitmap ->
                            Surface(color = Color.White, shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth()) {
                                Image(bitmap.asImageBitmap(), contentDescription = "当前纸张模板的实际标签排版", modifier = Modifier.fillMaxWidth())
                            }
                        }
                        Text("按尺寸走纸；首张标签请人工对齐。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        displayed!!.textLines().forEachIndexed { index, line -> Text(line, style = if (index == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium, color = if (index == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant) }
                        receiptPreview.getOrNull()?.forEach { (payload, bitmap) ->
                            Surface(color = Color.White, shape = RoundedCornerShape(12.dp), modifier = Modifier.align(Alignment.CenterHorizontally)) {
                                Image(bitmap, contentDescription = "二维码：$payload", modifier = Modifier.padding(10.dp).size(180.dp))
                            }
                            Text(payload, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Text("小票预览显示打印内容，实际字距与排版由打印机决定。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    previewError?.let { Text("无法打印此模板：${it.message ?: "内容超出标签，请增大尺寸、减小字号或减少字段"}", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        if (templates.selected == null) {
            Text("纸尾长度 · 自动保存", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaperTail.entries.forEach { option -> FilterChip(selected = tail == option, onClick = { viewModel.setTail(option) }, enabled = !busy, label = { Text(option.title) }, colors = printChipColors()) }
            }
        } else if (selected == null) {
            Text("按尺寸走纸；首张标签请人工对齐。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val preparationFailure = preparation as? PrintPreparationState.Failed
        val message = if (preparation == PrintPreparationState.Loading) "正在读取最新耗材信息…"
        else if (preparationFailure != null) preparationFailure.message
        else when (val current = state) {
            PrintState.Idle -> "确认预览后，点击打印。"
            is PrintState.Working -> "耗材 #${current.spoolId}：${current.message}"
            is PrintState.Finished -> "耗材 #${current.spoolId} 打印完成。"
            is PrintState.Failed -> current.message
            is PrintState.Uncertain -> "耗材 #${current.spoolId} 打印结果暂时无法确认。请查看纸张；为避免重复打印，正在等待原任务结束。若一直没有响应，请确认纸张后重启应用。"
        }
        val isFailure = preparationFailure != null || state is PrintState.Failed || state is PrintState.Uncertain
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(
            containerColor = if (isFailure) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (isFailure) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
        )) {
            Text(message, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
        }
        Button(
            onClick = { selected?.let(viewModel::print) },
            enabled = previewReady && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (preparationFailure != null) "重新读取并打印" else if (state is PrintState.Failed) "检查纸张后重新打印" else "读取最新信息并打印") }
    }
    if (editorOpen) PaperTemplateEditor(
        profile = editingProfile,
        onClose = { editorOpen = false },
        onSave = viewModel::saveTemplate,
    )
    deletingProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = { deletingProfile = null },
            title = { Text("删除纸张模板？") },
            text = { Text("删除“${profile.paper.name}”后将切换为小票纸。") },
            confirmButton = { TextButton(onClick = {
                try { viewModel.deleteTemplate(profile.id); templateError = null } catch (e: Exception) { templateError = e.message }
                deletingProfile = null
            }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deletingProfile = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun printChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
)
