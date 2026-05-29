package com.vtbatch.desktop.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// ButtonBar — row of action buttons. Each maps to a user action
// that will become an MVI Intent in Phase 3.

@Composable
fun ButtonBar(
    modifier: Modifier = Modifier,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onOpenHashed: () -> Unit,
    onUpload: () -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(onClick = onStart, modifier = Modifier.weight(1f)) {
            Text("Start")
        }
        OutlinedButton(onClick = onPause, modifier = Modifier.weight(1f)) {
            Text("Pause")
        }
        OutlinedButton(onClick = onOpenHashed, modifier = Modifier.weight(1.4f)) {
            Text("Open Hashed")
        }
        OutlinedButton(onClick = onUpload, modifier = Modifier.weight(1f)) {
            Text("Upload")
        }
        OutlinedButton(onClick = onClear, modifier = Modifier.weight(0.8f)) {
            Text("Clear")
        }
    }
}
