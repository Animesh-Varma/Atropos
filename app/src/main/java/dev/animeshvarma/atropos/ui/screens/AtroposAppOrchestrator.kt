package dev.animeshvarma.atropos.ui.screens

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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

    LaunchedEffect(bufferManager) {
        bufferManager.onSizeUpdated = { sizeMb -> viewModel.updateRealSize(sizeMb) }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // LAYER 1: The Camera Engine
        if (uiState.currentMode != AppMode.EDITOR) {
            CameraPreviewBase(currentMode = uiState.currentMode, bufferManager = bufferManager)
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
                    .background(Color.Black) // 100% Black turns off OLED pixels entirely
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null // Removes the tap ripple for a cleaner feel
                    ) { viewModel.wakeUp() },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(32.dp),
                    // Push it slightly up so it doesn't crowd the bottom buttons
                    modifier = Modifier.padding(bottom = 64.dp)
                ) {
                    // Brand new Expressive M3 Loader (Organic/Morphing)
                    LoadingIndicator(
                        modifier = Modifier
                            .scale(1.4f)
                            .alpha(0.3f), // Semi-transparent heartbeat so main buttons pop
                        color = MaterialTheme.colorScheme.primary
                    )

                    Text(
                        text = "Recording...\nTap anywhere to wake screen",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                        color = Color.White.copy(alpha = 0.4f), // Dimmed text
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }

        // LAYER 3: The Controls (Always floats on top of the black box!)
        if (uiState.currentMode in listOf(AppMode.RECORDING_AWAKE, AppMode.RECORDING_ASLEEP, AppMode.IDLE)) {
            val isRecordingOrAsleep = uiState.currentMode != AppMode.IDLE

            Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                // HUD CHIP
                AnimatedVisibility(
                    visible = isRecordingOrAsleep, enter = fadeIn(), exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp)
                ) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = "${formatTime(uiState.recordingDurationMs)} | ${formatTime(uiState.currentBufferDurationMs)}",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White
                        )
                    }
                }

                // LIQUID RECORD BUTTONS
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

        // LAYER 4: Dialogs & Editor
        if (showSnapshotDialog) {
            SnapshotPromptDialog(
                onEditSnippet = {
                    showSnapshotDialog = false
                    Toast.makeText(context, "Finalizing files, please wait...", Toast.LENGTH_SHORT).show()
                    bufferManager.pauseForSnapshot {
                        viewModel.proceedToEditor()
                    }
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

        // Editor Screen
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