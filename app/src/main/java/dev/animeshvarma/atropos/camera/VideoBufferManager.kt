package dev.animeshvarma.atropos.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.util.LinkedList

class VideoBufferManager(private val context: Context) {

    private val bufferDirectory = File(context.cacheDir, "atropos_buffer").apply {
        if (!exists()) mkdirs()
    }

    private val maxChunks = 5
    private val chunkDurationMs = 60_000L

    private val chunkQueue = LinkedList<File>()
    private var activeRecording: Recording? = null
    private var chunkingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    val recorder: Recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD)).build()
    val videoCapture: VideoCapture<Recorder> = VideoCapture.withOutput(recorder)

    var onSizeUpdated: ((sizeMb: Float) -> Unit)? = null
    private var pendingFinalizeAction: (() -> Unit)? = null

    init { clearBuffer() }

    @SuppressLint("MissingPermission")
    fun startRollingBuffer() {
        if (chunkingJob?.isActive == true) return

        chunkingJob = scope.launch {
            while (isActive) {
                val chunkFile = File(bufferDirectory, "chunk_${System.currentTimeMillis()}.mp4")
                val outputOptions = FileOutputOptions.Builder(chunkFile).build()

                val finalizeDeferred = CompletableDeferred<Unit>()
                // FIX 1: Add a deferred to track when the hardware ACTUALLY starts recording
                val startDeferred = CompletableDeferred<Unit>()

                val pendingRecording = recorder.prepareRecording(context, outputOptions).withAudioEnabled()

                try {
                    activeRecording = pendingRecording.start(ContextCompat.getMainExecutor(context)) { event ->
                        when (event) {
                            is VideoRecordEvent.Start -> {
                                // Hardware is now officially recording frames!
                                startDeferred.complete(Unit)
                            }
                            is VideoRecordEvent.Status -> {
                                val pastFilesSize = chunkQueue.sumOf { it.length() }
                                val totalSizeMb = (pastFilesSize + event.recordingStats.numBytesRecorded) / (1024f * 1024f)
                                onSizeUpdated?.invoke(totalSizeMb)
                            }
                            is VideoRecordEvent.Finalize -> {
                                activeRecording = null

                                // Failsafe: if hardware fails before starting, unblock the loop
                                startDeferred.complete(Unit)

                                // Cleanly handle 0-byte or error files so they don't break the Editor
                                if (!event.hasError() || event.error == VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA) {
                                    if (chunkFile.exists() && chunkFile.length() > 0) {
                                        manageQueue(chunkFile)
                                    } else {
                                        chunkFile.delete()
                                    }
                                } else {
                                    Log.e("AtroposBuffer", "Chunk Error: ${event.error}")
                                    if (chunkFile.exists()) chunkFile.delete()
                                }

                                pendingFinalizeAction?.invoke()
                                pendingFinalizeAction = null

                                finalizeDeferred.complete(Unit)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("AtroposBuffer", "Hardware codec busy. Retrying...", e)
                    delay(1000) // Wait 1 second and try again
                    continue
                }

                try {
                    // FIX 2: Wait for the camera to actually spin up before counting 60 seconds!
                    startDeferred.await()

                    delay(chunkDurationMs) // Now this waits a FULL 60 seconds of actual video time
                    activeRecording?.stop()
                } catch (e: CancellationException) {
                    // If user clicks Snapshot, this safely catches the cancellation and stops recording early
                    activeRecording?.stop()
                    throw e
                }

                finalizeDeferred.await()

                // Hardware Flush Delay (Allows MediaCodec to reset cleanly)
                delay(500)
            }
        }
    }

    fun stopRecording() {
        chunkingJob?.cancel()
        activeRecording?.stop()
    }

    fun pauseForSnapshot(onComplete: () -> Unit) {
        chunkingJob?.cancel()
        if (activeRecording != null) {
            pendingFinalizeAction = onComplete
            activeRecording?.stop()
        } else {
            // If user clicks snapshot during the 500ms gap, proceed instantly!
            onComplete()
        }
    }

    private fun manageQueue(newChunk: File) {
        chunkQueue.add(newChunk)
        while (chunkQueue.size > maxChunks) {
            val oldest = chunkQueue.removeFirst()
            if (oldest.exists()) oldest.delete()
        }
    }

    fun getBufferedFiles(): List<File> {
        return bufferDirectory.listFiles()
            ?.filter { it.extension == "mp4" && it.length() > 0 }
            ?.sortedBy { it.name } ?: emptyList()
    }

    fun clearBuffer() {
        bufferDirectory.listFiles()?.forEach { it.delete() }
        chunkQueue.clear()
        onSizeUpdated?.invoke(0f)
    }

    fun exportBufferToGallery(onComplete: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            val filesToSave = getBufferedFiles()
            filesToSave.forEachIndexed { index, file ->
                saveVideoToMediaStore(file, "Atropos_Buffer_${System.currentTimeMillis()}_Part${index + 1}")
            }
            withContext(Dispatchers.Main) { onComplete() }
        }
    }

    private fun saveVideoToMediaStore(file: File, title: String) {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$title.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Atropos")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val uri: Uri? = resolver.insert(collection, contentValues)
        uri?.let {
            resolver.openOutputStream(it)?.use { outStream ->
                FileInputStream(file).use { inStream -> inStream.copyTo(outStream) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(it, contentValues, null, null)
            }
        }
    }
}