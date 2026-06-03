package com.vtbatch.model

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Manual DI container — lazy singletons, factory methods.
 * Matches Python's container.py structure.
 * No framework dependency — just lazy property initialization.
 */
class AppContainer(
    apiKey: String? = null,
    val config: AppConfig = AppConfig.default,
) {
    @Volatile private var _apiKey: SecureApiKey? = apiKey?.let { SecureApiKey(it) }

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

    val credentialStore: CredentialStore by lazy { CredentialStore() }

    val quotaManager: QuotaManager by lazy { QuotaManager(config = config) }

    val errorHandler: ErrorHandler by lazy { ErrorHandler() }

    val pendingRecheckTracker: PendingRecheckTracker by lazy {
        PendingRecheckTracker(pollDelaySeconds = config.recheckBatchPollDelay.toDouble())
    }

    val telemetry: LocalTelemetry by lazy { LocalTelemetry() }

    // Cached VirusTotalApi - invalidated when credentials change
    @Volatile private var _cachedApi: VirusTotalApi? = null
    @Volatile private var _cachedApiKeyValue: String? = null
    private val apiLock = Any()

    val virusTotalApi: VirusTotalApi?
        get() = synchronized(apiLock) {
            val key = _apiKey
            if (key == null) return null
            val currentKey = key.get()
            if (_cachedApi == null || _cachedApiKeyValue != currentKey) {
                _cachedApi?.close()
                _cachedApi = VirusTotalApi(currentKey, rateLimiter, config)
                _cachedApiKeyValue = currentKey
            }
            _cachedApi
        }

    val apiKey: String? get() = _apiKey?.get()
    val credentialsValid: Boolean get() = _apiKey != null

    fun updateCredentials(apiKey: String) = synchronized(apiLock) {
        _apiKey?.clear()
        _apiKey = SecureApiKey(apiKey)
        _cachedApi?.close()
        _cachedApi = null
        _cachedApiKeyValue = null
        logger.info { "Credentials updated" }
    }

    fun shutdown() = synchronized(apiLock) {
        _cachedApi?.close()
        _cachedApi = null
        _apiKey?.clear()
    }
}
