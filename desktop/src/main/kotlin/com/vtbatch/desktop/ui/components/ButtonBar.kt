package com.vtbatch.desktop.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// ButtonBar - row of action buttons. Each maps to an MVI Intent.
// The "Key" button shows a green checkmark when credentials are valid.

@Composable
fun ButtonBar(
    modifier: Modifier = Modifier,
    hasCredentials: Boolean = false,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onOpenHashed: () -> Unit,
    onUpload: () -> Unit,
    onClear: () -> Unit,
    onShowCredentialDialog: () -> Unit = {}
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
        Spacer(modifier = Modifier.width(4.dp))
        OutlinedButton(
            onClick = onShowCredentialDialog,
            modifier = Modifier.weight(0.9f),
            colors = if (hasCredentials) {
                ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            } else {
                ButtonDefaults.outlinedButtonColors()
            }
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasCredentials) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Valid",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Icon(
                    Icons.Default.Key,
                    contentDescription = "API Key",
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
