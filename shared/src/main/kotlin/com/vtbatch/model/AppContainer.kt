package com.vtbatch.model

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.reflect.KClass

private val logger = KotlinLogging.logger {}

/**
 * Manual DI container — lazy singletons, factory methods.
 * Matches Python's container.py structure.
 * No framework dependency — just lazy property initialization.
 */
class AppContainer(
    apiKey: String? = null,
    val user: String? = null,
    val config: AppConfig = AppConfig.default,
) {
    private var _apiKey: SecureApiKey? = apiKey?.let { SecureApiKey(it) }
    private val overrides = mutableMapOf<KClass<*>, Any>()

    // Lazy singletons — created on first access, reused after that.
    // Kotlin `by lazy` = thread-safe lazy initialization (like Python's @property with caching)
    private val _rateLimiter: RateLimiter by lazy {
        RateLimiter(
            requestsPerMinute = config.rateLimitPerMinute,
            pauseController = pauseController
        )
    }

    val rateLimiter: RateLimiter get() = _rateLimiter

    val pauseController: PauseController by lazy { PauseController() }

    val fileStateManager: FileStateManager by lazy { FileStateManager() }

    val quotaManager: QuotaManager by lazy { QuotaManager(config = config) }

    val errorHandler: ErrorHandler by lazy { ErrorHandler() }

    val pendingRecheckTracker: PendingRecheckTracker by lazy {
        PendingRecheckTracker(pollDelaySeconds = config.recheckBatchPollDelay.toDouble())
    }

    val telemetry: LocalTelemetry by lazy { LocalTelemetry() }

    val virusTotalApi: VirusTotalApi?
        get() = if (credentialsValid && _apiKey != null)
            VirusTotalApi(_apiKey!!.get(), rateLimiter, config)
        else null

    val apiKey: String? get() = _apiKey?.get()
    val credentialsValid: Boolean get() = _apiKey != null && user != null

    fun updateCredentials(apiKey: String, user: String) {
        _apiKey?.clear()
        _apiKey = SecureApiKey(apiKey)
        // Note: can't reassign val, but VirusTotalApi is created fresh each time via getter
        logger.info { "Credentials updated for user: $user" }
    }

    fun <T : Any> override(cls: KClass<T>, instance: T) {
        overrides[cls] = instance
    }

    fun shutdown() {
        _apiKey?.clear()
    }
}
