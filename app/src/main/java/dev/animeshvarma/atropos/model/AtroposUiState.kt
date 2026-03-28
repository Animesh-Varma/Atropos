package dev.animeshvarma.atropos.model

import android.graphics.Bitmap

enum class AppMode {
    IDLE, RECORDING_AWAKE, RECORDING_ASLEEP, PAUSED_PROMPT, EDITOR
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
    val trimmedSizeMb: Float = 0f,

    // NEW: Camera & HUD Controls
    val isDrawerOpen: Boolean = false,
    val showGridLines: Boolean = false,
    val isTorchOn: Boolean = false,
    val isMicMuted: Boolean = false,
    val showExposureSlider: Boolean = false,

    // NEW: Hardware Settings
    val videoQuality: String = "HD", // SD, HD, FHD, UHD
    val fps: Int = 30,
    val is10BitHdr: Boolean = false,
    val isOisEnabled: Boolean = true
)