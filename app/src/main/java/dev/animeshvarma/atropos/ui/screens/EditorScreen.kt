package dev.animeshvarma.atropos.ui.screens

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import dev.animeshvarma.atropos.model.AtroposUiState
import dev.animeshvarma.atropos.ui.components.EditorSliders
import dev.animeshvarma.atropos.util.VideoExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(UnstableApi::class)
@Composable
fun EditorScreen(
    uiState: AtroposUiState,
    bufferFiles: List<File>,
    onProceed: () -> Unit,
    onCutFurther: () -> Unit
) {
    val context = LocalContext.current
    val MAX_BUFFER_MS = 6 * 60 * 1000L

    var viewWindowStartMs by remember { mutableLongStateOf(0L) }
    var viewWindowEndMs by remember { mutableLongStateOf(0L) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var trimRange by remember { mutableStateOf(0f..100f) }
    var previousTrimRange by remember { mutableStateOf(0f..100f) }

    var isInitialized by remember { mutableStateOf(false) }
    var showConfirmationDialog by remember { mutableStateOf(false) }

    var chunkDurations by remember { mutableStateOf<List<Long>>(emptyList()) }
    var totalDurationMs by remember { mutableLongStateOf(0L) }
    var globalOffsetMs by remember { mutableLongStateOf(0L) }

    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }

    LaunchedEffect(bufferFiles) {
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            val rawDurations = mutableListOf<Long>()
            var totalDiskMs = 0L

            for (file in bufferFiles) {
                try {
                    retriever.setDataSource(file.absolutePath)
                    val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val dur = durStr?.toLongOrNull() ?: 0L
                    rawDurations.add(dur)
                    totalDiskMs += dur
                } catch (e: Exception) {
                    rawDurations.add(0L)
                }
            }
            retriever.release()

            val offset = maxOf(0L, totalDiskMs - MAX_BUFFER_MS)
            var timeToChop = offset

            val validChunkDurations = mutableListOf<Long>()
            var finalTotalMs = 0L

            withContext(Dispatchers.Main) {
                globalOffsetMs = offset
                val player = ExoPlayer.Builder(context).build().apply {
                    setSeekParameters(SeekParameters.EXACT)
                }

                for (i in bufferFiles.indices) {
                    val file = bufferFiles[i]
                    val fileDur = rawDurations[i]

                    if (timeToChop >= fileDur && fileDur > 0) {
                        timeToChop -= fileDur
                    } else {
                        val itemDur = fileDur - timeToChop
                        validChunkDurations.add(itemDur)
                        finalTotalMs += itemDur

                        val builder = MediaItem.Builder().setUri(Uri.fromFile(file))
                        if (timeToChop > 0) {
                            builder.setClippingConfiguration(
                                MediaItem.ClippingConfiguration.Builder()
                                    .setStartPositionMs(timeToChop)
                                    .build()
                            )
                            timeToChop = 0
                        }
                        player.addMediaItem(builder.build())
                    }
                }

                player.prepare()
                player.playWhenReady = true

                chunkDurations = validChunkDurations
                totalDurationMs = finalTotalMs
                exoPlayer = player

                if (finalTotalMs > 0 && !isInitialized) {
                    viewWindowEndMs = finalTotalMs
                    val initialRange = 0f..finalTotalMs.toFloat()
                    trimRange = initialRange
                    previousTrimRange = initialRange
                    isInitialized = true
                }
            }
        }
    }

    fun seekToGlobal(globalMs: Long) {
        val player = exoPlayer ?: return
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
        player.seekTo(windowIdx, remaining)
    }

    // --- SEEKING & AUTO-PLAY LOGIC ---
    var lastSeekTimeMs by remember { mutableLongStateOf(0L) }

    fun requestSeek(globalMs: Long) {
        val player = exoPlayer ?: return
        val now = System.currentTimeMillis()

        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        if (now - lastSeekTimeMs > 60) {
            seekToGlobal(globalMs)
            lastSeekTimeMs = now
        }
    }

    fun onDragRelease() {
        val player = exoPlayer ?: return
        player.setSeekParameters(SeekParameters.EXACT)
        seekToGlobal(currentPositionMs)
        player.play()
    }
    // -------------------------------------

    LaunchedEffect(exoPlayer, isInitialized, chunkDurations) {
        while (true) {
            val player = exoPlayer
            if (player != null && isInitialized && chunkDurations.isNotEmpty()) {
                val windowIndex = player.currentMediaItemIndex
                val localPos = player.currentPosition

                var globalPos = 0L
                for (i in 0 until windowIndex) {
                    if (i < chunkDurations.size) {
                        globalPos += chunkDurations[i]
                    }
                }
                globalPos += localPos

                if (player.isPlaying) {
                    currentPositionMs = globalPos

                    val cutStart = trimRange.start.toLong()
                    val cutEnd = trimRange.endInclusive.toLong()
                    if (globalPos >= cutEnd) {
                        seekToGlobal(cutStart)
                    } else if (globalPos < cutStart - 500) {
                        seekToGlobal(cutStart)
                    }
                }
            }
            delay(50)
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { exoPlayer?.pause() }
    DisposableEffect(Unit) { onDispose { exoPlayer?.release() } }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 280.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            exoPlayer?.pause()
                            tryAwaitRelease()
                            exoPlayer?.play()
                        }
                    )
                },
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { view ->
                if (view.player != exoPlayer) {
                    view.player = exoPlayer
                }
            }
        )

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
                        exoPlayer?.pause()
                        currentPositionMs = newPos
                        requestSeek(newPos)
                    },
                    trimRange = trimRange,
                    onTrimRangeChange = { newTrimRange ->
                        exoPlayer?.pause()
                        val startChanged = newTrimRange.start != previousTrimRange.start
                        val endChanged = newTrimRange.endInclusive != previousTrimRange.endInclusive
                        val targetPos = when {
                            startChanged -> newTrimRange.start.toLong()
                            endChanged -> newTrimRange.endInclusive.toLong()
                            else -> currentPositionMs
                        }
                        currentPositionMs = targetPos
                        requestSeek(targetPos)
                        previousTrimRange = newTrimRange
                        trimRange = newTrimRange
                    },
                    onDragFinished = {
                        onDragRelease()
                    }
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
                            seekToGlobal(viewWindowStartMs)
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
                            exoPlayer?.pause()
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

        AnimatedVisibility(visible = showConfirmationDialog, enter = fadeIn(), exit = fadeOut()) {
            AlertDialog(
                onDismissRequest = {
                    showConfirmationDialog = false
                    exoPlayer?.play()
                },
                title = { Text("Save Snippet") },
                text = { Text("Are you sure you want to finalize this cut and save it to your Gallery?") },
                confirmButton = {
                    Button(onClick = {
                        exoPlayer?.pause()
                        Toast.makeText(context, "Exporting Video... Please Wait.", Toast.LENGTH_LONG).show()

                        VideoExporter.exportTrimmedVideo(
                            context = context,
                            files = bufferFiles,
                            trimStartMs = trimRange.start.toLong() + globalOffsetMs,
                            trimEndMs = trimRange.endInclusive.toLong() + globalOffsetMs,
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