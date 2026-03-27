package dev.animeshvarma.atropos.util

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

object VideoExporter {

    @OptIn(UnstableApi::class)
    fun exportTrimmedVideo(
        context: Context,
        files: List<File>,
        trimStartMs: Long,
        trimEndMs: Long,
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val mediaItemsToExport = mutableListOf<EditedMediaItem>()
                val retriever = MediaMetadataRetriever()
                var currentGlobalTimeMs = 0L

                // 1. Math Phase: Figure out which chunks need trimming based on the global slider
                for (file in files) {
                    retriever.setDataSource(file.absolutePath)
                    val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val durationMs = durationStr?.toLongOrNull() ?: 0L

                    val fileGlobalStart = currentGlobalTimeMs
                    val fileGlobalEnd = currentGlobalTimeMs + durationMs

                    // Check if this chunk overlaps with our requested cut range
                    if (trimEndMs > fileGlobalStart && trimStartMs < fileGlobalEnd) {

                        // Convert global slider times to local file times
                        val localStartMs = maxOf(0L, trimStartMs - fileGlobalStart)
                        val localEndMs = minOf(durationMs, trimEndMs - fileGlobalStart)

                        val clippingConfig = MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(localStartMs)
                            .setEndPositionMs(localEndMs)
                            .build()

                        val mediaItem = MediaItem.Builder()
                            .setUri(Uri.fromFile(file))
                            .setClippingConfiguration(clippingConfig)
                            .build()

                        mediaItemsToExport.add(EditedMediaItem.Builder(mediaItem).build())
                    }

                    currentGlobalTimeMs += durationMs
                    if (currentGlobalTimeMs >= trimEndMs) break // Stop processing chunks if we passed the cut
                }
                retriever.release()

                if (mediaItemsToExport.isEmpty()) {
                    withContext(Dispatchers.Main) { onError(Exception("No valid video data in the selected range.")) }
                    return@launch
                }

                // 2. Transformer Phase: Feed the clips into Media3 to stitch and export
                val sequence = EditedMediaItemSequence(mediaItemsToExport)
                val composition = Composition.Builder(listOf(sequence)).build()

                val outputDir = File(context.cacheDir, "exports").apply { mkdirs() }
                val outputFile = File(outputDir, "Atropos_Final_${System.currentTimeMillis()}.mp4")

                // Media3 requires initializations on the Main Thread
                withContext(Dispatchers.Main) {
                    val transformer = Transformer.Builder(context).build()

                    transformer.addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    saveToGallery(context, outputFile)
                                    withContext(Dispatchers.Main) { onSuccess() }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) { onError(e) }
                                }
                            }
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            Log.e("VideoExporter", "Transformer failed", exportException)
                            onError(exportException)
                        }
                    })

                    transformer.start(composition, outputFile.absolutePath)
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onError(e) }
            }
        }
    }

    private fun saveToGallery(context: Context, file: File) {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "Atropos_Snippet_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Atropos")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val uri = resolver.insert(collection, contentValues)
        uri?.let {
            resolver.openOutputStream(it)?.use { outStream ->
                FileInputStream(file).use { inStream -> inStream.copyTo(outStream) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(it, contentValues, null, null)
            }
        } ?: throw Exception("Failed to create MediaStore record")

        // Cleanup temporary export file to save cache space
        if (file.exists()) file.delete()
    }
}