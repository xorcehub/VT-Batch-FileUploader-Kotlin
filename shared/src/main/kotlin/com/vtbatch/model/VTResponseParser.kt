package com.vtbatch.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        return extractDetectionStats(json)?.ratio
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
}
