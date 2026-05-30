package com.vtbatch.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Extracts structured data from VirusTotal API JSON responses.
 * Lives in the shared module because it depends on kotlinx.serialization,
 * which is only available here (not in the desktop module).
 */
object VTResponseParser {

    /** Structured detection stats extracted from a VT response. */
    data class DetectionStats(
        val description: String,  // e.g. "2 malicious, 72 total"
        val ratio: String         // e.g. "2/72"
    )

    /**
     * Extract detection stats from a VT file report response.
     * Returns a DetectionStats with a human-readable description and the ratio string.
     */
    fun extractDetectionStats(json: JsonObject): DetectionStats? {
        return try {
            val data = json["data"]?.jsonObject
            val attrs = data?.get("attributes")?.jsonObject
            val lastAnalysis = attrs?.get("last_analysis_results")?.jsonObject
            if (lastAnalysis != null) {
                val total = lastAnalysis.size
                val malicious = lastAnalysis.values.count {
                    it.jsonObject["category"]?.jsonPrimitive?.content == "malicious"
                }
                DetectionStats(
                    description = "$malicious malicious, $total total",
                    ratio = "$malicious/$total"
                )
            } else null
        } catch (e: Exception) {
            logger.warn { "Failed to extract detection stats: ${e.message}" }
            null
        }
    }

