package com.vtbatch.desktop.mvi

import com.vtbatch.desktop.ui.navigation.FindNavigator
import com.vtbatch.model.*

// AppState = the entire UI state in one immutable snapshot.
// data class = auto-generates equals/hashCode/copy/toString.
// Every field has a default value so you can create an empty initial state.
// The reducer produces new copies of this — never mutates it.

data class AppState(
    /** All tracked files, in display order */
    val files: List<FileEntry> = emptyList(),

    /** Whether we're currently hash-checking files against VT */
    val isProcessing: Boolean = false,

    /** Whether we're currently uploading files to VT */
    val isUploading: Boolean = false,

    /** Whether processing is paused */
    val isPaused: Boolean = false,

    /** Hashing progress bar */
    val hashingProgress: ProgressInfo = ProgressInfo(),

    /** Upload progress bar */
    val uploadProgress: ProgressInfo = ProgressInfo(),

    /** Total progress bar */
    val totalProgress: ProgressInfo = ProgressInfo(),

    /** Currently processing file name (shown in ProcessingInfo) */
    val currentFile: String? = null,

    /** Status of the currently processing file */
    val currentStatus: String? = null,

    /** Log messages displayed in the status report area */
    val statusLog: List<String> = emptyList(),

    /** VT API quota information */
    val quotaDaily: QuotaData? = null,
    val quotaMonthly: QuotaData? = null,

    /** Recheck countdown */
    val recheckRemaining: Int? = null,
    val recheckPendingCount: Int = 0,

    /** Whether the credential dialog is visible */
    val showCredentialDialog: Boolean = false,

    /** Whether credentials are being validated */
    val isValidatingCredentials: Boolean = false,

    /** Find command matches */
    val findMatches: FindNavigator.FindMatches = FindNavigator.FindMatches(),

    /** Whether files are being dragged over the window */
    val isDragOver: Boolean = false,

    /** API key available and valid */
    val hasCredentials: Boolean = false
)
