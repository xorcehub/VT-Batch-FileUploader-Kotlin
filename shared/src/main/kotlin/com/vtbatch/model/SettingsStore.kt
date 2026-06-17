package com.vtbatch.model

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * User-overridable settings persisted to ~/.vtbatch/settings.json.
 * Fields are nullable — null means "use the hardcoded default".
 * Non-null means the user explicitly set it via the settings dialog.
 *
 * Priority chain: hardcoded default → settings.json → env var (highest).
 */
@Serializable
data class UserSettings(
    val analysisInitialDelay: Int? = null,
    val analysisPollInterval: Int? = null,
    val analysisMaxRetries: Int? = null,
    val cacheDurationHours: Int? = null,
    val shortTimeout: Int? = null,
)

/**
 * Persists user settings to ~/.vtbatch/settings.json.
 * Same injectable-File pattern as CredentialStore for testability.
 */
class SettingsStore(
    private val dir: File = File(System.getProperty("user.home"), ".vtbatch"),
    private val fileName: String = "settings.json"
) {
    private val file = File(dir, fileName)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun load(): UserSettings {
        return try {
            if (!file.exists() || file.length() == 0L) return UserSettings()
            val text = file.readText().trim()
            if (text.isBlank()) return UserSettings()
            json.decodeFromString<UserSettings>(text)
        } catch (e: Exception) {
            logger.warn { "Failed to load settings: ${e.message}" }
            UserSettings()
        }
    }

    fun save(settings: UserSettings) {
        try {
            dir.mkdirs()
            file.writeText(json.encodeToString(settings))
        } catch (e: Exception) {
            logger.error { "Failed to save settings: ${e.message}" }
            throw e
        }
    }
}
