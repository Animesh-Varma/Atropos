package dev.animeshvarma.atropos.camera

import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.animeshvarma.atropos.model.AppMode

@Composable
fun CameraPreviewBase(
    currentMode: AppMode,
    bufferManager: VideoBufferManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isCameraReady by remember { mutableStateOf(false) }
    var cameraProviderRef by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var previewUseCaseRef by remember { mutableStateOf<Preview?>(null) }

    LaunchedEffect(currentMode, isCameraReady) {
        if (isCameraReady && cameraProviderRef != null) {
            when (currentMode) {
                AppMode.RECORDING_AWAKE -> {
                    bufferManager.startRollingBuffer()
                }
                AppMode.RECORDING_ASLEEP -> {
                    // FIX: DO NOT unbind the preview!
                    // Android Camera hardware requires an active Preview surface to keep sending frames.
                    // If we unbind it, the sensor sleeps and all future background chunks yield 0 bytes!
                    // The black overlay in Compose is enough to turn off OLED pixels and save battery.
                }
                AppMode.PAUSED_PROMPT -> bufferManager.pauseForSnapshot {}
                AppMode.IDLE -> {
                    bufferManager.stopRecording()
                    bufferManager.clearBuffer()
                }
                AppMode.EDITOR -> { }
            }
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                cameraProviderRef = cameraProvider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                previewUseCaseRef = preview

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, bufferManager.videoCapture
                    )
                    isCameraReady = true
                } catch (exc: Exception) { exc.printStackTrace() }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        }
    )
}