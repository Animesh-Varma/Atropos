package dev.animeshvarma.atropos.model
import android.graphics.Bitmap

enum class AppMode {
    IDLE,               // Initial state, ready to record
    RECORDING_AWAKE,    // Recording, actively showing live feed
    RECORDING_ASLEEP,   // Recording, screen darkened to save battery
    PAUSED_PROMPT,      // Snapshot clicked, asking to continue or cancel
    EDITOR              // In the editor trimming the video
}

data class AtroposUiState(
    val currentMode: AppMode = AppMode.IDLE,
    val recordingDurationMs: Long = 0L,
    val currentBufferSizeMb: Float = 0f,
    val currentBufferDurationMs: Long = 0L,
    val lastSnapshot: Bitmap? = null,

    // Editor specific
    val editorVideoPath: String? = null,
    val trimmedLengthMs: Long = 0L,
    val trimmedSizeMb: Float = 0f
)