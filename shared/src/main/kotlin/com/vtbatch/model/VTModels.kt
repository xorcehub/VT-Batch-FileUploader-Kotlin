package com.vtbatch.model

// Data classes for VirusTotal API responses.
// These match the JSON structure VT returns.
// @Serializable (added in Phase 2) will let us auto-parse JSON into these.

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

data class VTUserInfo(
    val username: String? = null,
    val dailyQuotaUsed: Int = 0,
    val dailyQuotaTotal: Int = 500,
    val monthlyQuotaUsed: Int = 0,
    val monthlyQuotaTotal: Int = 5000
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
