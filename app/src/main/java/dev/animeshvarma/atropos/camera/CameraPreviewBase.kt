package dev.animeshvarma.atropos.camera

import android.hardware.camera2.CaptureRequest
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.animeshvarma.atropos.model.AppMode
import dev.animeshvarma.atropos.model.AtroposUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraPreviewBase(
    uiState: AtroposUiState,
    bufferManager: VideoBufferManager,
    isTorchOn: Boolean = false,
    showGridLines: Boolean = false,
    showExposureSlider: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var isCameraReady by remember { mutableStateOf(false) }
    var cameraProviderRef by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var cameraControlRef by remember { mutableStateOf<CameraControl?>(null) }
    var cameraInfoRef by remember { mutableStateOf<CameraInfo?>(null) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }

    // Gesture States
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var showZoomPill by remember { mutableStateOf(false) }
    var focusOffset by remember { mutableStateOf<Offset?>(null) }
    var exposureValue by remember { mutableFloatStateOf(0.5f) } // Default middle

    // 1. GAPLESS LOOP & MODE MANAGER
    LaunchedEffect(uiState.currentMode, isCameraReady) {
        if (isCameraReady && cameraProviderRef != null) {
            when (uiState.currentMode) {
                AppMode.RECORDING_AWAKE -> bufferManager.startRollingBuffer()
                AppMode.RECORDING_ASLEEP -> { /* Keep active */ }
                AppMode.PAUSED_PROMPT -> bufferManager.pauseForSnapshot {}
                AppMode.IDLE -> {
                    bufferManager.stopRecording()
                    bufferManager.clearBuffer()
                }
                AppMode.EDITOR -> { }
            }
        }
    }

    // 2. HARDWARE REBINDER (Only runs when IDLE for 4K and HDR rebuilding)
    LaunchedEffect(uiState.videoQuality, uiState.is10BitHdr) {
        if (uiState.currentMode == AppMode.IDLE && isCameraReady && cameraProviderRef != null && previewViewRef != null) {
            bufferManager.updateHardwareConfig(uiState.videoQuality, uiState.is10BitHdr)

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewViewRef!!.surfaceProvider)
            }
            try {
                cameraProviderRef?.unbindAll()
                val camera = cameraProviderRef?.bindToLifecycle(
                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, bufferManager.videoCapture
                )
                cameraControlRef = camera?.cameraControl
                cameraInfoRef = camera?.cameraInfo
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    // 3. PIXEL 9 NATIVE HARDWARE CONTROLS (OIS & FPS via Camera2Interop)
    // Runs instantly without breaking the recording loop!
    LaunchedEffect(uiState.fps, uiState.isOisEnabled, cameraControlRef) {
        cameraControlRef?.let { control ->
            try {
                val camera2Control = Camera2CameraControl.from(control)
                val optionsBuilder = CaptureRequestOptions.Builder()

                // Force Target FPS Range natively
                optionsBuilder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    android.util.Range(uiState.fps, uiState.fps)
                )

                // Force Optical and Video Stabilization directly on the lens
                val oisMode = if (uiState.isOisEnabled) CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON else CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                val videoStabMode = if (uiState.isOisEnabled) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF

                optionsBuilder.setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, oisMode)
                optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, videoStabMode)

                camera2Control.setCaptureRequestOptions(optionsBuilder.build())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    LaunchedEffect(isTorchOn, cameraControlRef) { cameraControlRef?.enableTorch(isTorchOn) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoomMultiplier, _ ->
                    val currentZoom = cameraInfoRef?.zoomState?.value?.zoomRatio ?: 1f
                    val minZoom = cameraInfoRef?.zoomState?.value?.minZoomRatio ?: 1f
                    val maxZoom = cameraInfoRef?.zoomState?.value?.maxZoomRatio ?: 5f
                    zoomRatio = (currentZoom * zoomMultiplier).coerceIn(minZoom, maxZoom)
                    cameraControlRef?.setZoomRatio(zoomRatio)
                    showZoomPill = true
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { offset ->
                    focusOffset = offset

                    // NEW: Reset Exposure UI and Hardware
                    exposureValue = 0.5f
                    cameraControlRef?.setExposureCompensationIndex(0)

                    val factory = SurfaceOrientedMeteringPointFactory(size.width.toFloat(), size.height.toFloat())
                    val point = factory.createPoint(offset.x, offset.y)
                    val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE).build()
                    cameraControlRef?.startFocusAndMetering(action)

                    scope.launch { delay(2000); focusOffset = null }
                })
            }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
                previewViewRef = previewView

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    cameraProviderRef = cameraProvider

                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    try {
                        cameraProvider.unbindAll()
                        val camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                            preview, bufferManager.videoCapture
                        )
                        cameraControlRef = camera.cameraControl
                        cameraInfoRef = camera.cameraInfo
                        isCameraReady = true
                    } catch (exc: Exception) { exc.printStackTrace() }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            }
        )

        // Grid Lines...
        if (showGridLines) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val pC = Color.White.copy(alpha = 0.3f)
                drawLine(pC, Offset(size.width/3, 0f), Offset(size.width/3, size.height), 2f)
                drawLine(pC, Offset(size.width*2/3, 0f), Offset(size.width*2/3, size.height), 2f)
                drawLine(pC, Offset(0f, size.height/3), Offset(size.width, size.height/3), 2f)
                drawLine(pC, Offset(0f, size.height*2/3), Offset(size.width, size.height*2/3), 2f)
            }
        }

        // Tap to Focus Ring...
        focusOffset?.let { offset ->
            val ringAlpha = remember { Animatable(1f) }
            LaunchedEffect(offset) { delay(1200); ringAlpha.animateTo(0f, tween(500)) }
            Box(modifier = Modifier.offset(x = (offset.x - 30).dp, y = (offset.y - 30).dp).size(60.dp).border(2.dp, Color.Yellow.copy(alpha = ringAlpha.value), CircleShape))
        }

        // FIXED: Zoom Pill is pushed way up (200.dp) to clear recording buttons
        AnimatedVisibility(
            visible = showZoomPill,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 200.dp)
        ) {
            LaunchedEffect(zoomRatio) { delay(1500); showZoomPill = false }
            Surface(color = Color.Black.copy(alpha = 0.6f), shape = RoundedCornerShape(16.dp)) {
                Text(
                    text = String.format("%.1fx", zoomRatio),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        // FIXED: Exposure Slider is pushed up (140.dp) to clear recording buttons
        if (showExposureSlider) {
            Slider(
                value = exposureValue,
                onValueChange = {
                    exposureValue = it
                    val range = cameraInfoRef?.exposureState?.exposureCompensationRange
                    if (range != null && range.lower != range.upper) {
                        val index = (range.lower + (it * (range.upper - range.lower))).toInt()
                        cameraControlRef?.setExposureCompensationIndex(index)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 140.dp)
                    .fillMaxWidth(0.6f),
                colors = SliderDefaults.colors(
                    thumbColor = Color.Yellow,
                    activeTrackColor = Color.Yellow,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                )
            )
        }
    }
}