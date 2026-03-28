package dev.animeshvarma.atropos.ui.screens

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.animeshvarma.atropos.AtroposViewModel
import dev.animeshvarma.atropos.camera.CameraPreviewBase
import dev.animeshvarma.atropos.camera.VideoBufferManager
import dev.animeshvarma.atropos.model.AppMode
import dev.animeshvarma.atropos.ui.components.LiquidRecordButtons
import dev.animeshvarma.atropos.ui.components.SnapshotPromptDialog
import dev.animeshvarma.atropos.ui.components.StopWarningDialog
import dev.animeshvarma.atropos.ui.components.formatTime

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AtroposAppOrchestrator(viewModel: AtroposViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val bufferManager = remember { VideoBufferManager(context) }

    // UI Flags for floating dialogs over the active background recording
    var showSnapshotDialog by remember { mutableStateOf(false) }
    var showStopDialog by remember { mutableStateOf(false) }

    // Floating Camera Controls State
    var isDrawerOpen by remember { mutableStateOf(false) }
    var isTorchOn by remember { mutableStateOf(false) }
    var showGrid by remember { mutableStateOf(false) }
    var isMicMuted by remember { mutableStateOf(false) }
    var showExposureSlider by remember { mutableStateOf(false) }

    var showCameraSettings by remember { mutableStateOf(false) }
    var showImageSettings by remember { mutableStateOf(false) }

    LaunchedEffect(bufferManager) {
        bufferManager.onSizeUpdated = { sizeMb -> viewModel.updateRealSize(sizeMb) }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // LAYER 1: The Camera Engine
        if (uiState.currentMode != AppMode.EDITOR) {
            CameraPreviewBase(
                uiState = uiState,
                bufferManager = bufferManager,
                isTorchOn = isTorchOn,
                showGridLines = showGrid,
                showExposureSlider = showExposureSlider
            )
        }

        // LAYER 2: The Pitch Black OLED Battery Saver
        AnimatedVisibility(
            visible = uiState.currentMode == AppMode.RECORDING_ASLEEP,
            enter = fadeIn(), exit = fadeOut()
        ) {
            val interactionSource = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) { viewModel.wakeUp() },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(32.dp),
                    modifier = Modifier.padding(bottom = 64.dp)
                ) {
                    LoadingIndicator(
                        modifier = Modifier.scale(1.4f).alpha(0.3f),
                        color = MaterialTheme.colorScheme.primary
                    )

                    Text(
                        text = "Recording...\nTap anywhere to wake screen",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                        color = Color.White.copy(alpha = 0.4f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }

        // LAYER 3: The Controls (HUD, Record Buttons, Side Panel)
        if (uiState.currentMode in listOf(AppMode.RECORDING_AWAKE, AppMode.RECORDING_ASLEEP, AppMode.IDLE)) {
            val isRecordingOrAsleep = uiState.currentMode != AppMode.IDLE

            Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {

                // --- TOP: HUD CHIP ---
                AnimatedVisibility(
                    visible = isRecordingOrAsleep, enter = fadeIn(), exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = "${formatTime(uiState.recordingDurationMs)} | ${formatTime(uiState.currentBufferDurationMs)}",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // --- RIGHT SIDE: PREMIUM M3 DRAWER ---
                // Only show side controls if awake or idle
                if (uiState.currentMode != AppMode.RECORDING_ASLEEP) {
                    Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {

                            // The Trigger Button (Sleek M3 FilledIconButton)
                            FilledIconButton(
                                onClick = { isDrawerOpen = !isDrawerOpen },
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                                    contentColor = MaterialTheme.colorScheme.onSurface
                                )
                            ) {
                                Icon(
                                    imageVector = if (isDrawerOpen) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                    contentDescription = "Toggle Camera Controls"
                                )
                            }

                            // The Expanding Control Pill
                            AnimatedVisibility(
                                visible = isDrawerOpen,
                                enter = expandHorizontally(expandFrom = Alignment.End) + fadeIn(),
                                exit = shrinkHorizontally(shrinkTowards = Alignment.End) + fadeOut()
                            ) {
                                Surface(
                                    modifier = Modifier.padding(start = 8.dp),
                                    shape = RoundedCornerShape(24.dp), // Premium pill shape
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
                                    tonalElevation = 6.dp
                                ) {
                                    Column(
                                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        IconButton(onClick = { isTorchOn = !isTorchOn }) {
                                            Icon(if (isTorchOn) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff, "Flash")
                                        }
                                        IconButton(onClick = {
                                            isMicMuted = !isMicMuted
                                            bufferManager.setMicMuted(isMicMuted)
                                        }) {
                                            Icon(if (isMicMuted) Icons.Default.MicOff else Icons.Default.Mic, "Microphone")
                                        }
                                        IconButton(onClick = { showGrid = !showGrid }) {
                                            Icon(
                                                Icons.Default.Grid4x4,
                                                "Grid",
                                                tint = if (showGrid) MaterialTheme.colorScheme.primary else LocalContentColor.current
                                            )
                                        }

                                        HorizontalDivider(modifier = Modifier.width(32.dp).padding(vertical = 8.dp))

                                        IconButton(onClick = { showCameraSettings = true; isDrawerOpen = false }) {
                                            Icon(Icons.Default.Settings, "Camera Settings")
                                        }
                                        IconButton(onClick = { showImageSettings = true; isDrawerOpen = false }) {
                                            Icon(Icons.Default.Tune, "Image Settings")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // --- BOTTOM: LIQUID RECORD BUTTONS ---
                Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)) {
                    LiquidRecordButtons(
                        isRecording = isRecordingOrAsleep,
                        onStart = { viewModel.startRecording() },
                        onStop = {
                            viewModel.wakeUp()
                            showStopDialog = true
                        },
                        onSnapshot = {
                            viewModel.wakeUp()
                            showSnapshotDialog = true
                        }
                    )
                }
            }
        }

        // LAYER 4: M3 Settings Dialogs
        // LAYER 4: M3 Settings Dialogs
        if (showCameraSettings) {
            AlertDialog(
                onDismissRequest = { showCameraSettings = false },
                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                title = { Text("Camera Settings") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Resolution (Applied when Idle)", style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("HD", "FHD", "4K").forEach { res ->
                                    FilterChip(
                                        selected = uiState.videoQuality == res,
                                        onClick = { viewModel.setVideoQuality(res) }, // Linked
                                        label = { Text(res) }
                                    )
                                }
                            }
                        }

                        // NEW: Frame Rate Selector
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Frame Rate", style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(24, 30, 60).forEach { fps ->
                                    FilterChip(
                                        selected = uiState.fps == fps,
                                        onClick = { viewModel.setFps(fps) },
                                        label = { Text("${fps}fps") }
                                    )
                                }
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Optical Stabilization", style = MaterialTheme.typography.bodyLarge)
                            Switch(checked = uiState.isOisEnabled, onCheckedChange = { viewModel.setOisEnabled(it) }) // Linked
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showCameraSettings = false }) { Text("Done") }
                }
            )
        }

        if (showImageSettings) {
            AlertDialog(
                onDismissRequest = { showImageSettings = false },
                icon = { Icon(Icons.Default.Tune, contentDescription = null) },
                title = { Text("Image Settings") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("10-Bit HDR Video", style = MaterialTheme.typography.bodyLarge)
                            Switch(checked = uiState.is10BitHdr, onCheckedChange = { viewModel.set10BitHdr(it) }) // Linked
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Exposure Slider Overlay", style = MaterialTheme.typography.bodyLarge)
                            Switch(checked = showExposureSlider, onCheckedChange = { showExposureSlider = it })
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showImageSettings = false }) { Text("Done") }
                }
            )
        }


        // LAYER 5: Action Dialogs & Editor
        if (showSnapshotDialog) {
            SnapshotPromptDialog(
                onEditSnippet = {
                    showSnapshotDialog = false
                    Toast.makeText(context, "Finalizing files, please wait...", Toast.LENGTH_SHORT).show()
                    bufferManager.pauseForSnapshot { viewModel.proceedToEditor() }
                },
                onSaveFullBuffer = {
                    showSnapshotDialog = false
                    Toast.makeText(context, "Saving Full Buffer to Gallery...", Toast.LENGTH_SHORT).show()
                    bufferManager.pauseForSnapshot {
                        bufferManager.exportBufferToGallery {
                            viewModel.stopRecording()
                            Toast.makeText(context, "Saved Successfully!", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                onCancel = {
                    showSnapshotDialog = false
                    viewModel.startSleepTimer()
                }
            )
        }

        if (showStopDialog) {
            StopWarningDialog(
                onStopConfirm = {
                    showStopDialog = false
                    viewModel.stopRecording()
                },
                onCancel = {
                    showStopDialog = false
                    viewModel.startSleepTimer()
                }
            )
        }

        if (uiState.currentMode == AppMode.EDITOR) {
            EditorScreen(
                uiState = uiState,
                bufferFiles = bufferManager.getBufferedFiles(),
                onProceed = { viewModel.stopRecording() },
                onCutFurther = { }
            )
        }
    }
}