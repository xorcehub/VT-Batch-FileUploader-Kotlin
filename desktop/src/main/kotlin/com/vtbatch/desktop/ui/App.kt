package com.vtbatch.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vtbatch.desktop.mvi.*
import com.vtbatch.desktop.theme.VTBatchTheme
import com.vtbatch.desktop.ui.components.*
import com.vtbatch.model.*

// @Composable = a function that describes UI. Compose automatically
// re-executes it whenever the data it reads changes (reactive).
// This is the root layout — it composes all the UI sections top-to-bottom.
//
// The store provides a StateFlow<AppState>. Every time the state changes,
// Compose re-renders only the parts that read changed data.

@Composable
fun App(store: AppStore = remember { AppStore() }) {
    val state by store.state.collectAsState()

    VTBatchTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Top section: Drop zone + Buttons
                DropZone(
                    modifier = Modifier.fillMaxWidth(),
                    isDragOver = state.isDragOver,
                    onDrop = { paths -> store.dispatch(AppIntent.DropFiles(paths)) },
                    onSubmitCommand = { text -> store.dispatch(AppIntent.SubmitCommand(text)) }
                )

                ButtonBar(
                    modifier = Modifier.fillMaxWidth(),
                    hasCredentials = state.hasCredentials,
                    onStart = { store.dispatch(AppIntent.StartProcessing) },
                    onPause = { store.dispatch(AppIntent.TogglePause) },
                    onOpenHashed = { store.dispatch(AppIntent.OpenHashedFiles) },
                    onUpload = { store.dispatch(AppIntent.UploadNewFiles) },
                    onClear = { store.dispatch(AppIntent.ClearList) },
                    onShowCredentialDialog = { store.dispatch(AppIntent.ShowCredentialDialog) }
                )

                // Middle: File list (takes remaining vertical space)
                Box(modifier = Modifier.weight(1f, fill = true)) {
                    FileList(
                        files = state.files,
                        expandedFilePath = state.expandedFilePath,
                        onToggleExpansion = { path -> store.dispatch(AppIntent.ToggleFileExpansion(path)) },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Bottom: Processing info + progress + log + status bar
                ProcessingInfo(
                    currentFile = state.currentFile,
                    currentStatus = state.currentStatus,
                    modifier = Modifier.fillMaxWidth()
                )

                ProgressSection(
                    hashingProgress = state.hashingProgress,
                    uploadProgress = state.uploadProgress,
                    totalProgress = state.totalProgress,
                    modifier = Modifier.fillMaxWidth()
                )

                StatusReport(
                    logLines = state.statusLog,
                    modifier = Modifier.fillMaxWidth()
                )

                StatusBar(
                    hashingInfo = if (state.hashingProgress.fileCount > 0)
                        "${state.hashingProgress.elapsedFormatted}, ${state.hashingProgress.speedFormatted} (${state.hashingProgress.fileCount} files)"
                    else "—",
                    uploadInfo = if (state.uploadProgress.fileCount > 0)
                        "${state.uploadProgress.elapsedFormatted}, ${state.uploadProgress.speedFormatted} (${state.uploadProgress.fileCount} files)"
                    else "—",
                    quotaInfo = state.quotaError ?: buildString {
                        val daily = state.quotaDaily
                        if (daily != null) {
                            append(daily.formatted)
                            append(" daily")
                            val monthly = state.quotaMonthly
                            if (monthly != null) {
                                append(" — ")
                                append(monthly.formatted)
                                append(" monthly")
                            }
                        } else {
                            append("—")
                        }
                    },
                    recheckInfo = state.recheckRemaining?.let { remaining ->
                        if (remaining > 0) "${formatSeconds(remaining)} (${state.recheckPendingCount} pending)" else "—"
                    } ?: "—",
                    modifier = Modifier.fillMaxWidth()
                )

                // Credential dialog (shown when user triggers it)
                if (state.showCredentialDialog) {
                    CredentialDialog(
                        onDismiss = { store.dispatch(AppIntent.HideCredentialDialog) },
                        onSubmit = { apiKey ->
                            store.dispatch(AppIntent.SubmitCredentials(apiKey))
                        }
                    )
                }
            }
        }
    }
}

/** Format seconds into mm:ss */
private fun formatSeconds(totalSeconds: Int): String {
    val mins = totalSeconds / 60
    val secs = totalSeconds % 60
    return "${mins}:${secs.toString().padStart(2, '0')}"
}
