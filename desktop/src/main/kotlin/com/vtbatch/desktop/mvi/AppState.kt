package com.vtbatch.desktop.mvi

import com.vtbatch.desktop.ui.navigation.FindNavigator
import com.vtbatch.model.*

// AppState = the entire UI state in one immutable snapshot.
// data class = auto-generates equals/hashCode/copy/toString.
// Every field has a default value so you can create an empty initial state.
// In Phase 3, the reducer produces new copies of this — never mutates it.

data class AppState(
    val files: List<FileEntry> = emptyList(),
    val isPaused: Boolean = false,
    val isProcessing: Boolean = false,
    val isUploading: Boolean = false,
    val hashingProgress: ProgressInfo = ProgressInfo(),
    val uploadProgress: ProgressInfo = ProgressInfo(),
    val totalProgress: ProgressInfo = ProgressInfo(),
    val currentFile: String? = null,
    val currentStatus: String? = null,
    val statusLog: List<String> = emptyList(),
    val quotaInfo: QuotaData? = null,
    val recheckRemaining: Int? = null,
    val recheckPendingCount: Int = 0,
    val showCredentialDialog: Boolean = false,
    val findMatches: FindNavigator.FindMatches = FindNavigator.FindMatches()
)
