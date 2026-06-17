package com.vtbatch.desktop.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// ButtonBar - row of action buttons. Each maps to an MVI Intent.
// Start and Upload are disabled while processing or uploading to prevent
// overlapping operations that would corrupt the state machine.
// Buttons show icons + text when the bar is wide enough, and drop to
// icons-only when the window shrinks below the threshold.
//
// Each icon has a fixed Modifier.size so Compose measures it at its intrinsic
// size before the weighted buttons are sized. Without this, weighted buttons
// squeeze their icon content unevenly as the window narrows (cf. StatusBar
// fix in 44216af).

@Composable
fun ButtonBar(
    modifier: Modifier = Modifier,
    isProcessing: Boolean = false,
    isUploading: Boolean = false,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onOpenHashed: () -> Unit,
    onUpload: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit = {}
) {
    val busy = isProcessing || isUploading

    BoxWithConstraints(modifier = modifier) {
        val showText = maxWidth > 1135.dp

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onStart,
                enabled = !busy,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Start",
                    modifier = Modifier.size(18.dp)
                )
                if (showText) {
                    Spacer(Modifier.width(4.dp))
                    Text("Start", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            OutlinedButton(
                onClick = onPause,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.Pause,
                    contentDescription = "Pause",
                    modifier = Modifier.size(18.dp)
                )
                if (showText) {
                    Spacer(Modifier.width(4.dp))
                    Text("Pause", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            OutlinedButton(
                onClick = onOpenHashed,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.FolderOpen,
                    contentDescription = "Open Hashed",
                    modifier = Modifier.size(18.dp)
                )
                if (showText) {
                    Spacer(Modifier.width(4.dp))
                    Text("Open Hashed", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            OutlinedButton(
                onClick = onUpload,
                enabled = !busy,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.CloudUpload,
                    contentDescription = "Upload",
                    modifier = Modifier.size(18.dp)
                )
                if (showText) {
                    Spacer(Modifier.width(4.dp))
                    Text("Upload", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            OutlinedButton(
                onClick = onClear,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.DeleteSweep,
                    contentDescription = "Clear",
                    modifier = Modifier.size(18.dp)
                )
                if (showText) {
                    Spacer(Modifier.width(4.dp))
                    Text("Clear", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            OutlinedButton(
                onClick = onExport,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = "Export",
                    modifier = Modifier.size(18.dp)
                )
                if (showText) {
                    Spacer(Modifier.width(4.dp))
                    Text("Export", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
