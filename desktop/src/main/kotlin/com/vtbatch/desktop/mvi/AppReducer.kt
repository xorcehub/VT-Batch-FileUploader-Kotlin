package com.vtbatch.desktop.mvi

import com.vtbatch.desktop.ui.navigation.FindNavigator
import com.vtbatch.model.*

// AppReducer = pure function: (oldState, intent) -> newState
// No side effects here — no API calls, no file I/O, nothing impure.
// This makes state transitions trivially testable.
// Every intent is handled; `when` is exhaustive because AppIntent is sealed.

object AppReducer {

    fun reduce(state: AppState, intent: AppIntent): AppState = when (intent) {
        // ── User actions ────────────────────────────────────────────────

        is AppIntent.DropFiles -> state.copy(
            statusLog = state.statusLog + "Scanning dropped paths..."
        )

        is AppIntent.SubmitCommand -> state.copy(
            statusLog = state.statusLog + "> ${intent.text}"
        )

        is AppIntent.StartProcessing -> {
            val toProcess = state.files.count {
                it.status == FileStatus.PENDING ||
                it.status == FileStatus.HASHED_NOT_FOUND ||
                (it.analysisUrl == null && it.md5Hash != null)
            }
            if (toProcess == 0) {
                state.copy(
                    statusLog = state.statusLog + "No files to process."
                )
            } else {
                state.copy(
                    isProcessing = true,
                    hashingProgress = ProgressInfo(),
                    totalProgress = ProgressInfo(),
                    statusLog = state.statusLog + "Starting processing for $toProcess file(s)..."
                )
            }
        }

        is AppIntent.TogglePause -> {
            val newPaused = !state.isPaused
            state.copy(
                isPaused = newPaused,
                statusLog = state.statusLog + if (newPaused) "Paused." else "Resumed."
            )
        }

        is AppIntent.DragEnter -> state.copy(isDragOver = true)

        is AppIntent.DragExit -> state.copy(isDragOver = false)

        is AppIntent.UploadNewFiles -> {
            val toUpload = state.files.count {
                it.status == FileStatus.HASHED_NOT_FOUND && it.md5Hash != null
            }
            if (toUpload == 0) {
                state.copy(
                    statusLog = state.statusLog + "No files to upload (all found on VT or no hash)."
                )
            } else {
                state.copy(
                    isUploading = true,
                    uploadProgress = ProgressInfo(),
                    totalProgress = ProgressInfo(),
                    statusLog = state.statusLog + "Uploading $toUpload file(s)..."
                )
            }
        }

        is AppIntent.OpenHashedFiles -> {
            val withUrl = state.files.count { it.analysisUrl != null }
            state.copy(
                statusLog = state.statusLog + "Opening $withUrl file(s) in browser..."
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
            statusLog = state.statusLog + "List cleared.",
            findMatches = FindNavigator.FindMatches()
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
            statusLog = state.statusLog + "Validating credentials..."
        )

        is AppIntent.FindFiles -> {
            val matches = if (intent.term.isBlank()) {
                FindNavigator.FindMatches()
            } else {
                FindNavigator.search(intent.term, state.files)
            }
            val logMsg = if (matches.hasMatches) {
                "Found ${matches.matchIndices.size} match(es) for \"${intent.term}\""
            } else {
                "No files matching \"${intent.term}\""
            }
            state.copy(
                findMatches = matches,
                statusLog = state.statusLog + logMsg
            )
        }

        is AppIntent.NavigateMatches -> {
            val updated = FindNavigator.navigate(state.findMatches, intent.direction)
            state.copy(findMatches = updated)
        }

        // ── Async results ──────────────────────────────────────────────

        is AppIntent.FilesScanned -> {
            // Merge new files with existing ones (by path), new files take precedence
            val existingMap = state.files.associateBy { it.path }
            val merged = (existingMap + intent.files.associateBy { it.path }).values.toList()
            state.copy(
                files = merged,
                statusLog = state.statusLog + intent.summary
            )
        }

        is AppIntent.FileProcessed -> state.copy(
            files = state.files.map {
                if (it.path == intent.path) intent.updatedEntry else it
            }
        )

        is AppIntent.CurrentProcessingChanged -> state.copy(
            currentFile = intent.file,
            currentStatus = intent.status
        )

        is AppIntent.FileUploaded -> state.copy(
            files = state.files.map {
                if (it.path == intent.path) it.copy(
                    status = FileStatus.UPLOADED_AWAITING,
                    analysisUrl = intent.analysisUrl
                ) else it
            }
        )

        is AppIntent.UploadProgress -> state.copy(
            files = state.files.map {
                if (it.path == intent.path) it.copy(
                    status = FileStatus.UPLOADING
                ) else it
            }
        )

        is AppIntent.AnalysisCompleted -> state.copy(
            files = state.files.map {
                if (it.path == intent.path) intent.updatedEntry else it
            },
            statusLog = state.statusLog + "Analysis complete: ${intent.updatedEntry.fileName}"
        )

        is AppIntent.AnalysisTimeout -> state.copy(
            files = state.files.map {
                if (it.path == intent.path) it.copy(
                    status = FileStatus.ANALYSIS_TIMEOUT,
                    errorMessage = "Analysis timed out"
                ) else it
            },
            statusLog = state.statusLog + "Analysis timed out for ${state.files.find { it.path == intent.path }?.fileName ?: intent.path}"
        )

        is AppIntent.HashingProgress -> state.copy(
            hashingProgress = ProgressInfo(
                percent = intent.percent,
                speedFormatted = "${String.format("%.1f", intent.speedMbps)} MB/s",
                fileCount = intent.fileCount,
                elapsedFormatted = intent.elapsedFormatted
            )
        )

        is AppIntent.UploadSpeed -> state.copy(
            uploadProgress = ProgressInfo(
                percent = state.uploadProgress.percent,
                speedFormatted = "${String.format("%.1f", intent.speedMbps)} MB/s",
                fileCount = intent.fileCount,
                elapsedFormatted = intent.elapsedFormatted
            )
        )

        is AppIntent.TotalProgress -> state.copy(
            totalProgress = state.totalProgress.copy(percent = intent.percent)
        )

        is AppIntent.QuotaUpdated -> state.copy(
            quotaDaily = intent.daily,
            quotaMonthly = intent.monthly
        )

        is AppIntent.RecheckTimerTick -> state.copy(
            recheckRemaining = intent.remainingSeconds,
            recheckPendingCount = intent.pendingCount
        )

        is AppIntent.CredentialsValidated -> state.copy(
            hasCredentials = true,
            isValidatingCredentials = false,
            statusLog = state.statusLog + "Credentials validated successfully."
        )

        is AppIntent.CredentialsInvalid -> state.copy(
            hasCredentials = false,
            isValidatingCredentials = false,
            showCredentialDialog = true,
            statusLog = state.statusLog + "Credential error: ${intent.message}"
        )

        is AppIntent.FilesUpdated -> state.copy(
            files = intent.files
        )

        is AppIntent.LogMessage -> state.copy(
            statusLog = state.statusLog + intent.message
        )

        is AppIntent.Error -> state.copy(
            statusLog = state.statusLog + "ERROR: ${intent.message}",
            isProcessing = if (state.isProcessing) {
                // Only clear processing if there are no more files being worked on
                state.files.none { it.status == FileStatus.HASHING || it.status == FileStatus.PENDING }
            } else state.isProcessing
        )

        is AppIntent.ProcessingCompleted -> state.copy(
            isProcessing = false,
            currentFile = null,
            currentStatus = null,
            statusLog = state.statusLog + "Processing complete."
        )

        is AppIntent.UploadCompleted -> state.copy(
            isUploading = false,
            currentFile = null,
            currentStatus = null,
            statusLog = state.statusLog + "Upload batch complete."
        )
    }
}
