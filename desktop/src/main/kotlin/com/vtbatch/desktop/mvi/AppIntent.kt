package com.vtbatch.desktop.mvi

import com.vtbatch.model.*

// AppIntent = every possible user action or async result, as a sealed class.
// "sealed class" means all subclasses are defined in this file — the compiler
// guarantees there are no others. This makes `when` expressions exhaustive:
// if you add a new intent, the compiler forces you to handle it everywhere.
//
// Think of it like Python's Union type but the compiler checks all cases.
// Phase 3 will flesh these out with real payloads.

sealed class AppIntent {
    // User actions
    data class DropFiles(val paths: List<String>) : AppIntent()
    data class SubmitCommand(val text: String) : AppIntent()
    object StartProcessing : AppIntent()
    object TogglePause : AppIntent()
    object UploadNewFiles : AppIntent()
    object OpenHashedFiles : AppIntent()
    object ClearList : AppIntent()

    // Async results (from side effects)
    data class FilesScanned(val files: List<FileEntry>) : AppIntent()
    data class FileProcessed(val path: String, val result: VTFileReport) : AppIntent()
    data class FileUploaded(val path: String, val analysisId: String) : AppIntent()
    data class AnalysisCompleted(val path: String, val detectionRatio: String) : AppIntent()
    data class UploadProgress(val path: String, val percent: Float) : AppIntent()
    data class HashingProgress(val percent: Float, val speedMbps: Float) : AppIntent()
    data class QuotaUpdated(val daily: QuotaData, val monthly: QuotaData) : AppIntent()
    data class RecheckTimerTick(val remainingSeconds: Int, val pendingCount: Int) : AppIntent()
    data class Error(val message: String) : AppIntent()
}