    /** Extract detection ratio string from an analysis response */
    fun extractDetectionStatsFromAnalysis(json: JsonObject): String? {
        // First try the file report format (last_analysis_results)
        val fromReport = extractDetectionStats(json)?.ratio
        if (fromReport != null) return fromReport

        // Fall back to analysis response format which uses "results" or "stats"
        return try {
            val attrs = json["data"]?.jsonObject?.get("attributes")?.jsonObject

            // Try stats object first (has explicit counts)
            val stats = attrs?.get("stats")?.jsonObject
            if (stats != null) {
                val malicious = stats["malicious"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val suspicious = stats["suspicious"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val undetected = stats["undetected"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val harmless = stats["harmless"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val total = malicious + suspicious + undetected + harmless +
                    (stats["timeout"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0) +
                    (stats["confirmed-timeout"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0) +
                    (stats["failure"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0) +
                    (stats["type-unsupported"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0)
                return "$malicious/$total"
            }

            // Fall back to counting individual results
            val results = attrs?.get("results")?.jsonObject
            if (results != null) {
                val total = results.size
                val malicious = results.values.count {
                    it.jsonObject["category"]?.jsonPrimitive?.content == "malicious"
                }
                return "$malicious/$total"
            }

            null
        } catch (e: Exception) {
            logger.warn { "Failed to extract analysis stats: ${e.message}" }
            null
        }
    }

    /** Extract SHA256 hash from a file report response */
    fun extractSha256(json: JsonObject): String? {
        return try {
            json["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content
        } catch (e: Exception) { null }
    }

    /** Extract SHA256 from an analysis response */
    fun extractSha256FromAnalysis(json: JsonObject): String? {
        return try {
            val meta = json["meta"]?.jsonObject
            meta?.get("file_info")?.jsonObject?.get("sha256")?.jsonPrimitive?.content
                ?: json["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content
        } catch (e: Exception) { null }
    }

    /** Extract analysis ID from an upload response */
    fun extractAnalysisId(json: JsonObject): String? {
        return try {
            json["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content
        } catch (e: Exception) { null }
    }

    /** Extract analysis status from an analysis response (e.g. "completed", "queued") */
    fun extractAnalysisStatus(json: JsonObject): String? {
        return try {
            json["data"]?.jsonObject?.get("attributes")?.jsonObject?.get("status")?.jsonPrimitive?.content
        } catch (e: Exception) { null }
    }

    /** Extract last_analysis_date (epoch seconds) from a file report */
    fun extractLastAnalysisDate(json: JsonObject): Long? {
        return try {
            json["data"]?.jsonObject?.get("attributes")?.jsonObject
                ?.get("last_analysis_date")?.jsonPrimitive?.content?.toLongOrNull()
        } catch (e: Exception) { null }
    }

    /** Structured file details extracted from a VT file report response. */
    data class FileDetails(
        val lastAnalysisStats: String?,      // formatted "42 malicious, 28 harmless, ..."
        val popularThreatLabel: String?,
        val typeDescription: String?,
        val tags: List<String>?,
        val meaningfulName: String?,
        val timesSubmitted: Int?,
        val reputation: Int?,
        val firstSubmissionDate: Long?,      // epoch seconds
        val lastSubmissionDate: Long?,       // epoch seconds
        val totalVotesHarmless: Int?,
        val totalVotesMalicious: Int?,
        val detectionCount: Int?,            // malicious count for cache
        val suggestedThreatLabel: String?    // for cache
    )

    /**
     * Extract all file detail fields from a VT /files/{hash} response in a single pass.
     * Returns null if the response doesn't have the expected structure.
     */
    fun extractFileDetails(json: JsonObject): FileDetails? {
        return try {
            val attrs = json["data"]?.jsonObject?.get("attributes")?.jsonObject ?: return null

            // Last analysis stats breakdown
            val lastAnalysisStats = try {
                val stats = attrs["last_analysis_stats"]?.jsonObject
                if (stats != null) {
                    val malicious = stats["malicious"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    val suspicious = stats["suspicious"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    val undetected = stats["undetected"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    val harmless = stats["harmless"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    val timeout = stats["timeout"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    buildString {
                        if (malicious > 0) append("$malicious malicious, ")
                        if (suspicious > 0) append("$suspicious suspicious, ")
                        if (harmless > 0) append("$harmless harmless, ")
                        if (undetected > 0) append("$undetected undetected")
                        if (timeout > 0) append(", $timeout timeout")
                    }.trimEnd(',', ' ')
                } else null
            } catch (_: Exception) { null }

            // Threat label
            val threatLabel = try {
                attrs["popular_threat_classification"]?.jsonObject
                    ?.get("suggested_threat_label")?.jsonPrimitive?.content
                    ?: attrs["popular_threat_label"]?.jsonPrimitive?.content
            } catch (_: Exception) { null }

            // Tags
            val tags = try {
                attrs["tags"]?.jsonArray?.map { it.jsonPrimitive.content }
            } catch (_: Exception) { null }

            // Total votes
            val votesHarmless = try {
                attrs["total_votes"]?.jsonObject?.get("harmless")?.jsonPrimitive?.content?.toIntOrNull()
            } catch (_: Exception) { null }
            val votesMalicious = try {
                attrs["total_votes"]?.jsonObject?.get("malicious")?.jsonPrimitive?.content?.toIntOrNull()
            } catch (_: Exception) { null }

            // Detection count (malicious engines count)
            val detectionCount = try {
                attrs["last_analysis_stats"]?.jsonObject?.get("malicious")?.jsonPrimitive?.content?.toIntOrNull()
            } catch (_: Exception) { null }

            FileDetails(
                lastAnalysisStats = lastAnalysisStats,
                popularThreatLabel = threatLabel,
                typeDescription = try { attrs["type_description"]?.jsonPrimitive?.content } catch (_: Exception) { null },
                tags = tags,
                meaningfulName = try { attrs["meaningful_name"]?.jsonPrimitive?.content } catch (_: Exception) { null },
                timesSubmitted = try { attrs["times_submitted"]?.jsonPrimitive?.content?.toIntOrNull() } catch (_: Exception) { null },
                reputation = try { attrs["reputation"]?.jsonPrimitive?.content?.toIntOrNull() } catch (_: Exception) { null },
                firstSubmissionDate = try { attrs["first_submission_date"]?.jsonPrimitive?.content?.toLongOrNull() } catch (_: Exception) { null },
                lastSubmissionDate = try { attrs["last_submission_date"]?.jsonPrimitive?.content?.toLongOrNull() } catch (_: Exception) { null },
                totalVotesHarmless = votesHarmless,
                totalVotesMalicious = votesMalicious,
                detectionCount = detectionCount,
                suggestedThreatLabel = threatLabel
            )
        } catch (e: Exception) {
            logger.warn { "Failed to extract file details: ${e.message}" }
            null
        }
    }
}
