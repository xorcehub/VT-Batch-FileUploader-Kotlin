package com.vtbatch.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// Exports the current in-view file list (with all VT data and per-engine
// detections) to JSON. Lives in the shared module so it can be unit-tested
// without the desktop/UI dependencies.
//
// Why JSON over CSV here: a file carries a *list* of per-engine detections,
// which is nested data. JSON keeps that as a clean array; CSV would force
// either comma-escaping inside cells or one-row-per-engine duplication.
// kotlinx.serialization (already a dependency for the cache) makes this a
// few lines of code.

/** A single AV engine detection, in the export's shape. */
@Serializable
data class ExportDetection(
    val engine: String,
    val verdict: String
)

/** Flat, human-friendly representation of one file for export. */
@Serializable
data class ExportFile(
    val fileName: String,
    val path: String,
    val sizeBytes: Long,
    val sizeFormatted: String,
    val md5: String? = null,
    val sha256: String? = null,
    val status: String,
    val analysisUrl: String? = null,
    val detectionRatio: String? = null,
    val lastAnalysisDate: String? = null,
    val lastAnalysisStats: String? = null,
    val popularThreatLabel: String? = null,
    val typeDescription: String? = null,
    val meaningfulName: String? = null,
    val tags: List<String>? = null,
    val timesSubmitted: Int? = null,
    val reputation: Int? = null,
    val firstSubmissionDate: String? = null,
    val lastSubmissionDate: String? = null,
    val totalVotesHarmless: Int? = null,
    val totalVotesMalicious: Int? = null,
    val errorMessage: String? = null,
    val avDetections: List<ExportDetection>? = null
)

/** Top-level export document. */
@Serializable
data class ExportDocument(
    val exportedAt: String,
    val fileCount: Int,
    val files: List<ExportFile>
)

/**
 * Converts the app's FileEntry list into a self-describing JSON document.
 * Pure and side-effect free — the caller (a side effect) decides where to
 * write the file, so this object stays trivially unit-testable. `object` = a
 * singleton with no state (same pattern as VTResponseParser / AppReducer).
 */
object FileExporter {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false   // omit null/empty fields for compact output
    }

    /** Convert one FileEntry (with its engine hits) to the export shape. */
    fun toExportFile(entry: FileEntry): ExportFile {
        return ExportFile(
            fileName = entry.fileName,
            path = entry.path,
            sizeBytes = entry.fileSizeBytes,
            sizeFormatted = entry.fileSizeFormatted,
            md5 = entry.md5Hash,
            sha256 = entry.sha256Hash,
            status = entry.status.name,
            analysisUrl = entry.analysisUrl,
            detectionRatio = entry.detectionRatio,
            lastAnalysisDate = entry.lastAnalysisDate,
            lastAnalysisStats = entry.lastAnalysisStats,
            popularThreatLabel = entry.popularThreatLabel,
            typeDescription = entry.typeDescription,
            meaningfulName = entry.meaningfulName,
            tags = entry.tags,
            timesSubmitted = entry.timesSubmitted,
            reputation = entry.reputation,
            firstSubmissionDate = entry.firstSubmissionDate,
            lastSubmissionDate = entry.lastSubmissionDate,
            totalVotesHarmless = entry.totalVotes?.harmless,
            totalVotesMalicious = entry.totalVotes?.malicious,
            errorMessage = entry.errorMessage,
            avDetections = entry.engineHits?.map { ExportDetection(it.engine, it.verdict) }
        )
    }

    /** Build the full export document from a list of files. */
    fun buildDocument(
        files: List<FileEntry>,
        exportedAt: String = defaultTimestamp()
    ): ExportDocument = ExportDocument(
        exportedAt = exportedAt,
        fileCount = files.size,
        files = files.map(::toExportFile)
    )

    /** Serialize the file list to a pretty-printed JSON string. */
    fun toJson(
        files: List<FileEntry>,
        exportedAt: String = defaultTimestamp()
    ): String = json.encodeToString(ExportDocument.serializer(), buildDocument(files, exportedAt))

    /** Current timestamp in ISO-8601 local form (e.g. "2026-06-16T12:00:00"). */
    fun defaultTimestamp(): String =
        LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
}
