package dev.animeshvarma.atropos.ui.screens

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import dev.animeshvarma.atropos.model.AtroposUiState
import dev.animeshvarma.atropos.ui.components.EditorSliders
import dev.animeshvarma.atropos.util.VideoExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun EditorScreen(
    uiState: AtroposUiState,
    bufferFiles: List<File>,
    onProceed: () -> Unit,
    onCutFurther: () -> Unit
) {
    val context = LocalContext.current

    var viewWindowStartMs by remember { mutableLongStateOf(0L) }
    var viewWindowEndMs by remember { mutableLongStateOf(0L) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var trimRange by remember { mutableStateOf(0f..100f) }

    var isInitialized by remember { mutableStateOf(false) }
    var showConfirmationDialog by remember { mutableStateOf(false) }

    // NEW: Store the duration of each chunk so we can translate Global <-> Local times
    var chunkDurations by remember { mutableStateOf<List<Long>>(emptyList()) }
    var totalDurationMs by remember { mutableLongStateOf(0L) }

    // 1. Math Phase: Calculate global duration across all chunks
    LaunchedEffect(bufferFiles) {
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            val durations = mutableListOf<Long>()
            var total = 0L
            for (file in bufferFiles) {
                try {
                    retriever.setDataSource(file.absolutePath)
                    val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val dur = durStr?.toLongOrNull() ?: 0L
                    durations.add(dur)
                    total += dur
                } catch (e: Exception) {
                    durations.add(0L) // Failsafe for broken files
                }
            }
            retriever.release()

            withContext(Dispatchers.Main) {
                chunkDurations = durations
                totalDurationMs = total

                // Initialize UI Bounds with the FULL duration
                if (total > 0 && !isInitialized) {
                    viewWindowEndMs = total
                    trimRange = 0f..total.toFloat()
                    isInitialized = true
                }
            }
        }
    }

    // Media3 ExoPlayer Setup
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            bufferFiles.forEach { file ->
                addMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            }
            prepare()
            playWhenReady = true
        }
    }

    // Helper: Seek global timeline -> ExoPlayer chunk + local position
    fun seekToGlobal(globalMs: Long) {
        var remaining = globalMs
        var windowIdx = 0
        for (i in chunkDurations.indices) {
            val dur = chunkDurations[i]
            if (remaining <= dur || i == chunkDurations.lastIndex) {
                windowIdx = i
                break
            }
            remaining -= dur
        }
        exoPlayer.seekTo(windowIdx, remaining)
    }

    // 2. Playback Phase: Enforce video loop & read global timeline
    LaunchedEffect(exoPlayer, isInitialized, chunkDurations) {
        while (true) {
            if (isInitialized && chunkDurations.isNotEmpty()) {
                val windowIndex = exoPlayer.currentMediaItemIndex
                val localPos = exoPlayer.currentPosition

                // Map local chunk position back to the Global UI Timeline
                var globalPos = 0L
                for (i in 0 until windowIndex) {
                    if (i < chunkDurations.size) {
                        globalPos += chunkDurations[i]
                    }
                }
                globalPos += localPos
                currentPositionMs = globalPos

                val cutStart = trimRange.start.toLong()
                val cutEnd = trimRange.endInclusive.toLong()

                if (exoPlayer.isPlaying) {
                    // Force loop based on the GLOBAL timeline
                    if (globalPos >= cutEnd) {
                        seekToGlobal(cutStart)
                    } else if (globalPos < cutStart - 500) {
                        seekToGlobal(cutStart)
                    }
                }
            }
            delay(50) // Smooth slider
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { exoPlayer.pause() }
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Video Player
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(bottom = 280.dp),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false // User uses our custom sliders instead!
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            }
        )

        // Controls
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 4.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {

                EditorSliders(
                    viewWindowStartMs = viewWindowStartMs,
                    viewWindowEndMs = viewWindowEndMs,
                    currentPositionMs = currentPositionMs,
                    onPositionChange = { newPos ->
                        currentPositionMs = newPos
                        seekToGlobal(newPos) // THE FIX: Translates slider position to ExoPlayer
                    },
                    trimRange = trimRange,
                    onTrimRangeChange = { trimRange = it }
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            viewWindowStartMs = trimRange.start.toLong()
                            viewWindowEndMs = trimRange.endInclusive.toLong()
                            seekToGlobal(viewWindowStartMs) // THE FIX
                            onCutFurther()
                        },
                        modifier = Modifier.weight(1f).height(50.dp)
                    ) {
                        Icon(Icons.Default.ContentCut, contentDescription = "Cut", modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Cut Further")
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Button(
                        onClick = {
                            exoPlayer.pause()
                            showConfirmationDialog = true
                        },
                        modifier = Modifier.weight(1f).height(50.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "Proceed", modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Proceed")
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // Confirmation Dialog (Code unchanged from your original)
        AnimatedVisibility(visible = showConfirmationDialog, enter = fadeIn(), exit = fadeOut()) {
            AlertDialog(
                onDismissRequest = {
                    showConfirmationDialog = false
                    exoPlayer.play()
                },
                title = { Text("Save Snippet") },
                text = { Text("Are you sure you want to finalize this cut and save it to your Gallery?") },
                confirmButton = {
                    Button(onClick = {
                        exoPlayer.pause()
                        Toast.makeText(context, "Exporting Video... Please Wait.", Toast.LENGTH_LONG).show()

                        VideoExporter.exportTrimmedVideo(
                            context = context,
                            files = bufferFiles,
                            trimStartMs = trimRange.start.toLong(),
                            trimEndMs = trimRange.endInclusive.toLong(),
                            onSuccess = {
                                showConfirmationDialog = false
                                Toast.makeText(context, "Saved to Gallery Successfully!", Toast.LENGTH_LONG).show()
                                onProceed()
                            },
                            onError = { error ->
                                showConfirmationDialog = false
                                Toast.makeText(context, "Export Failed: ${error.message}", Toast.LENGTH_LONG).show()
                            }
                        )
                    }) { Text("Save Snippet") }
                }
            )
        }
    }
}