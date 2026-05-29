package com.vtbatch.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
// System.getenv() used directly

/**
 * Manages the list of suspicious file extensions to scan for.
 * Extensions come from: defaults → JSON config file → env var override.
 * Uses kotlinx.serialization for JSON parsing.
 */
object ExtensionsConfig {

    // Default extensions matching the Python version
    private val DEFAULT_EXTENSIONS = setOf(
        // Executables and Libraries
        ".exe", ".dll", ".ocx", ".sys", ".scr", ".drv",
        // Scripting
        ".bat", ".cmd", ".ps1", ".vbs", ".js", ".wsf", ".hta", ".vbe", ".jse",
        // Office Documents with Macros
        ".docm", ".xlsm", ".pptm",
        // Legacy Office Documents
        ".doc", ".xls", ".ppt",
        // Archives & Installers
        ".zip", ".rar", ".7z", ".gz", ".iso", ".msi", ".jar",
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Serializable
    private data class ExtensionsFile(val extensions: List<String>)

    private fun loadJsonExtensions(): Set<String> {
        val configFile = File("suspicious_extensions.json")
        if (!configFile.exists()) {
            // Create default config file
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
            emptySet()
        }
    }

    /** Load extensions: defaults + JSON + env var */
    fun getSuspiciousExtensions(): Set<String> {
        val exts = DEFAULT_EXTENSIONS.toMutableSet()
        loadJsonExtensions().let { exts.addAll(it) }

        // Env var override: VT_SUSPICIOUS_EXTENSIONS=.exe,.dll,.ps1
        System.getenv("VT_SUSPICIOUS_EXTENSIONS")?.let { env ->
            env.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
                exts.add(if (part.startsWith(".")) part else ".$part")
            }
        }

        return exts
    }
}
