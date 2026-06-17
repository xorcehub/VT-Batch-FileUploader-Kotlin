package com.vtbatch.model

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val logger = KotlinLogging.logger {}

/**
 * Manual DI container — lazy singletons, factory methods.
 * Matches Python's container.py structure.
 * No framework dependency — just lazy property initialization.
 */
class AppContainer(
    apiKey: String? = null,
    initialConfig: AppConfig = AppConfig.default,
    val settingsStore: SettingsStore = SettingsStore()
) {
    // Mutable config — can be updated at runtime via settings dialog.
    // Readers use `config` (returns current value) — no existing callers need to change.
    private val _config = MutableStateFlow(initialConfig)
    val config: AppConfig get() = _config.value
    val configFlow: StateFlow<AppConfig> = _config.asStateFlow()

    @Volatile private var _apiKey: SecureApiKey? = apiKey?.let { SecureApiKey(it) }

    // Lazy singletons — created on first access, reused after that.
    private val pauseControllerBacking: PauseController by lazy { PauseController() }
    val pauseController: PauseController get() = pauseControllerBacking

    val credentialStore: CredentialStore by lazy { CredentialStore() }

    // QuotaManager + RateLimiter: recreated when config changes (same pattern as VirusTotalApi)
    @Volatile private var _quotaManager: QuotaManager? = null
    val quotaManager: QuotaManager
        get() = _quotaManager ?: QuotaManager(config = config).also { _quotaManager = it }

    @Volatile private var _rateLimiter: RateLimiter? = null
    val rateLimiter: RateLimiter
        get() = _rateLimiter ?: RateLimiter(
            requestsPerMinute = config.rateLimitPerMinute,
            pauseController = pauseController
        ).also { _rateLimiter = it }

    val errorHandler: ErrorHandler get() = ErrorHandler

    val pendingRecheckTracker: PendingRecheckTracker by lazy {
        PendingRecheckTracker(pollDelaySeconds = config.recheckBatchPollDelay.toDouble())
    }

    val telemetry: LocalTelemetry by lazy { LocalTelemetry() }

    // Cached VirusTotalApi - invalidated when credentials or config change
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

    /** Apply new config at runtime (from settings dialog). Rebuilds derived services. */
    fun updateConfig(newConfig: AppConfig) {
        _config.value = newConfig
        synchronized(apiLock) {
            _cachedApi?.close()
            _cachedApi = null
            _cachedApiKeyValue = null
        }
        _quotaManager = null
        _rateLimiter = null
        logger.info { "Config updated" }
    }

    fun shutdown() = synchronized(apiLock) {
        _cachedApi?.close()
        _cachedApi = null
        _apiKey?.clear()
    }
}
