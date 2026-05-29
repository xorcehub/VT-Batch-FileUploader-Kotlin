package com.vtbatch.model

import java.io.File
import java.util.Base64

/**
 * Persists the API key to ~/.vtbatch/credentials.
 *
 * The key is stored with simple Base64 encoding to avoid it being
 * readable at a glance. This is NOT cryptographic protection - it just
 * prevents accidental exposure (e.g. someone glancing at the file).
 * For real security, use the VT_API_KEY environment variable instead.
 *
 * Instantiated by AppContainer (not a singleton) so tests can inject
 * a temp directory instead of hitting the real filesystem.
 */
class CredentialStore(
    private val dir: File = File(System.getProperty("user.home"), ".vtbatch"),
    private val fileName: String = "credentials"
) {
    private val file get() = File(dir, fileName)

    /** Save API key to disk. */
    fun save(apiKey: String) {
        try {
            dir.mkdirs()
            val encoded = Base64.getEncoder().encodeToString(apiKey.toByteArray(Charsets.UTF_8))
            file.writeText(encoded)
            // Restrict to owner-only on supported platforms
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
        } catch (_: Exception) {
            // Best effort - don't crash if persistence fails
        }
    }

    /** Load saved API key. Returns null if not found or unreadable. */
    fun load(): String? {
        return try {
            if (!file.exists() || file.length() == 0L) return null
            val encoded = file.readText().trim()
            if (encoded.isBlank()) return null
            String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /** Remove saved credentials. */
    fun clear() {
        try { file.delete() } catch (_: Exception) {}
    }
}
