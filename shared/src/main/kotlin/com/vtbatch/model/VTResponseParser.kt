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

    // ── JSON field access helpers (eliminate repeated chains) ─────────
    //
    // try/catch is intentional: VT's API usually returns the expected types,
    // but a schema change (e.g. a string field becoming an object) would
    // cause jsonPrimitive to throw. The original code wrapped every access
    // in individual try/catch blocks; we preserve that safety here so the
    // parser degrades gracefully (returns null) instead of crashing.

    private fun JsonObject?.intField(key: String): Int? =
        try { this?.get(key)?.jsonPrimitive?.content?.toIntOrNull() } catch (_: Exception) { null }

    private fun JsonObject?.stringField(key: String): String? =
        try { this?.get(key)?.jsonPrimitive?.content } catch (_: Exception) { null }

    private fun JsonObject?.longField(key: String): Long? =
        try { this?.get(key)?.jsonPrimitive?.content?.toLongOrNull() } catch (_: Exception) { null }

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
                    it.jsonObject.stringField("category") == "malicious"
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
                val malicious = stats.intField("malicious") ?: 0
                val suspicious = stats.intField("suspicious") ?: 0
                val undetected = stats.intField("undetected") ?: 0
                val harmless = stats.intField("harmless") ?: 0
                val total = malicious + suspicious + undetected + harmless +
                    (stats.intField("timeout") ?: 0) +
                    (stats.intField("confirmed-timeout") ?: 0) +
                    (stats.intField("failure") ?: 0) +
                    (stats.intField("type-unsupported") ?: 0)
                return "$malicious/$total"
            }

            // Fall back to counting individual results
            val results = attrs?.get("results")?.jsonObject
            if (results != null) {
                val total = results.size
                val malicious = results.values.count {
                    it.jsonObject.stringField("category") == "malicious"
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
        return json["data"]?.jsonObject?.stringField("id")
    }

    /** Extract SHA256 from an analysis response */
    fun extractSha256FromAnalysis(json: JsonObject): String? {
        return json["meta"]?.jsonObject?.get("file_info")?.jsonObject?.stringField("sha256")
            ?: json["data"]?.jsonObject?.stringField("id")
    }

    /** Extract analysis ID from an upload response */
    fun extractAnalysisId(json: JsonObject): String? {
        return json["data"]?.jsonObject?.stringField("id")
    }

    /** Extract analysis status from an analysis response (e.g. "completed", "queued") */
    fun extractAnalysisStatus(json: JsonObject): String? {
        return json["data"]?.jsonObject?.get("attributes")?.jsonObject?.stringField("status")
    }

    /** Extract last_analysis_date (epoch seconds) from a file report */
    fun extractLastAnalysisDate(json: JsonObject): Long? {
        return json["data"]?.jsonObject?.get("attributes")?.jsonObject?.longField("last_analysis_date")
    }

    /**
     * Extract per-engine detections (malicious engines + their verdicts) from a
     * results container. Same structure for both endpoints, so one helper:
     *   - /files/{id}     -> attributes.last_analysis_results
     *   - /analyses/{id}  -> attributes.results
     * Each entry is {"engine_name": ..., "category": "malicious"|..., "result": ...}.
     * Returns null when nothing is malicious (so clean files stay null, not []).
     */
    fun extractEngineHits(resultsContainer: JsonObject?): List<EngineHit>? {
        if (resultsContainer == null) return null
        return try {
            resultsContainer.entries
                .filter { it.value.jsonObject["category"]?.jsonPrimitive?.content == "malicious" }
                .map { (key, data) ->
                    val dataObj = data.jsonObject
                    EngineHit(
                        engine = dataObj["engine_name"]?.jsonPrimitive?.content ?: key,
                        verdict = dataObj["result"]?.jsonPrimitive?.content ?: "malicious"
                    )
                }
                .sortedBy { it.engine.lowercase() }
                .takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            logger.warn { "Failed to extract engine hits: ${e.message}" }
            null
        }
    }

    /** Extract per-engine detections from a VT analysis (/analyses/{id}) response. */
    fun extractEngineHitsFromAnalysis(json: JsonObject): List<EngineHit>? {
        val results = try {
            json["data"]?.jsonObject?.get("attributes")?.jsonObject?.get("results")?.jsonObject
        } catch (e: Exception) {
            logger.warn { "Failed to read analysis results: ${e.message}" }
            null
        }
        return extractEngineHits(results)
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
        val suggestedThreatLabel: String?,   // for cache
        val engineHits: List<EngineHit>? = null  // per-engine detections
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
                    val malicious = stats.intField("malicious") ?: 0
                    val suspicious = stats.intField("suspicious") ?: 0
                    val undetected = stats.intField("undetected") ?: 0
                    val harmless = stats.intField("harmless") ?: 0
                    val timeout = stats.intField("timeout") ?: 0
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
            val threatLabel = attrs["popular_threat_classification"]?.jsonObject
                ?.stringField("suggested_threat_label")
                ?: attrs.stringField("popular_threat_label")

            // Tags
            val tags = try {
                attrs["tags"]?.jsonArray?.map { it.jsonPrimitive.content }
            } catch (_: Exception) { null }

            // Total votes
            val votesHarmless = attrs["total_votes"]?.jsonObject?.intField("harmless")
            val votesMalicious = attrs["total_votes"]?.jsonObject?.intField("malicious")

            // Detection count (malicious engines count)
            val detectionCount = attrs["last_analysis_stats"]?.jsonObject?.intField("malicious")

            // Per-engine detections: pull each engine that flagged the file
            // as malicious, recording its name and the verdict it returned.
            val engineHits = extractEngineHits(attrs["last_analysis_results"]?.jsonObject)

            FileDetails(
                lastAnalysisStats = lastAnalysisStats,
                popularThreatLabel = threatLabel,
                typeDescription = attrs.stringField("type_description"),
                tags = tags,
                meaningfulName = attrs.stringField("meaningful_name"),
                timesSubmitted = attrs.intField("times_submitted"),
                reputation = attrs.intField("reputation"),
                firstSubmissionDate = attrs.longField("first_submission_date"),
                lastSubmissionDate = attrs.longField("last_submission_date"),
                totalVotesHarmless = votesHarmless,
                totalVotesMalicious = votesMalicious,
                detectionCount = detectionCount,
                suggestedThreatLabel = threatLabel,
                engineHits = engineHits
            )
        } catch (e: Exception) {
            logger.warn { "Failed to extract file details: ${e.message}" }
            null
        }
    }
}
