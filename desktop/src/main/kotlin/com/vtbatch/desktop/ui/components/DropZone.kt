package com.vtbatch.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// DropZone — the area at the top where users drag-and-drop files,
// and also type commands. The isDragOver parameter comes from the
// MVI state (updated by the window-level DropTarget in Main.kt).

@Composable
fun DropZone(
    modifier: Modifier = Modifier,
    isDragOver: Boolean = false,
    isScanning: Boolean = false,
    onDrop: (List<String>) -> Unit,
    onSubmitCommand: (String) -> Unit
) {
    var commandText by remember { mutableStateOf("") }

    Column(modifier = modifier) {
        // Drag-and-drop area (visual indicator, DnD handled at window level)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (isDragOver) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .border(
                    width = if (isDragOver) 3.dp else 2.dp,
                    color = if (isDragOver) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(8.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = when {
                    isDragOver -> "Drop files here..."
                    isScanning -> "Scanning directory..."
                    else -> "Drag & Drop Files Here"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    isDragOver -> MaterialTheme.colorScheme.primary
                    isScanning -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Command input
        OutlinedTextField(
            value = commandText,
            onValueChange = { commandText = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Type a command (help, check, force, etc.) or drag files above...") },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            trailingIcon = {
                TextButton(
                    onClick = {
                        if (commandText.isNotBlank()) {
                            onSubmitCommand(commandText)
                            commandText = ""
                        }
                    }
                ) {
                    Text("Send")
                }
            }
        )
    }
}
