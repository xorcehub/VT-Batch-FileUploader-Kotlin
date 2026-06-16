package com.vtbatch.desktop.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// ButtonBar - row of action buttons. Each maps to an MVI Intent.
// Start and Upload are disabled while processing or uploading to prevent
// overlapping operations that would corrupt the state machine.
// Buttons show icons + text when the bar is wide enough, and drop to
// icons-only when the window shrinks below the threshold.

@Composable
fun ButtonBar(
    modifier: Modifier = Modifier,
    isProcessing: Boolean = false,
    isUploading: Boolean = false,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onOpenHashed: () -> Unit,
    onUpload: () -> Unit,
    onClear: () -> Unit
) {
    val busy = isProcessing || isUploading

    BoxWithConstraints(modifier = modifier) {
        val showText = maxWidth > 480.dp

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onStart,
                enabled = !busy,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Start")
                if (showText) {
                    Spacer(Modifier.width(4.dp))
                    Text("Start")
                }
            }
            OutlinedButton(
                onClick = onPause,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Pause, contentDescription = "Pause")
                if (showText) {
                    Spacer(Modifier.width(4.dp))
                    Text("Pause")
                }
            }
            OutlinedButton(
                onClick = onOpenHashed,
                modifier = Modifier.weight(1.4f)
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = "Open Hashed")
                if (showText) {
                    Spacer(Modifier.width(4.dp))
                    Text("Open Hashed")
                }
            }
            OutlinedButton(
                onClick = onUpload,
                enabled = !busy,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.CloudUpload, contentDescription = "Upload")
                if (showText) {
                    Spacer(Modifier.width(4.dp))
                    Text("Upload")
                }
            }
            OutlinedButton(
                onClick = onClear,
                modifier = Modifier.weight(0.8f)
            ) {
                Icon(Icons.Default.DeleteSweep, contentDescription = "Clear")
                if (showText) {
                    Spacer(Modifier.width(4.dp))
                    Text("Clear")
                }
            }
        }
    }
}
