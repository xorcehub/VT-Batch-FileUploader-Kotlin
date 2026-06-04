package com.vtbatch.model

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private val logger = KotlinLogging.logger {}

/**
 * Persists the API key to ~/.vtbatch/credentials.
 *
 * The key is encrypted using AES-256-GCM with a machine-specific key derived
 * from JVM system properties. This prevents the API key from being readable
 * at a glance in the credentials file. The encryption key file is stored
 * alongside the credentials in ~/.vtbatch/.key.
 *
 * Note: This is defense-in-depth, not a substitute for OS keychain storage.
 * A determined local attacker with file access can recover the key.
 * For stronger security, integrate with the system keychain
 * (macOS Keychain, Windows Credential Manager, libsecret on Linux).
 * Avoid VT_API_KEY env var on shared systems — it is visible to other
 * users via /proc/<pid>/environ on Linux.
 *
 * File permission restrictions (setReadable/setWritable with ownerOnly=true)
 * are no-ops on Windows NTFS.
 *
 * Instantiated by AppContainer (not a singleton) so tests can inject
 * a temp directory instead of hitting the real filesystem.
 */
class CredentialStore(
    private val dir: File = File(System.getProperty("user.home"), ".vtbatch"),
    private val fileName: String = "credentials"
) {
    private val file = File(dir, fileName)
    private val keyFile = File(dir, ".key")

    companion object {
        private const val ALGORITHM = "AES"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12

        /**
         * Derive a deterministic 256-bit AES key from JVM system properties.
         * This makes the key machine-specific without requiring user input.
         * Different JVM installs or OS users will produce different keys.
         */
        private fun deriveMachineKey(): ByteArray {
            val props = listOf(
                System.getProperty("user.name", ""),
                System.getProperty("user.home", ""),
                System.getProperty("java.home", ""),
                System.getProperty("java.version", ""),
            ).joinToString("|")

            // SHA-256 hash to get exactly 32 bytes for AES-256
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            return digest.digest(props.toByteArray(Charsets.UTF_8))
        }
    }

    /** Save API key to disk with AES-256-GCM encryption. */
    fun save(apiKey: String) {
        try {
            dir.mkdirs()
            val cipher = Cipher.getInstance(TRANSFORMATION)

            // Generate random IV for GCM
            val iv = ByteArray(GCM_IV_LENGTH)
            SecureRandom().nextBytes(iv)

            // Use machine-derived key
            val keyBytes = deriveMachineKey()
            val secretKey: SecretKey = SecretKeySpec(keyBytes, ALGORITHM)

            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val encrypted = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))

            // Prepend IV to ciphertext, then Base64-encode the whole thing
            val combined = iv + encrypted
            val encoded = Base64.getEncoder().encodeToString(combined)
            file.writeText(encoded)

            // Restrict to owner-only on supported platforms (no-op on Windows NTFS)
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
        } catch (e: Exception) {
            logger.warn { "Failed to save credentials: ${e.message}" }
        }
    }

    /** Load saved API key. Returns null if not found or unreadable. */
    fun load(): String? {
        return try {
            if (!file.exists() || file.length() == 0L) return null
            val encoded = file.readText().trim()
            if (encoded.isBlank()) return null

            // Try AES-GCM decryption first (new format)
            decryptWithAES(encoded)?.let { return it }

            // Fallback: try legacy Base64 decoding for migration
            @Suppress("DEPRECATION")
            loadLegacyBase64(encoded)?.let { legacyKey ->
                logger.info { "Migrating credentials from legacy Base64 format to AES encryption" }
                // Re-save in new format
                save(legacyKey)
                return legacyKey
            }

            null
        } catch (_: Exception) {
            null
        }
    }

    /** Remove saved credentials. */
    fun clear() {
        try { file.delete() } catch (_: Exception) {}
    }

    /**
     * Decrypt a Base64-encoded AES-GCM ciphertext.
     * Returns null if the data is not in the expected format.
     */
    private fun decryptWithAES(encoded: String): String? {
        return try {
            val combined = Base64.getDecoder().decode(encoded)
            if (combined.size <= GCM_IV_LENGTH) return null

            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

            val keyBytes = deriveMachineKey()
            val secretKey: SecretKey = SecretKeySpec(keyBytes, ALGORITHM)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val decrypted = cipher.doFinal(ciphertext)
            String(decrypted, Charsets.UTF_8)
        } catch (_: Exception) {
            null // Not AES-GCM format or decryption failed
        }
    }

    /**
     * Legacy Base64 decoder for migrating old credential files.
     * @suppress kept for backward compatibility during migration.
     */
    @Deprecated("Legacy Base64 format, kept for migration only")
    private fun loadLegacyBase64(encoded: String): String? {
        return try {
            val decoded = Base64.getDecoder().decode(encoded)
            val key = String(decoded, Charsets.UTF_8)
            // Basic sanity: VT API keys are alphanumeric with hyphens/underscores
            // If it looks like a valid key, return it
            if (key.length in 16..256 && key.all { it.isLetterOrDigit() || it in "-_=+" }) {
                key
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
