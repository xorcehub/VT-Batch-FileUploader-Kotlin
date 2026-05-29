package com.vtbatch.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Local-only usage telemetry. All data stays on device in ~/.vtbatch/telemetry.json
 * Tracks: commands used, files scanned, cache hit rate, upload outcomes, etc.
 */
class LocalTelemetry {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file = File(System.getProperty("user.home"), ".vtbatch/telemetry.json")

    @Serializable
    data class TelemetryData(
        val commandsUsed: Map<String, Int> = emptyMap(),
        val filesScanned: Int = 0,
        val cacheHits: Int = 0,
        val cacheMisses: Int = 0,
        val uploadSuccesses: Int = 0,
        val uploadFailures: Int = 0,
        val totalProcessingTimeMs: Long = 0,
        val sessionsCount: Int = 0,
        val lastSession: String? = null,
    )

    private var data = load()

    val cacheHitRate: Double
        get() = if (data.cacheHits + data.cacheMisses > 0)
            data.cacheHits.toDouble() / (data.cacheHits + data.cacheMisses)
        else 0.0

    fun recordCommand(command: String) {
        val updated = data.commandsUsed.toMutableMap()
        updated[command] = (updated[command] ?: 0) + 1
        data = data.copy(commandsUsed = updated)
        save()
    }

    fun recordFilesScanned(count: Int) {
        data = data.copy(filesScanned = data.filesScanned + count)
        save()
    }

    fun recordCacheHit() {
        data = data.copy(cacheHits = data.cacheHits + 1)
        save()
    }

    fun recordCacheMiss() {
        data = data.copy(cacheMisses = data.cacheMisses + 1)
        save()
    }

    fun recordUploadSuccess() {
        data = data.copy(uploadSuccesses = data.uploadSuccesses + 1)
        save()
    }

    fun recordUploadFailure() {
        data = data.copy(uploadFailures = data.uploadFailures + 1)
        save()
    }

    fun recordProcessingTime(ms: Long) {
        data = data.copy(totalProcessingTimeMs = data.totalProcessingTimeMs + ms)
    }

    fun recordSession() {
        data = data.copy(
            sessionsCount = data.sessionsCount + 1,
            lastSession = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        )
        save()
    }

    fun getStats(): TelemetryData = data

    private fun load(): TelemetryData {
        return try {
            if (file.exists()) json.decodeFromString(TelemetryData.serializer(), file.readText())
            else TelemetryData()
        } catch (_: Exception) { TelemetryData() }
    }

    private fun save() {
        try {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(TelemetryData.serializer(), data))
        } catch (_: Exception) { /* silently ignore */ }
    }
}
