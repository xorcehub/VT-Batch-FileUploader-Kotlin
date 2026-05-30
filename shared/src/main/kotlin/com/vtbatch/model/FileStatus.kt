package com.vtbatch.model

// enum class = a fixed set of named values (like Python's Enum)
// Each file in the app has one status that changes as it's processed.
enum class FileStatus {
    PENDING,              // File added, not yet processed
    HASHING,              // Currently computing MD5/SHA256 hash
    HASHED_FOUND,         // Hash checked against VT — file already known
    HASHED_NOT_FOUND,     // Hash checked against VT — file unknown (needs upload)
    UPLOADING,            // Currently uploading file to VT
    UPLOADED_AWAITING,    // Upload done, waiting for analysis results
    ANALYSIS_COMPLETE,    // Analysis finished — results available
    ANALYSIS_TIMEOUT,     // Took too long to get analysis results
    ERROR,                // Something went wrong
    QUEUED_FOR_RECHECK,   // Scheduled for re-analysis
    RECHECKING            // Currently re-checking analysis status
}

// data class = a class that automatically gets equals(), hashCode(), toString(), copy()
// Think of it like a Python @dataclass — it's a dumb container for data.
data class FileEntry(
    val path: String,                    // Full file path
    val fileName: String,                // Just the filename
    val fileSizeBytes: Long,             // Size in bytes
    val fileSizeFormatted: String,       // Human-readable size ("2.4 MB")
    val md5Hash: String? = null,         // MD5 hash (computed during HASHING)
    val sha256Hash: String? = null,      // SHA256 hash
    val status: FileStatus = FileStatus.PENDING,
    val analysisUrl: String? = null,     // VT URL to view results
    val detectionRatio: String? = null,  // e.g. "0/72"
    val lastAnalysisDate: String? = null,
    val errorMessage: String? = null
) {
    // Determined color for the file list entry based on detection results
    val colorTag: ColorTag
        get() = when (status) {
            FileStatus.HASHED_FOUND, FileStatus.ANALYSIS_COMPLETE -> {
                detectionRatio?.let {
                    val parts = it.split("/").map { s -> s.trim().toIntOrNull() ?: 0 }
                    if (parts.size < 2) return@let ColorTag.NEUTRAL
                    val (detections, _) = parts
                    when {
                        detections == 0 -> ColorTag.CLEAN
                        detections <= 3 -> ColorTag.SUSPICIOUS
                        else -> ColorTag.MALICIOUS
                    }
                } ?: ColorTag.NEUTRAL
            }
            FileStatus.ERROR -> ColorTag.ERROR
            else -> ColorTag.NEUTRAL
        }
}

enum class ColorTag {
    CLEAN,       // Green — no detections
    SUSPICIOUS,  // Yellow — 1-3 detections
    MALICIOUS,   // Red — 4+ detections
    NEUTRAL,     // Gray — pending/uploading/etc
    ERROR        // Red variant — processing error
}
