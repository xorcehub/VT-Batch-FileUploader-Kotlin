package com.vtbatch.model

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

/**
 * Local-only usage telemetry. All data stays on device in ~/.vtbatch/telemetry.json
 * Tracks: commands used, files scanned, cache hit rate, upload outcomes, etc.
 */
class LocalTelemetry {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file = File(System.getProperty("user.home"), ".vtbatch/telemetry.json")
    private val mutex = Mutex()

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
        get() = data.let { d ->
            if (d.cacheHits + d.cacheMisses > 0)
                d.cacheHits.toDouble() / (d.cacheHits + d.cacheMisses)
            else 0.0
        }

    suspend fun recordCommand(command: String) {
        mutex.withLock {
            val updated = data.commandsUsed.toMutableMap()
            updated[command] = (updated[command] ?: 0) + 1
            data = data.copy(commandsUsed = updated)
            save()
        }
    }

    suspend fun recordFilesScanned(count: Int) {
        mutex.withLock {
            data = data.copy(filesScanned = data.filesScanned + count)
            save()
        }
    }

    suspend fun recordCacheHit() {
        mutex.withLock {
            data = data.copy(cacheHits = data.cacheHits + 1)
            save()
        }
    }

    suspend fun recordCacheMiss() {
        mutex.withLock {
            data = data.copy(cacheMisses = data.cacheMisses + 1)
            save()
        }
    }

    suspend fun recordUploadSuccess() {
        mutex.withLock {
            data = data.copy(uploadSuccesses = data.uploadSuccesses + 1)
            save()
        }
    }

    suspend fun recordUploadFailure() {
        mutex.withLock {
            data = data.copy(uploadFailures = data.uploadFailures + 1)
            save()
        }
    }

    suspend fun recordProcessingTime(ms: Long) {
        mutex.withLock {
            data = data.copy(totalProcessingTimeMs = data.totalProcessingTimeMs + ms)
            save()
        }
    }

    suspend fun recordSession() {
        mutex.withLock {
            data = data.copy(
                sessionsCount = data.sessionsCount + 1,
                lastSession = Instant.now().toString()
            )
            save()
        }
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
