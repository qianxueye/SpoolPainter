package com.spoolpainter.app.ui.screens.inventory

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.spoolpainter.app.hardware.camera.QrFrameDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Uses the app's existing CameraX permission/preview infrastructure and ZXing dependency. */
@Composable
internal fun InventoryQrScanner(onQr: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var permitted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var permissionFinished by remember { mutableStateOf(permitted) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permitted = it
        permissionFinished = true
    }
    LaunchedEffect(Unit) { if (!permitted) permission.launch(Manifest.permission.CAMERA) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("扫描库存二维码", style = MaterialTheme.typography.titleLarge)
                Text("将完整二维码放入画面，识别后打开对应库存。", style = MaterialTheme.typography.bodyMedium)
                if (permitted) CameraQrPreview(Modifier.weight(1f).fillMaxWidth(), onQr)
                else {
                    Spacer(Modifier.weight(1f))
                    Text(if (permissionFinished) "扫描二维码需要相机权限。也可以返回库存手动搜索。" else "正在请求相机权限…")
                    if (permissionFinished) OutlinedButton(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("允许使用相机") }
                    Spacer(Modifier.weight(1f))
                }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("取消") }
            }
        }
    }
}

@Composable
private fun CameraQrPreview(modifier: Modifier, onQr: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnQr by rememberUpdatedState(onQr)
    val previewView = remember(context) { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var frontCamera by remember { mutableStateOf(false) }
    var canSwitchCamera by remember { mutableStateOf(false) }
    DisposableEffect(context, lifecycleOwner, previewView, frontCamera) {
        cameraError = null
        val main = ContextCompat.getMainExecutor(context)
        val executor = Executors.newSingleThreadExecutor()
        val disposed = AtomicBoolean(false)
        val delivered = AtomicBoolean(false)
        val decoder = QrFrameDecoder()
        var provider: ProcessCameraProvider? = null
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(1280, 720))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        var lastFrame = 0L
        analysis.setAnalyzer(executor) { image ->
            try {
                val now = SystemClock.elapsedRealtime()
                if (!disposed.get() && !delivered.get() && now - lastFrame >= 180) {
                    lastFrame = now
                    val plane = image.planes.first()
                    decoder.decode(plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride)?.let { payload ->
                        if (delivered.compareAndSet(false, true)) main.execute { if (!disposed.get()) latestOnQr(payload) }
                    }
                }
            } catch (e: Exception) {
                main.execute { if (!disposed.get()) cameraError = "无法读取相机画面：${e.message.orEmpty()}" }
            } finally { image.close() }
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!disposed.get()) {
                try {
                    val camera = future.get()
                    provider = camera
                    val hasBack = camera.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                    val hasFront = camera.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                    canSwitchCamera = hasBack && hasFront
                    val selector = when {
                        frontCamera && hasFront -> CameraSelector.DEFAULT_FRONT_CAMERA
                        hasBack -> CameraSelector.DEFAULT_BACK_CAMERA
                        hasFront -> CameraSelector.DEFAULT_FRONT_CAMERA
                        else -> error("设备没有可用相机")
                    }
                    camera.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                } catch (e: Exception) { cameraError = "无法打开相机：${e.message.orEmpty()}" }
            }
        }, main)
        onDispose {
            disposed.set(true)
            analysis.clearAnalyzer()
            try { provider?.unbind(preview, analysis) }
            finally { executor.shutdownNow() }
        }
    }
    Column(modifier) {
        AndroidView(factory = { previewView }, modifier = Modifier.weight(1f).fillMaxWidth())
        if (canSwitchCamera) TextButton(onClick = { frontCamera = !frontCamera }, modifier = Modifier.fillMaxWidth()) { Text("切换相机") }
        cameraError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
    }
}
