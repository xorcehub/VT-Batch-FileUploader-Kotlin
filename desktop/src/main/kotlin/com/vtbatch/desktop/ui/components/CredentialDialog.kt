package com.vtbatch.desktop.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

// CredentialDialog — a modal popup for entering the VT API key.
// AlertDialog is Material3's built-in dialog component.
// Phase 3 will wire this to the MVI store for real credential handling.

@Composable
fun CredentialDialog(
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var apiKey by remember { mutableStateOf("") }
    var isVisible by remember { mutableStateOf(true) }

    if (isVisible) {
        AlertDialog(
            onDismissRequest = {
                isVisible = false
                onDismiss()
            },
            title = { Text("Enter VirusTotal API Key") },
            text = {
                Column {
                    Text(
                        "Enter your VirusTotal API key. You can find it at:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "https://www.virustotal.com/gui/user/<your-username>/apikey",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (apiKey.isNotBlank()) {
                            isVisible = false
                            onSubmit(apiKey)
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        isVisible = false
                        onDismiss()
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}
