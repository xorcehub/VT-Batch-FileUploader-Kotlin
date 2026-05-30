package com.vtbatch.model

import io.github.oshai.kotlinlogging.KotlinLogging
// System.getenv() is used directly — no import needed in Kotlin/JVM

private val logger = KotlinLogging.logger {}

// =====================================================================
// Network Configuration Constants
// =====================================================================

// VirusTotal API rate limits (free tier default)
const val VT_FREE_TIER_REQUESTS_PER_MINUTE = 20

// VT API v3 default user path segment (/users/current)
const val VT_DEFAULT_USER = "current"
const val VT_PREMIUM_TIER_REQUESTS_PER_MINUTE = 1000

// Network timeouts (seconds)
const val DEFAULT_TIMEOUT = 30
const val SHORT_TIMEOUT = 15
const val LONG_TIMEOUT = 60

// Retry configuration
const val MAX_RETRIES = 3
const val RETRY_DELAY = 1.0
const val RETRY_BACKOFF = 2.0

// File upload
const val LARGE_FILE_THRESHOLD = 32 * 1024 * 1024 // 32MB

// Analysis polling
const val ANALYSIS_POLL_INTERVAL = 10
const val ANALYSIS_MAX_RETRIES = 30
const val ANALYSIS_INITIAL_DELAY = 5

// Recheck polling
const val RECHECK_POLL_INTERVAL = 30
const val RECHECK_MAX_ATTEMPTS = 10
const val RECHECK_BATCH_POLL_DELAY = 300 // 5 minutes

// Cache
const val CACHE_DURATION_HOURS = 24
const val CACHE_FILENAME = "vt_scan_data.json"

// Throttle
const val REFRESH_THROTTLE_SECONDS = 60.0

// Threads
const val MAX_CONCURRENT_THREADS = 5

// API
const val VT_API_BASE_URL = "https://www.virustotal.com/api/v3"

// Input validation limits
const val MAX_SEARCH_TERM_LENGTH = 256
const val MAX_FILE_PATH_DISPLAY_LENGTH = 260
const val MAX_EXTENSION_LENGTH = 20
const val MAX_HASH_LENGTH = 64   // SHA256
const val MIN_HASH_LENGTH = 32   // MD5
const val MAX_COMMAND_LENGTH = 2048
const val MAX_DATE_STRING_LENGTH = 32

// Helper to read env vars with fallback
private fun envInt(key: String, default: Int): Int {
    val value = System.getenv(key) ?: return default
    return value.toIntOrNull() ?: run {
        logger.warn { "Invalid integer for $key: '$value', using default $default" }
        default
    }
}

private fun envDouble(key: String, default: Double): Double {
    val value = System.getenv(key) ?: return default
    return value.toDoubleOrNull() ?: run {
        logger.warn { "Invalid float for $key: '$value', using default $default" }
        default
    }
}

private fun envStr(key: String, default: String): String = System.getenv(key) ?: default

/**
 * Runtime application configuration.
 *
 * All settings can be overridden via environment variables (VT_* prefix).
 * Kotlin data class = like Python @dataclass — auto-generates equals/hashCode/copy/toString.
 */
