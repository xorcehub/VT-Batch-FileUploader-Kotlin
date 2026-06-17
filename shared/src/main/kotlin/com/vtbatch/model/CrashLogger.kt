package com.vtbatch.model

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Appends crash logs to ~/.vtbatch/crash.log
 * Checked on startup to surface previous-session crashes.
 * Log file is rotated when it exceeds 1MB to prevent unbounded growth.
 */
object CrashLogger {
    private const val MAX_LOG_SIZE = 1_000_000L // 1MB
    private val logDir = File(System.getProperty("user.home"), ".vtbatch")
    private val logFile = File(logDir, "crash.log")

    fun logCrash(exception: Throwable) {
        try {
            logDir.mkdirs()
            // Rotate if file exceeds size limit — keep only the most recent entries
            if (logFile.exists() && logFile.length() > MAX_LOG_SIZE) {
                val lines = logFile.readLines()
                val kept = lines.takeLast(250)
                logFile.writeText(kept.joinToString("\n") + "\n")
            }
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            val entry = buildString {
                appendLine("=== CRASH $timestamp ===")
                appendLine("Type: ${exception::class.qualifiedName}")
                appendLine("Message: ${exception.message}")
                appendLine("Stack trace:")
                exception.stackTraceToString().lines().take(20).forEach { appendLine("  $it") }
                appendLine()
            }
            logFile.appendText(entry)
        } catch (_: Exception) {
            // Can't log a crash logger failure — silently ignore
        }
    }

    /** Check for previous crash. Returns crash text or null. Bounded to 1MB / 500 lines. */
    fun checkPreviousCrash(): String? {
        return try {
            if (logFile.exists() && logFile.length() > 0) {
                if (logFile.length() > 1_000_000) {
                    logFile.readLines().take(500).joinToString("\n")
                } else {
                    logFile.readText()
                }
            } else null
        } catch (_: Exception) { null }
    }

    fun clearCrashLog() {
        try { logFile.delete() } catch (_: Exception) {}
    }
}
