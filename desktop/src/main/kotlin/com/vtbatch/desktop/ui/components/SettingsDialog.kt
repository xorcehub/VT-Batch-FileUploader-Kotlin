package com.vtbatch.desktop.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vtbatch.model.AppConfig
import com.vtbatch.model.UserSettings

// SettingsDialog — modal dialog for tuning analysis polling, cache, and timeout settings.
// Follows the same AlertDialog pattern as CredentialDialog.
// Fields overridden by env vars are grayed out with a warning label.

@Composable
fun SettingsDialog(
    effectiveConfig: AppConfig,
    overriddenFields: Set<String>,
    onDismiss: () -> Unit,
    onSave: (UserSettings) -> Unit
) {
    var isVisible by remember { mutableStateOf(true) }

    // Initialize from effective config values (already resolved: default → settings → env var)
    var analysisInitialDelay by remember { mutableStateOf(effectiveConfig.analysisInitialDelay.toString()) }
    var analysisPollInterval by remember { mutableStateOf(effectiveConfig.analysisPollInterval.toString()) }
    var analysisMaxRetries by remember { mutableStateOf(effectiveConfig.analysisMaxRetries.toString()) }
    var cacheDurationHours by remember { mutableStateOf(effectiveConfig.cacheDurationHours.toString()) }
    var shortTimeout by remember { mutableStateOf(effectiveConfig.shortTimeout.toString()) }

    if (isVisible) {
        AlertDialog(
            onDismissRequest = {
                isVisible = false
                onDismiss()
            },
            title = { Text("Settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Leave a field empty to use the default value. Changes take effect immediately.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    for (meta in AppConfig.USER_FACING_FIELDS) {
                        val value = when (meta.fieldName) {
                            "analysisInitialDelay" -> analysisInitialDelay
                            "analysisPollInterval" -> analysisPollInterval
                            "analysisMaxRetries" -> analysisMaxRetries
                            "cacheDurationHours" -> cacheDurationHours
                            "shortTimeout" -> shortTimeout
                            else -> ""
                        }
                        val onValueChange: (String) -> Unit = when (meta.fieldName) {
                            "analysisInitialDelay" -> { v: String -> analysisInitialDelay = v }
                            "analysisPollInterval" -> { v: String -> analysisPollInterval = v }
                            "analysisMaxRetries" -> { v: String -> analysisMaxRetries = v }
                            "cacheDurationHours" -> { v: String -> cacheDurationHours = v }
                            "shortTimeout" -> { v: String -> shortTimeout = v }
                            else -> { _: String -> }
                        }
                        SettingsField(
                            label = meta.label,
                            unit = meta.unit,
                            value = value,
                            onValueChange = onValueChange,
                            defaultHint = meta.default.toString(),
                            overriddenBy = if (meta.fieldName in overriddenFields) meta.envVar else null
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        isVisible = false
                        onSave(UserSettings(
                            analysisInitialDelay = analysisInitialDelay.toIntOrNull()?.let { v ->
                                if (v != AppConfig.USER_FACING_FIELDS.find { it.fieldName == "analysisInitialDelay" }?.default) v else null
                            },
                            analysisPollInterval = analysisPollInterval.toIntOrNull()?.let { v ->
                                if (v != AppConfig.USER_FACING_FIELDS.find { it.fieldName == "analysisPollInterval" }?.default) v else null
                            },
                            analysisMaxRetries = analysisMaxRetries.toIntOrNull()?.let { v ->
                                if (v != AppConfig.USER_FACING_FIELDS.find { it.fieldName == "analysisMaxRetries" }?.default) v else null
                            },
                            cacheDurationHours = cacheDurationHours.toIntOrNull()?.let { v ->
                                if (v != AppConfig.USER_FACING_FIELDS.find { it.fieldName == "cacheDurationHours" }?.default) v else null
                            },
                            shortTimeout = shortTimeout.toIntOrNull()?.let { v ->
                                if (v != AppConfig.USER_FACING_FIELDS.find { it.fieldName == "shortTimeout" }?.default) v else null
                            },
                        ))
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        isVisible = false
                        onDismiss()
                    }
                ) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SettingsField(
    label: String,
    unit: String,
    value: String,
    onValueChange: (String) -> Unit,
    defaultHint: String,
    overriddenBy: String?
) {
    Column {
        Text(
            "$label ($unit) — default: $defaultHint",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (overriddenBy != null) {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                enabled = false,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.38f)
                )
            )
            Text(
                "Overridden by $overriddenBy env var",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
            )
        } else {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text("Default: $defaultHint") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
