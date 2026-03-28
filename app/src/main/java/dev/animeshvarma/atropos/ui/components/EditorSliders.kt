package dev.animeshvarma.atropos.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorSliders(
    viewWindowStartMs: Long,
    viewWindowEndMs: Long,
    currentPositionMs: Long,
    onPositionChange: (Long) -> Unit,
    trimRange: ClosedFloatingPointRange<Float>,
    onTrimRangeChange: (ClosedFloatingPointRange<Float>) -> Unit,
    onDragFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val trimmedLengthMs = (trimRange.endInclusive - trimRange.start).toLong()
    val estimatedSizeMb = (trimmedLengthMs / 1000f) * 1.2f

    // Safe bounds for sliders
    val windowStartF = viewWindowStartMs.toFloat()
    val windowEndF = viewWindowEndMs.toFloat().coerceAtLeast(windowStartF + 1f)

    val safeTrimStart = trimRange.start
    val safeTrimEnd = trimRange.endInclusive.coerceAtLeast(safeTrimStart + 1f)

    // Clamp the current playback position for the UI sliders so they don't crash
    val clampedGlobalPos = currentPositionMs.toFloat().coerceIn(windowStartF, windowEndF)
    val clampedTrimPos = currentPositionMs.toFloat().coerceIn(safeTrimStart, safeTrimEnd)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // 1. Global View Scrubber
        Text(
            text = "Complete Timeline: ${formatTime(currentPositionMs)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = clampedGlobalPos,
            onValueChange = { onPositionChange(it.toLong()) },
            valueRange = windowStartF..windowEndF,
            modifier = Modifier.height(24.dp),
            onValueChangeFinished = { onDragFinished() },
            )

        Spacer(modifier = Modifier.height(12.dp))

        // 2. The Trimmer (Cutter)
        Text(
            text = "Set Cut Bounds",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary
        )
        RangeSlider(
            value = trimRange,
            onValueChange = { onTrimRangeChange(it) },
            valueRange = windowStartF..windowEndF,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.secondary,
                activeTrackColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f)
            ),
            onValueChangeFinished = { onDragFinished() },
            modifier = Modifier.height(32.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 3. NEW: The Trim-Only Scrubber
        Text(
            text = "Scrub Inside Cut: ${formatTime((clampedTrimPos - safeTrimStart).toLong())} / ${formatTime(trimmedLengthMs)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary
        )
        Slider(
            value = clampedTrimPos,
            onValueChange = { onPositionChange(it.toLong()) },
            valueRange = safeTrimStart..safeTrimEnd,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.tertiary,
                activeTrackColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
            ),
            onValueChangeFinished = { onDragFinished() },
            modifier = Modifier.height(32.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Resulting Length & Size Text
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Resulting Cut: ${formatTime(trimmedLengthMs)}",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "~${String.format("%.1f", estimatedSizeMb)} MB",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.tertiary
            )
        }
    }
}

// Utility formatting function to support the sliders locally
fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}