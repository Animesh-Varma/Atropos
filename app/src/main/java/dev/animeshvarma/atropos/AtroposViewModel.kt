package dev.animeshvarma.atropos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.animeshvarma.atropos.model.AppMode
import dev.animeshvarma.atropos.model.AtroposUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AtroposViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(AtroposUiState())
    val uiState: StateFlow<AtroposUiState> = _uiState.asStateFlow()

    private var sleepTimerJob: Job? = null
    private var recordingTimerJob: Job? = null

    private val maxBufferDurationMs = 6 * 60 * 1000L

    fun startRecording() {
        _uiState.update {
            it.copy(
                currentMode = AppMode.RECORDING_AWAKE,
                recordingDurationMs = 0L,
                currentBufferSizeMb = 0f,
                currentBufferDurationMs = 0L
            )
        }
        startSleepTimer()
        startRecordingTimer()
    }

    private fun startRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                _uiState.update {
                    val newSessionDuration = it.recordingDurationMs + 1000
                    val currentBuffer = minOf(newSessionDuration, maxBufferDurationMs)

                    it.copy(
                        recordingDurationMs = newSessionDuration,
                        currentBufferDurationMs = currentBuffer
                    )
                }
            }
        }
    }

    // Made public so we can reset it if the user clicks "Cancel" on a dialog
    fun startSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = viewModelScope.launch {
            delay(60000) // Changed to 1 MINUTE screen timeout
            if (_uiState.value.currentMode == AppMode.RECORDING_AWAKE) {
                _uiState.update { it.copy(currentMode = AppMode.RECORDING_ASLEEP) }
            }
        }
    }

    fun wakeUp() {
        if (_uiState.value.currentMode == AppMode.RECORDING_ASLEEP) {
            _uiState.update { it.copy(currentMode = AppMode.RECORDING_AWAKE) }
            startSleepTimer()
        }
    }

    fun stopRecording() {
        sleepTimerJob?.cancel()
        recordingTimerJob?.cancel()
        _uiState.update { it.copy(currentMode = AppMode.IDLE) }
    }

    fun proceedToEditor() {
        sleepTimerJob?.cancel()
        recordingTimerJob?.cancel()
        _uiState.update { it.copy(currentMode = AppMode.EDITOR) }
    }

    fun updateRealSize(sizeMb: Float) {
        _uiState.update { it.copy(currentBufferSizeMb = sizeMb) }
    }

    fun setVideoQuality(quality: String) {
        _uiState.update { it.copy(videoQuality = quality) }
    }
    fun set10BitHdr(isEnabled: Boolean) {
        _uiState.update { it.copy(is10BitHdr = isEnabled) }
    }
    fun setOisEnabled(isEnabled: Boolean) {
        _uiState.update { it.copy(isOisEnabled = isEnabled) }
    }
    fun setFps(fps: Int) {
        _uiState.update { it.copy(fps = fps) }
    }
}