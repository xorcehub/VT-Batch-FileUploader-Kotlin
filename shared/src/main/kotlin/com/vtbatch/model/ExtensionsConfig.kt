package com.vtbatch.model

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Manages the list of suspicious file extensions to scan for.
 * Extensions come from: defaults → JSON config file → env var override.
 * Uses kotlinx.serialization for JSON parsing.
 */
object ExtensionsConfig {

    // Default extensions matching the Python version
    private val DEFAULT_EXTENSIONS = setOf(
        // Windows Executables and Libraries
        ".exe", ".dll", ".ocx", ".sys", ".scr", ".drv", ".com", ".cpl",
        // Windows Installers & Packages
        ".msi", ".msix", ".appx",
        // Windows Shortcuts & Scripts
        ".bat", ".cmd", ".ps1", ".psm1", ".psd1", ".vbs", ".js", ".wsf",
        ".hta", ".vbe", ".jse", ".lnk", ".url", ".reg", ".inf",
        // Scripting Languages
        ".py", ".pyc", ".rb", ".pl", ".lua", ".wsh", ".sct",
        // Office Documents with Macros
        ".docm", ".xlsm", ".pptm",
        // Legacy Office Documents
        ".doc", ".xls", ".ppt",
        // Archives & Installers
        ".zip", ".rar", ".7z", ".gz", ".iso", ".jar", ".cab", ".deb", ".rpm",
        // macOS
        ".dmg", ".pkg",
        // Android
        ".apk", ".dex", ".aab", ".xapk",
        // iOS
        ".ipa",
        // Linux
        ".appimage", ".snap", ".flatpak",
        // Help & Documentation (can contain embedded executables)
        ".chm", ".hlp",
        // Disk Images
        ".img", ".vhd", ".vmdk",
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Serializable
    private data class ExtensionsFile(val extensions: List<String>)

    /** Resolve config file to ~/.vtbatch/ for consistency across launch locations */
    private fun resolveConfigFile(): File {
        val dir = File(System.getProperty("user.home"), ".vtbatch")
        dir.mkdirs()
        return File(dir, "suspicious_extensions.json")
    }

    private fun loadJsonExtensions(): Set<String> = synchronized(this) {
        val configFile = resolveConfigFile()
        if (!configFile.exists()) {
            try {
                val data = ExtensionsFile(DEFAULT_EXTENSIONS.toList())
                configFile.writeText(json.encodeToString(ExtensionsFile.serializer(), data))
            } catch (e: Exception) {
                // Can't create file, just use defaults
            }
            return DEFAULT_EXTENSIONS
        }

        return try {
            val data = json.decodeFromString(ExtensionsFile.serializer(), configFile.readText())
            data.extensions.map { if (it.startsWith(".")) it else ".$it" }.toSet()
        } catch (e: Exception) {
            logger.warn { "Failed to parse extensions config, using defaults: ${e.message}" }
            DEFAULT_EXTENSIONS
        }
    }

    /** Load extensions: JSON config file (the single source of truth) + env var override.
     *
     *  The file is seeded with DEFAULT_EXTENSIONS on first run (see loadJsonExtensions);
     *  after that it is the truth — add-ext/remove-ext edit it directly. The in-code
     *  DEFAULT_EXTENSIONS constant is NOT merged back in at scan time, so removing a
     *  default (e.g. .cab) actually takes effect. The env var is an additive override
     *  on top of whatever the file says. */
    fun getSuspiciousExtensions(): Set<String> {
        val exts = loadJsonExtensions().toMutableSet()

        // Env var override: VT_SUSPICIOUS_EXTENSIONS=.exe,.dll,.ps1
        System.getenv("VT_SUSPICIOUS_EXTENSIONS")?.let { env ->
            env.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
                exts.add(if (part.startsWith(".")) part else ".$part")
            }
        }

        return exts
    }

    /** Add an extension to the JSON config file */
    fun addExtension(ext: String) {
        val normalized = if (ext.startsWith(".")) ext else ".$ext"
        val current = loadJsonExtensions().toMutableList()
        if (normalized !in current) {
            current.add(normalized)
            saveJsonExtensions(current)
        }
    }

    /** Remove an extension from the JSON config file. No-op (with a log line) if the
     *  extension is not currently configured, so the caller isn't left guessing. */
    fun removeExtension(ext: String) {
        val normalized = if (ext.startsWith(".")) ext else ".$ext"
        val current = loadJsonExtensions().toMutableList()
        if (current.remove(normalized)) {
            saveJsonExtensions(current)
        } else {
            logger.info { "Extension $normalized is not in the configured scan list; nothing to remove." }
        }
    }

    private fun saveJsonExtensions(extensions: List<String>) = synchronized(this) { try {
            val configFile = resolveConfigFile()
            val data = ExtensionsFile(extensions)
            configFile.writeText(json.encodeToString(ExtensionsFile.serializer(), data))
        } catch (e: Exception) {
            // Silently fail — extensions will reload from defaults next time
        }
    }
}
