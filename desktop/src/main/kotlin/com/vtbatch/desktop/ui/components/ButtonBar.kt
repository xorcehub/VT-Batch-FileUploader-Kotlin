package com.vtbatch.desktop.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// ButtonBar - row of action buttons. Each maps to an MVI Intent.
// Start and Upload are disabled while processing or uploading to prevent
// overlapping operations that would corrupt the state machine.

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

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = onStart,
            enabled = !busy,
            modifier = Modifier.weight(1f)
        ) {
            Text("Start")
        }
        OutlinedButton(onClick = onPause, modifier = Modifier.weight(1f)) {
            Text("Pause")
        }
        OutlinedButton(onClick = onOpenHashed, modifier = Modifier.weight(1.4f)) {
            Text("Open Hashed")
        }
        OutlinedButton(
            onClick = onUpload,
            enabled = !busy,
            modifier = Modifier.weight(1f)
        ) {
            Text("Upload")
        }
        OutlinedButton(onClick = onClear, modifier = Modifier.weight(0.8f)) {
            Text("Clear")
        }
    }
}
