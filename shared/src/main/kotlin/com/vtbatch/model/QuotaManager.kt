package com.vtbatch.model

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
 */
class QuotaManager(
    cacheFile: String? = null,
    private val config: AppConfig = AppConfig.default
) {
    private val cacheFile = File(cacheFile ?: config.cacheFilename)
    private val cacheDuration = Duration.ofHours(config.cacheDurationHours.toLong())
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; isLenient = true }

    @Serializable
    data class CacheEntry(
        val filename: String? = null,
        val size: Long? = null,
        val path: String? = null,
        val url: String? = null,
        val last_scan: String? = null,
        val status: String? = null,
        val last_analysis_stats: String? = null,
        val last_analysis_date: Long? = null,
        val detections: String? = null,
        val detection_count: Int? = null,
        val suggested_threat_label: String? = null,
        val sandbox_verdicts: String? = null,
    )

    /** Save file statuses to cache */
    fun saveData(fileStatuses: Map<String, Map<String, Any?>>): Boolean {
        return try {
            val existing = loadRaw().toMutableMap()

            for ((filePath, statusData) in fileStatuses) {
                val md5Hash = statusData["md5_hash"] as? String ?: continue
                existing[md5Hash] = CacheEntry(
                    filename = File(filePath).name,
                    size = File(filePath).length(),
                    path = filePath,
                    url = statusData["analysis_url"] as? String,
                    last_scan = LocalDateTime.now().toString(),
                    status = statusData["status"] as? String,
                    last_analysis_stats = statusData["last_analysis_stats"]?.toString(),
                    last_analysis_date = statusData["last_analysis_date"] as? Long,
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

    /** Save a single entry */
    fun saveEntry(hashId: String, entry: CacheEntry): Boolean {
        return try {
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

    /** Clear cache */
    fun clearCache(): Boolean {
        return try {
            cacheFile.writeText("{}")
            true
        } catch (e: Exception) {
            logger.error { "Error clearing cache: $e" }
            false
        }
    }

    /** Load cached entries, skipping expired ones */
    fun loadData(): Map<String, CacheEntry> {
        val raw = loadRaw()
        val now = LocalDateTime.now()
        val result = mutableMapOf<String, CacheEntry>()

        for ((hashId, entry) in raw) {
            val lastScan = entry.last_scan?.let {
                try { LocalDateTime.parse(it) }
                catch (e: DateTimeParseException) { null }
            }

            if (lastScan == null) continue
            if (Duration.between(lastScan, now) > cacheDuration) continue

            result[hashId] = entry
        }

        return result
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
