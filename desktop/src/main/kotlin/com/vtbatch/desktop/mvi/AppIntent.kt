package com.vtbatch.desktop.mvi

import com.vtbatch.model.*

// AppIntent = every possible user action or async result, as a sealed class.
// "sealed class" means all subclasses are defined in this file — the compiler
// guarantees there are no others. This makes `when` expressions exhaustive:
// if you add a new intent, the compiler forces you to handle it everywhere.

sealed class AppIntent {

    // ── User actions (from UI events) ──────────────────────────────────

    /** User dragged-and-dropped file(s) onto the drop zone */
    data class DropFiles(val paths: List<String>) : AppIntent()

    /** User typed a command and pressed Send/Enter */
    data class SubmitCommand(val text: String) : AppIntent()

    /** User clicked "Start" to begin hash-checking all pending files */
    object StartProcessing : AppIntent()

    /** User clicked "Pause" / "Resume" */
    object TogglePause : AppIntent()

    /** User clicked "Upload" to upload files not found on VT */
    object UploadNewFiles : AppIntent()

    /** Files are being dragged over the window */
    object DragEnter : AppIntent()

    /** Files were dragged out or dropped */
    object DragExit : AppIntent()

    /** User clicked "Open Hashed" to open analysis URLs in browser */
    object OpenHashedFiles : AppIntent()

    /** User clicked "Clear" to reset everything */
    object ClearList : AppIntent()

    /** User triggered the credential dialog */
    object ShowCredentialDialog : AppIntent()

    /** User dismissed the credential dialog */
    object HideCredentialDialog : AppIntent()

    /** User submitted credentials via the dialog or command */
    data class SubmitCredentials(val apiKey: String, val user: String) : AppIntent()

    /** Find command — search files by name */
    data class FindFiles(val term: String) : AppIntent()

    /** Navigate to next/previous find match (+1 = next, -1 = previous) */
    data class NavigateMatches(val direction: Int) : AppIntent()

    // ── Async results (from side effects) ──────────────────────────────

    /** File scanning completed — here are the entries (with MD5 hashes and cache status) */
    data class FilesScanned(val files: List<FileEntry>, val summary: String) : AppIntent()

    /** A single file was hash-checked against VT */
    data class FileProcessed(val path: String, val updatedEntry: FileEntry) : AppIntent()

    /** Current file being processed changed (for the "Currently Processing" display) */
    data class CurrentProcessingChanged(val file: String?, val status: String?) : AppIntent()

    /** A file was uploaded to VT and we got an analysis ID */
    data class FileUploaded(
        val path: String,
        val analysisId: String,
        val analysisUrl: String
    ) : AppIntent()

    /** Upload progress update for a single file */
    data class UploadProgress(val path: String, val percent: Float) : AppIntent()

    /** Analysis polling completed for an uploaded file */
    data class AnalysisCompleted(val path: String, val updatedEntry: FileEntry) : AppIntent()

    /** Analysis polling timed out */
    data class AnalysisTimeout(val path: String) : AppIntent()

    /** Overall hashing progress bar update */
    data class HashingProgress(
        val percent: Float,
        val speedMbps: Float,
        val fileCount: Int,
        val elapsedFormatted: String
    ) : AppIntent()

    /** Overall upload progress / speed */
    data class UploadSpeed(
        val speedMbps: Float,
        val fileCount: Int,
        val elapsedFormatted: String
    ) : AppIntent()

    /** Total progress across all operations */
    data class TotalProgress(val percent: Float) : AppIntent()

    /** Quota info fetched from VT */
    data class QuotaUpdated(val daily: QuotaData, val monthly: QuotaData) : AppIntent()

    /** Recheck timer tick */
    data class RecheckTimerTick(val remainingSeconds: Int, val pendingCount: Int) : AppIntent()

    /** Credentials validated successfully */
    data class CredentialsValidated(val apiKey: String, val user: String) : AppIntent()

    /** Credentials failed validation */
    data class CredentialsInvalid(val message: String) : AppIntent()

    /** Bulk file list update (e.g. after force recheck, remove-green, etc.) */
    data class FilesUpdated(val files: List<FileEntry>) : AppIntent()

    /** A message to add to the status log (info) */
    data class LogMessage(val message: String) : AppIntent()

    /** An error occurred */
    data class Error(val message: String) : AppIntent()

    /** Processing completed (all files done) */
    object ProcessingCompleted : AppIntent()

    /** Upload batch completed */
    object UploadCompleted : AppIntent()
}
