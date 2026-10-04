package com.spoolpainter.app.ui.screens.inventory

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Size
import android.util.Log
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.TorchState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
import androidx.core.view.doOnLayout
import androidx.lifecycle.Observer
import com.spoolpainter.app.BuildConfig
import com.spoolpainter.app.hardware.camera.QrFrameDecoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
                Text("将完整二维码放入画面；轻点二维码对焦，光线不足时可开启补光。", style = MaterialTheme.typography.bodyMedium)
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
    var cameraLabel by remember { mutableStateOf("正在连接相机…") }
    var focusStatus by remember { mutableStateOf("正在等待预览…") }
    var controlError by remember { mutableStateOf<String?>(null) }
    var hasFlash by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    var torchPending by remember { mutableStateOf(false) }
    var toggleTorch by remember { mutableStateOf<(() -> Unit)?>(null) }
    DisposableEffect(context, lifecycleOwner, previewView, frontCamera) {
        cameraError = null
        controlError = null
        cameraLabel = "正在连接相机…"
        focusStatus = "正在等待预览…"
        hasFlash = false
        torchOn = false
        torchPending = false
        toggleTorch = null
        val main = ContextCompat.getMainExecutor(context)
        val executor = Executors.newSingleThreadExecutor()
        val disposed = AtomicBoolean(false)
        val delivered = AtomicBoolean(false)
        val decoder = QrFrameDecoder()
        var provider: ProcessCameraProvider? = null
        var boundCamera: Camera? = null
        var focusSequence = 0
        var focusedOnPreview = false
        fun focusAt(x: Float, y: Float) {
            val camera = boundCamera ?: return
            if (disposed.get() || previewView.width == 0 || previewView.height == 0) return
            val sequence = ++focusSequence
            try {
                // MotionEvent coordinates are relative to PreviewView itself.
                // Its factory accounts for crop, scale, sensor rotation and mirroring.
                val point = previewView.meteringPointFactory.createPoint(x, y)
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(5, TimeUnit.SECONDS).build()
                if (!camera.cameraInfo.isFocusMeteringSupported(action)) {
                    focusStatus = "此相机不支持该对焦位置，请调整距离或换用另一相机"
                    return
                }
                controlError = null
                focusStatus = "正在对焦与测光…"
                val result = camera.cameraControl.startFocusAndMetering(action)
                result.addListener({
                    if (!disposed.get() && sequence == focusSequence) {
                        try {
                            focusStatus = if (result.get().isFocusSuccessful) "已对焦，可轻点画面重新对焦" else "已测光；焦点未锁定，请调整距离后再轻点画面"
                        } catch (e: Exception) {
                            focusStatus = "对焦未完成"
                            controlError = "无法对焦：${(e.cause ?: e).message.orEmpty()}"
                        }
                    }
                }, main)
            } catch (e: Exception) {
                focusStatus = "对焦未完成"
                controlError = "无法对焦：${e.message.orEmpty()}"
            }
        }
        val torchObserver = Observer<Int> { state -> if (!disposed.get()) torchOn = state == TorchState.ON }
        val cameraObserver = Observer<CameraState> { state ->
            if (!disposed.get()) state?.error?.let { error ->
                cameraError = when (error.code) {
                    CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE -> "相机正被其他应用占用，请关闭其他相机应用后重试"
                    CameraState.ERROR_CAMERA_DISABLED -> "系统已停用相机，请检查设备权限或管理设置"
                    CameraState.ERROR_STREAM_CONFIG -> "此相机无法提供当前预览，请切换相机或关闭后重试"
                    else -> "相机发生错误（${error.code}）：${error.cause?.message.orEmpty()}"
                }
            }
        }
        val streamObserver = Observer<PreviewView.StreamState> { state ->
            if (state == PreviewView.StreamState.STREAMING && !focusedOnPreview && !disposed.get()) {
                focusedOnPreview = true
                previewView.doOnLayout {
                    if (!disposed.get()) focusAt(previewView.width / 2f, previewView.height / 2f)
                }
            }
        }
        previewView.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> true
                MotionEvent.ACTION_UP -> {
                    focusAt(event.x, event.y)
                    view.performClick()
                    true
                }
                else -> true
            }
        }
        val resolution = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER))
            .setResolutionFilter { sizes, _ ->
                sizes.filter { maxOf(it.width, it.height) <= 1280 && minOf(it.width, it.height) <= 720 }
            }
            .build()
        val preview = Preview.Builder().setResolutionSelector(resolution).build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolution)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        var lastFrame = 0L
        var lastDebugFrame = 0L
        analysis.setAnalyzer(executor) { image ->
            try {
                val now = SystemClock.elapsedRealtime()
                if (!disposed.get() && !delivered.get() && now - lastFrame >= 180) {
                    lastFrame = now
                    val plane = image.planes.first()
                    val started = SystemClock.elapsedRealtimeNanos()
                    val decoded = decoder.decode(plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride)
                    if (BuildConfig.DEBUG && (lastDebugFrame == 0L || now - lastDebugFrame >= 5000L)) {
                        lastDebugFrame = now
                        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000
                        Log.d("InventoryQrCamera", "frame=${image.width}x${image.height} rowStride=${plane.rowStride} pixelStride=${plane.pixelStride} rotation=${image.imageInfo.rotationDegrees} decodeMs=$elapsedMs detected=${decoded != null}")
                    }
                    decoded?.let { payload ->
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
                    val bound = camera.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                    boundCamera = bound
                    cameraLabel = if (bound.cameraInfo.lensFacing == CameraSelector.LENS_FACING_FRONT) "前置相机" else "后置相机"
                    hasFlash = bound.cameraInfo.hasFlashUnit()
                    bound.cameraInfo.torchState.observe(lifecycleOwner, torchObserver)
                    bound.cameraInfo.cameraState.observe(lifecycleOwner, cameraObserver)
                    previewView.previewStreamState.observe(lifecycleOwner, streamObserver)
                    toggleTorch = {
                        if (!disposed.get() && hasFlash && !torchPending) {
                            torchPending = true
                            controlError = null
                            try {
                                val result = bound.cameraControl.enableTorch(!torchOn)
                                result.addListener({
                                    if (!disposed.get()) {
                                        torchPending = false
                                        try { result.get() }
                                        catch (e: Exception) { controlError = "补光灯切换失败：${(e.cause ?: e).message.orEmpty()}" }
                                    }
                                }, main)
                            } catch (e: Exception) {
                                torchPending = false
                                controlError = "补光灯切换失败：${e.message.orEmpty()}"
                            }
                        }
                    }
                } catch (e: Exception) { cameraError = "无法打开相机：${e.message.orEmpty()}" }
            }
        }, main)
        onDispose {
            disposed.set(true)
            analysis.clearAnalyzer()
            previewView.setOnTouchListener(null)
            previewView.previewStreamState.removeObserver(streamObserver)
            boundCamera?.let { camera ->
                camera.cameraInfo.torchState.removeObserver(torchObserver)
                camera.cameraInfo.cameraState.removeObserver(cameraObserver)
                // Request torch-off before closing our camera use cases. CameraX
                // closes the camera on unbind even if this request is cancelled.
                if (camera.cameraInfo.hasFlashUnit()) runCatching { camera.cameraControl.enableTorch(false) }
                runCatching { camera.cameraControl.cancelFocusAndMetering() }
            }
            toggleTorch = null
            hasFlash = false
            torchOn = false
            torchPending = false
            try { provider?.unbind(preview, analysis) }
            finally { executor.shutdownNow() }
        }
    }
    Column(modifier) {
        AndroidView(factory = { previewView }, modifier = Modifier.weight(1f).fillMaxWidth())
        Text(cameraLabel + if (hasFlash) (if (torchOn) " · 补光已开启" else " · 可开启补光") else " · 无补光灯", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
        Text(focusStatus, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (hasFlash) OutlinedButton(onClick = { toggleTorch?.invoke() }, enabled = toggleTorch != null && !torchPending, modifier = Modifier.weight(1f)) {
                Text(if (torchPending) "切换中…" else if (torchOn) "关闭补光" else "开启补光")
            }
            if (canSwitchCamera) TextButton(onClick = { frontCamera = !frontCamera }, enabled = !torchPending, modifier = Modifier.weight(1f)) { Text("切换相机") }
        }
        controlError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }
        cameraError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
    }
}
