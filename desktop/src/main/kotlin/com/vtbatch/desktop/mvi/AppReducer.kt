package com.vtbatch.desktop.mvi

import com.vtbatch.desktop.ui.navigation.FindNavigator
import com.vtbatch.model.*

// AppReducer = pure function: (oldState, intent) -> newState
// No side effects here — no API calls, no file I/O, nothing impure.
// This makes state transitions trivially testable.
// Every intent is handled; `when` is exhaustive because AppIntent is sealed.

object AppReducer {

    private const val MAX_LOG_SIZE = 500

    private fun List<String>.append(message: String): List<String> {
        val timestamp = java.time.LocalTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
        )
        return (this + "[$timestamp] $message").takeLast(MAX_LOG_SIZE)
    }

    /** Update a single file entry by path, leaving others unchanged. */
    private fun List<FileEntry>.updateByPath(
        path: String,
        transform: (FileEntry) -> FileEntry
    ): List<FileEntry> = map { if (it.path == path) transform(it) else it }

    fun reduce(state: AppState, intent: AppIntent): AppState = when (intent) {
        // ── User actions ────────────────────────────────────────────────

        is AppIntent.DropFiles -> state.copy(
            statusLog = state.statusLog.append("Scanning dropped paths..."),
            isScanning = true
        )

        is AppIntent.ScanStarted -> state.copy(
            isScanning = true
        )

        is AppIntent.SubmitCommand -> state.copy(
            statusLog = state.statusLog.append("> ${intent.text}")
        )

        is AppIntent.StartProcessing -> {
            // Align count with SideEffects.processFiles filter
            val visible = state.filteredFiles()
            val toProcess = visible.count {
                it.status == FileStatus.PENDING && it.md5Hash != null
            }
            if (toProcess == 0) {
                state.copy(
                    statusLog = state.statusLog.append("No files to process."),
                    isProcessing = false
                )
            } else {
                state.copy(
                    isProcessing = true,
                    hashingProgress = ProgressInfo(),
                    totalProgress = ProgressInfo(),
                    statusLog = state.statusLog.append("Starting processing for $toProcess file(s)...")
                )
            }
        }

        is AppIntent.TogglePause -> {
            val newPaused = !state.isPaused
            state.copy(
                isPaused = newPaused,
                statusLog = state.statusLog.append(if (newPaused) "Paused." else "Resumed.")
            )
        }

        is AppIntent.DragEnter -> state.copy(isDragOver = true)

        is AppIntent.DragExit -> state.copy(isDragOver = false)

        is AppIntent.UploadNewFiles -> {
            val visible = state.filteredFiles()
            val toUpload = visible.count {
                it.status == FileStatus.HASHED_NOT_FOUND && it.md5Hash != null
            }
            if (toUpload == 0) {
                state.copy(
                    statusLog = state.statusLog.append("No files to upload (all found on VT or no hash).")
                )
            } else {
                state.copy(
                    isUploading = true,
                    uploadProgress = ProgressInfo(),
                    totalProgress = ProgressInfo(),
                    statusLog = state.statusLog.append("Uploading $toUpload file(s)...")
                )
            }
        }

        is AppIntent.OpenHashedFiles -> {
            val withUrl = state.filteredFiles().count { it.analysisUrl != null }
            state.copy(
                statusLog = state.statusLog.append("Opening $withUrl file(s) in browser...")
            )
        }

        is AppIntent.ExportFiles -> {
            val count = state.filteredFiles().size
            state.copy(
                statusLog = if (count == 0) {
                    state.statusLog.append("Nothing to export — file list is empty.")
                } else {
                    state.statusLog.append("Exporting $count file(s) to JSON...")
                }
            )
        }

        is AppIntent.ClearList -> state.copy(
            files = emptyList(),
            isProcessing = false,
            isUploading = false,
            hashingProgress = ProgressInfo(),
            uploadProgress = ProgressInfo(),
            totalProgress = ProgressInfo(),
            currentFile = null,
            currentStatus = null,
            statusLog = state.statusLog.append("File list cleared."),
            findMatches = FindNavigator.FindMatches(),
            expandedFilePath = null,
            deselectedExtensions = emptySet(),
            deselectedColorTags = emptySet()
        )

        is AppIntent.ShowCredentialDialog -> state.copy(
            showCredentialDialog = true
        )

        is AppIntent.HideCredentialDialog -> state.copy(
            showCredentialDialog = false,
            isValidatingCredentials = false
        )

        is AppIntent.SubmitCredentials -> state.copy(
            isValidatingCredentials = true,
            showCredentialDialog = false,
            statusLog = state.statusLog.append("Validating credentials...")
        )

        is AppIntent.FindFiles -> {
            val matches = if (intent.term.isBlank()) {
                FindNavigator.FindMatches()
            } else {
                FindNavigator.search(intent.term, state.filteredFiles())
            }
            val logMsg = if (matches.hasMatches) {
                "Found ${matches.matchIndices.size} match(es) for \"${intent.term}\""
            } else {
                "No files matching \"${intent.term}\""
            }
            state.copy(
                findMatches = matches,
                statusLog = state.statusLog.append(logMsg)
            )
        }

        is AppIntent.NavigateMatches -> {
            val updated = FindNavigator.navigate(state.findMatches, intent.direction)
            state.copy(findMatches = updated)
        }

        is AppIntent.ToggleFileExpansion -> {
            val newPath = if (state.expandedFilePath == intent.path) null else intent.path
            state.copy(expandedFilePath = newPath)
        }

        is AppIntent.RecheckFile -> {
            val name = state.files.find { it.path == intent.path }?.fileName ?: intent.path
            state.copy(
                statusLog = state.statusLog.append("Requesting recheck for $name...")
            )
        }

        is AppIntent.RemoveFile -> {
            val name = state.files.find { it.path == intent.path }?.fileName ?: intent.path
            state.copy(
                files = state.files.filter { it.path != intent.path },
                expandedFilePath = if (state.expandedFilePath == intent.path) null else state.expandedFilePath,
                statusLog = state.statusLog.append("Removed $name.")
            )
        }

        // ── Filters ─────────────────────────────────────────────────────

        is AppIntent.ToggleExtensionFilter -> {
            val ext = intent.extension
            state.copy(
                deselectedExtensions = if (ext in state.deselectedExtensions)
                    state.deselectedExtensions - ext
                else
                    state.deselectedExtensions + ext,
                findMatches = FindNavigator.FindMatches() // indices invalidated by filter change
            )
        }

        is AppIntent.ToggleColorFilter -> {
            val tag = intent.tag
            state.copy(
                deselectedColorTags = if (tag in state.deselectedColorTags)
                    state.deselectedColorTags - tag
                else
                    state.deselectedColorTags + tag,
                findMatches = FindNavigator.FindMatches() // indices invalidated by filter change
            )
        }

        is AppIntent.SelectAllExtensions -> state.copy(
            deselectedExtensions = emptySet(),
            findMatches = FindNavigator.FindMatches()
        )

        is AppIntent.DeselectAllExtensions -> state.copy(
            deselectedExtensions = state.files.map { extOfName(it.fileName) }.toSet(),
            findMatches = FindNavigator.FindMatches()
        )

        is AppIntent.SelectAllColorTags -> state.copy(
            deselectedColorTags = emptySet(),
            findMatches = FindNavigator.FindMatches()
        )

        is AppIntent.DeselectAllColorTags -> state.copy(
            deselectedColorTags = ColorTag.entries.toSet(),
            findMatches = FindNavigator.FindMatches()
        )

        // ── Async results ──────────────────────────────────────────────

        is AppIntent.FilesScanned -> {
            // Merge new files with existing ones (by path), new files take precedence
            val existingMap = state.files.associateBy { it.path }
            val merged = (existingMap + intent.files.associateBy { it.path }).values.toList()
            state.copy(
                files = merged,
                statusLog = state.statusLog.append(intent.summary),
                isScanning = false
            )
        }

        is AppIntent.FileProcessed -> state.copy(
            files = state.files.updateByPath(intent.path) { intent.updatedEntry }
        )

        is AppIntent.SetFileStatus -> state.copy(
            // Merge, not replace: keep all existing VT data, flip only the status.
            files = state.files.updateByPath(intent.path) { it.copy(status = intent.status) }
        )

        is AppIntent.CurrentProcessingChanged -> state.copy(
            currentFile = intent.file,
            currentStatus = intent.status
        )

        is AppIntent.FileUploaded -> state.copy(
            files = state.files.updateByPath(intent.path) {
                it.copy(status = FileStatus.UPLOADED_AWAITING, analysisUrl = intent.analysisUrl)
            }
        )

        is AppIntent.UploadProgress -> state.copy(
            files = state.files.updateByPath(intent.path) {
                it.copy(status = FileStatus.UPLOADING)
            }
        )

        is AppIntent.AnalysisCompleted -> state.copy(
            files = state.files.updateByPath(intent.path) { intent.updatedEntry },
            statusLog = state.statusLog.append("Analysis complete: ${intent.updatedEntry.fileName}")
        )

        is AppIntent.AnalysisTimeout -> state.copy(
            files = state.files.updateByPath(intent.path) {
                it.copy(status = FileStatus.ANALYSIS_TIMEOUT, errorMessage = "Analysis timed out")
            },
            statusLog = state.statusLog.append("Analysis timed out for ${state.files.find { it.path == intent.path }?.fileName ?: intent.path}")
        )

        is AppIntent.HashingProgress -> state.copy(
            hashingProgress = ProgressInfo(
                percent = intent.percent.toDouble(),
                speedFormatted = "%.1f MB/s".format(intent.speedMBps),
                fileCount = intent.fileCount,
                elapsedFormatted = intent.elapsedFormatted
            )
        )

        is AppIntent.UploadSpeed -> state.copy(
            uploadProgress = ProgressInfo(
                percent = intent.percent.toDouble(),
                speedFormatted = "%.1f MB/s".format(intent.speedMBps),
                fileCount = intent.fileCount,
                elapsedFormatted = intent.elapsedFormatted
            )
        )

        is AppIntent.TotalProgress -> state.copy(
            totalProgress = ProgressInfo(
                percent = intent.percent.toDouble(),
                speedFormatted = intent.speedFormatted,
                fileCount = intent.fileCount,
                elapsedFormatted = intent.elapsedFormatted
            )
        )

        is AppIntent.QuotaUpdated -> state.copy(
            quotaDaily = intent.daily,
            quotaMonthly = intent.monthly,
            quotaError = null
        )

        is AppIntent.QuotaError -> state.copy(
            quotaDaily = null,
            quotaMonthly = null,
            quotaError = intent.message
        )

        is AppIntent.RecheckTimerTick -> state.copy(
            recheckRemaining = intent.remainingSeconds,
            recheckPendingCount = intent.pendingCount
        )

        is AppIntent.CredentialsValidated -> state.copy(
            hasCredentials = true,
            isValidatingCredentials = false,
            statusLog = state.statusLog.append("Credentials validated successfully.")
        )

        is AppIntent.CredentialsInvalid -> state.copy(
            hasCredentials = false,
            isValidatingCredentials = false,
            showCredentialDialog = true,
            statusLog = state.statusLog.append("Credential error: ${intent.message}")
        )

        is AppIntent.CredentialsValidationTransientError -> state.copy(
            // Transient (rate limit / server error): the key may still be valid, so do
            // NOT clear hasCredentials. Stop the spinner and re-show the dialog so the
            // user can retry. Contrast CredentialsInvalid, which marks the key bad.
            isValidatingCredentials = false,
            showCredentialDialog = true,
            statusLog = state.statusLog.append("Validation error: ${intent.message}")
        )

        is AppIntent.FilesUpdated -> state.copy(
            files = intent.files
        )

        is AppIntent.LogMessage -> state.copy(
            statusLog = state.statusLog.append(intent.message)
        )

        is AppIntent.Error -> state.copy(
            statusLog = state.statusLog.append("ERROR: ${intent.message}"),
            isScanning = false,
            isProcessing = if (state.isProcessing) {
                // Only clear processing if there are no more files being worked on
                state.files.none { it.status == FileStatus.HASHING || it.status == FileStatus.PENDING }
            } else state.isProcessing
        )

        is AppIntent.ProcessingCompleted -> state.copy(
            isProcessing = false,
            currentFile = null,
            currentStatus = null,
            statusLog = state.statusLog.append("Processing complete.")
        )

        is AppIntent.UploadCompleted -> state.copy(
            isUploading = false,
            currentFile = null,
            currentStatus = null,
            statusLog = state.statusLog.append("Upload batch complete.")
        )

        // ── Settings ────────────────────────────────────────────────────

        is AppIntent.ShowSettingsDialog -> state.copy(showSettingsDialog = true)

        is AppIntent.HideSettingsDialog -> state.copy(showSettingsDialog = false)

        is AppIntent.SaveSettings -> state.copy(
            statusLog = state.statusLog.append("Saving settings...")
        )

        is AppIntent.SettingsSaved -> state.copy(
            showSettingsDialog = false,
            envOverriddenFields = intent.overriddenFields,
            statusLog = state.statusLog.append("Settings applied.")
        )

        is AppIntent.SettingsError -> state.copy(
            statusLog = state.statusLog.append("ERROR: ${intent.message}")
        )
    }
}
