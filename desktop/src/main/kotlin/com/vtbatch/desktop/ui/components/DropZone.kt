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
import androidx.compose.ui.unit.sp

// DropZone — the area at the top where users drag-and-drop files,
// and also type commands. It's both a drag target and a text input.
//
// onExternalDrag is Compose's built-in drag-and-drop support.
// For Phase 1 it's a visual placeholder; real DnD wiring comes in Phase 3.

@Composable
fun DropZone(
    modifier: Modifier = Modifier,
    onDrop: (List<String>) -> Unit,
    onSubmitCommand: (String) -> Unit
) {
    var commandText by remember { mutableStateOf("") }
    var isDragOver by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        // Drag-and-drop area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (isDragOver) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .border(
                    width = 2.dp,
                    color = if (isDragOver) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(8.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isDragOver) "Drop files here..." else "Drag & Drop Files Here",
                style = MaterialTheme.typography.bodyLarge,
                color = if (isDragOver) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Command input — a text field where users type commands like "help", "check", etc.
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
