package com.vtbatch.model

import kotlinx.serialization.Serializable

// Data classes for VirusTotal API responses.
// These match the JSON structure VT returns.
// @Serializable (added in Phase 2) will let us auto-parse JSON into these.

/**
 * One antivirus engine that flagged a file as malicious/suspicious.
 * engine  = the AV engine name (e.g. "Kaspersky", "Microsoft")
 * verdict = the malware name that engine returned (e.g. "Trojan.Win32.Generic")
 */
@Serializable
data class EngineHit(
    val engine: String,
    val verdict: String
)

data class VTFileReport(
    val sha256: String,
    val md5: String? = null,
    val detectionRatio: String? = null,
    val analysisUrl: String? = null,
    val lastAnalysisDate: String? = null,
    val status: FileStatus = FileStatus.PENDING
)

data class VTAnalysisResult(
    val analysisId: String,
    val status: String,  // "queued", "completed", etc.
    val detectionRatio: String? = null,
    val analysisUrl: String? = null
)

data class QuotaData(
    val used: Int,
    val total: Int
) {
    val remaining: Int get() = total - used
    val formatted: String get() = "$used/$total"
}

data class ProgressInfo(
    val percent: Double = 0.0,
    val speedFormatted: String = "",     // e.g. "45 MB/s"
    val fileCount: Int = 0,
    val elapsedFormatted: String = ""    // e.g. "2.3s"
)

/** Format byte count as human-readable size string. */
fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}