data class AppConfig(
    // Network
    val timeout: Int = envInt("VT_API_TIMEOUT", DEFAULT_TIMEOUT),
    val shortTimeout: Int = envInt("VT_SHORT_TIMEOUT", SHORT_TIMEOUT),
    val longTimeout: Int = envInt("VT_LONG_TIMEOUT", LONG_TIMEOUT),
    val rateLimitPerMinute: Int = envInt("VT_RATE_LIMIT", VT_FREE_TIER_REQUESTS_PER_MINUTE),

    // Upload
    val largeFileThreshold: Int = envInt("VT_LARGE_FILE_THRESHOLD", LARGE_FILE_THRESHOLD),

    // Analysis polling
    val analysisPollInterval: Int = envInt("VT_ANALYSIS_POLL_INTERVAL", ANALYSIS_POLL_INTERVAL),
    val analysisMaxRetries: Int = envInt("VT_ANALYSIS_MAX_RETRIES", ANALYSIS_MAX_RETRIES),
    val analysisInitialDelay: Int = envInt("VT_ANALYSIS_INITIAL_DELAY", ANALYSIS_INITIAL_DELAY),

    // Recheck
    val recheckPollInterval: Int = envInt("VT_RECHECK_POLL_INTERVAL", RECHECK_POLL_INTERVAL),
    val recheckMaxAttempts: Int = envInt("VT_RECHECK_MAX_ATTEMPTS", RECHECK_MAX_ATTEMPTS),
    val recheckBatchPollDelay: Int = envInt("VT_RECHECK_BATCH_POLL_DELAY", RECHECK_BATCH_POLL_DELAY),

    // Application
    val cacheDurationHours: Int = envInt("VT_CACHE_DURATION_HOURS", CACHE_DURATION_HOURS),
    val cacheFilename: String = envStr("VT_CACHE_FILENAME", CACHE_FILENAME),
    val refreshThrottleSeconds: Double = envDouble("VT_REFRESH_THROTTLE_SECONDS", REFRESH_THROTTLE_SECONDS),
    val maxConcurrentThreads: Int = envInt("VT_MAX_THREADS", MAX_CONCURRENT_THREADS),

    // API
    val apiBaseUrl: String = envStr("VT_API_BASE_URL", VT_API_BASE_URL),

    // Input validation
    val maxSearchTermLength: Int = envInt("VT_MAX_SEARCH_TERM_LENGTH", MAX_SEARCH_TERM_LENGTH),
    val maxFilePathDisplayLength: Int = envInt("VT_MAX_FILE_PATH_DISPLAY_LENGTH", MAX_FILE_PATH_DISPLAY_LENGTH),
    val maxExtensionLength: Int = envInt("VT_MAX_EXTENSION_LENGTH", MAX_EXTENSION_LENGTH),
    val maxHashLength: Int = envInt("VT_MAX_HASH_LENGTH", MAX_HASH_LENGTH),
    val minHashLength: Int = envInt("VT_MIN_HASH_LENGTH", MIN_HASH_LENGTH),
    val maxCommandLength: Int = envInt("VT_MAX_COMMAND_LENGTH", MAX_COMMAND_LENGTH),
    val maxDateStringLength: Int = envInt("VT_MAX_DATE_STRING_LENGTH", MAX_DATE_STRING_LENGTH),
) {
    init {
        val errors = mutableListOf<String>()
        if (timeout <= 0) errors.add("timeout must be positive, got $timeout")
        if (shortTimeout <= 0) errors.add("shortTimeout must be positive, got $shortTimeout")
        if (longTimeout <= 0) errors.add("longTimeout must be positive, got $longTimeout")
        if (rateLimitPerMinute < 0) errors.add("rateLimitPerMinute must be non-negative, got $rateLimitPerMinute")
        if (largeFileThreshold <= 0) errors.add("largeFileThreshold must be positive, got $largeFileThreshold")
        if (analysisPollInterval <= 0) errors.add("analysisPollInterval must be positive, got $analysisPollInterval")
        if (analysisMaxRetries <= 0) errors.add("analysisMaxRetries must be positive, got $analysisMaxRetries")
        if (analysisInitialDelay < 0) errors.add("analysisInitialDelay must be non-negative, got $analysisInitialDelay")
        if (recheckPollInterval <= 0) errors.add("recheckPollInterval must be positive, got $recheckPollInterval")
        if (recheckMaxAttempts <= 0) errors.add("recheckMaxAttempts must be positive, got $recheckMaxAttempts")
        if (cacheDurationHours <= 0) errors.add("cacheDurationHours must be positive, got $cacheDurationHours")
        if (refreshThrottleSeconds < 0) errors.add("refreshThrottleSeconds must be non-negative, got $refreshThrottleSeconds")
        if (maxConcurrentThreads <= 0) errors.add("maxConcurrentThreads must be positive, got $maxConcurrentThreads")
        if (minHashLength > maxHashLength) errors.add("minHashLength ($minHashLength) must not exceed maxHashLength ($maxHashLength)")

        if (errors.isNotEmpty()) {
            val msg = "Configuration validation failed:\n" + errors.joinToString("\n") { "  - $it" }
            logger.error { msg }
            throw IllegalArgumentException(msg)
        }
    }

    /** Minimum seconds between requests to respect rate limit */
    val minRequestInterval: Double get() = if (rateLimitPerMinute <= 0) 0.0 else 60.0 / rateLimitPerMinute

    /** Cache duration in seconds */
    val cacheDurationSeconds: Int get() = cacheDurationHours * 3600

    companion object {
        /** Global singleton config — like Python's get_config() */
        val default: AppConfig by lazy { AppConfig() }
    }
}
