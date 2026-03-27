package dev.animeshvarma.atropos.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SnapshotPromptDialog(
    onEditSnippet: () -> Unit,
    onSaveFullBuffer: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Snapshot Available") },
        text = { Text("What would you like to do with the recent buffer?") },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onEditSnippet, modifier = Modifier.weight(1f)) {
                    Text("Edit")
                }
                Button(onClick = onSaveFullBuffer, modifier = Modifier.weight(1f)) {
                    Text("Save All")
                }
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel (Resume Recording)")
            }
        }
    )
}

@Composable
fun StopWarningDialog(
    onStopConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Stop Recording?") },
        text = { Text("Warning: Stopping now will discard the current unsaved buffer. If you want to save it, take a Snapshot first.") },
        confirmButton = {
            Button(
                onClick = onStopConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Stop & Discard")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
    )
}