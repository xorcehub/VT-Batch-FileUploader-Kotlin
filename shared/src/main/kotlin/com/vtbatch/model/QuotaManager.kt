package com.vtbatch.model

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Manages file scan data cache (JSON file keyed by MD5 hash).
 * Matches the Python QuotaManager behavior: save, load, expire entries.
 * All write operations are guarded by a Mutex to prevent concurrent clobbering.
 */
class QuotaManager(
    cacheFile: String? = null,
    private val config: AppConfig = AppConfig.default
) {
    private val cacheFile = File(cacheFile ?: config.cacheFilename).let {
        if (it.isAbsolute) it else {
            val dir = File(System.getProperty("user.home"), ".vtbatch")
            dir.mkdirs()
            File(dir, it.path)
        }
    }
    private val cacheDuration = Duration.ofHours(config.cacheDurationHours.toLong())
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; isLenient = true }
    private val writeMutex = Mutex()

    @Serializable
    data class CacheEntry(
        val filename: String? = null,
        val size: Long? = null,
        val path: String? = null,
        val url: String? = null,
        @SerialName("last_scan") val lastScan: String? = null,
        val status: String? = null,
        @SerialName("last_analysis_stats") val lastAnalysisStats: String? = null,
        @SerialName("last_analysis_date") val lastAnalysisDate: Long? = null,
        val detections: String? = null,
        @SerialName("detection_count") val detectionCount: Int? = null,
        @SerialName("suggested_threat_label") val suggestedThreatLabel: String? = null,
        @SerialName("sandbox_verdicts") val sandboxVerdicts: String? = null,
        @SerialName("type_description") val typeDescription: String? = null,
        @SerialName("tags") val tags: String? = null,
        @SerialName("meaningful_name") val meaningfulName: String? = null,
        @SerialName("times_submitted") val timesSubmitted: Int? = null,
        @SerialName("reputation") val reputation: Int? = null,
        @SerialName("first_submission_date") val firstSubmissionDate: Long? = null,
        @SerialName("last_submission_date") val lastSubmissionDate: Long? = null,
        @SerialName("total_votes_harmless") val totalVotesHarmless: Int? = null,
        @SerialName("total_votes_malicious") val totalVotesMalicious: Int? = null,
    )

    /** Save file statuses to cache (guarded by mutex to prevent concurrent clobbering) */
    suspend fun saveData(fileStatuses: Map<String, Map<String, Any?>>): Boolean {
        return writeMutex.withLock {
            try {
                val existing = loadRaw().toMutableMap()

                for ((filePath, statusData) in fileStatuses) {
                    val md5Hash = statusData["md5_hash"] as? String ?: continue
                    existing[md5Hash] = CacheEntry(
                        filename = File(filePath).name,
                        size = File(filePath).length(),
                        path = filePath,
                        url = statusData["analysis_url"] as? String,
                        lastScan = LocalDateTime.now().toString(),
                        status = statusData["status"] as? String,
                        lastAnalysisStats = statusData["last_analysis_stats"]?.toString(),
                        lastAnalysisDate = statusData["last_analysis_date"] as? Long,
                    )
                }

                cacheFile.writeText(json.encodeToString(
                    serializer = kotlinx.serialization.serializer<Map<String, CacheEntry>>(),
                    value = existing
                ))
                true
            } catch (e: Exception) {
                logger.error { "Error saving data: $e" }
                false
            }
        }
    }

    /** Save a single entry (guarded by mutex to prevent concurrent clobbering) */
    suspend fun saveEntry(hashId: String, entry: CacheEntry): Boolean {
        return writeMutex.withLock {
            try {
                val existing = loadRaw().toMutableMap()
                existing[hashId] = entry
                cacheFile.writeText(json.encodeToString(
                    serializer = kotlinx.serialization.serializer<Map<String, CacheEntry>>(),
                    value = existing
                ))
                true
            } catch (e: Exception) {
                logger.error { "Error saving entry: $e" }
                false
            }
        }
    }

    /** Clear cache (guarded by mutex to prevent concurrent clobbering) */
    suspend fun clearCache(): Boolean {
        return writeMutex.withLock {
            try {
                cacheFile.writeText("{}")
                true
            } catch (e: Exception) {
                logger.error { "Error clearing cache: $e" }
                false
            }
        }
    }

    /** Load cached entries, skipping expired ones */
    fun loadData(): Map<String, CacheEntry> {
        val raw = loadRaw()
        val now = LocalDateTime.now()
        val result = mutableMapOf<String, CacheEntry>()

        for ((hashId, entry) in raw) {
            val lastScan = entry.lastScan?.let { parseTimestamp(it) }

            if (lastScan == null) continue
            if (Duration.between(lastScan, now) > cacheDuration) continue

            result[hashId] = entry
        }

        return result
    }

    /**
     * Parse a timestamp string that may be in either Instant format
     * (e.g. "2026-06-03T09:35:32.510212Z") or LocalDateTime format
     * (e.g. "2026-06-03T09:35:32.510212"). Returns null if unparseable.
     */
    private fun parseTimestamp(value: String): LocalDateTime? {
        return try {
            LocalDateTime.parse(value)
        } catch (e: DateTimeParseException) {
            try {
                LocalDateTime.ofInstant(Instant.parse(value), ZoneId.systemDefault())
            } catch (e2: DateTimeParseException) {
                logger.warn { "Unparseable cache timestamp: $value" }
                null
            }
        }
    }

    private fun loadRaw(): Map<String, CacheEntry> {
        if (!cacheFile.exists() || cacheFile.length() == 0L) return emptyMap()
        return try {
            json.decodeFromString(
                kotlinx.serialization.serializer<Map<String, CacheEntry>>(),
                cacheFile.readText()
            )
        } catch (e: Exception) {
            logger.error { "Error loading cache: $e" }
            emptyMap()
        }
    }
}
