package com.vtbatch.model

import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Scans directories for files matching suspicious extensions.
 * Uses java.nio.file (modern Java file API) instead of Python's pathlib.
 */
class FileScanner(
    private val extensions: Set<String> = ExtensionsConfig.getSuspiciousExtensions()
) {
    /** Reload extensions from config */
    fun reloadExtensions(): FileScanner = FileScanner(ExtensionsConfig.getSuspiciousExtensions())

    /**
     * Scan a path (file or directory) for files with suspicious extensions.
     * Returns list of absolute file paths.
     */
    fun getSuspiciousFiles(path: String): List<String> {
        val resolved = Paths.get(path).toAbsolutePath().normalize()

        if (!Files.exists(resolved)) {
            logger.error { "Path does not exist: $path" }
            return emptyList()
        }

        return try {
            when {
                Files.isRegularFile(resolved) -> {
                    if (".${resolved.extension.lowercase()}" in extensions) listOf(resolved.toString())
                    else emptyList()
                }
                Files.isDirectory(resolved) -> {
                    Files.walk(resolved, FileVisitOption.FOLLOW_LINKS).use { stream ->
                        stream.filter { it.isRegularFile() }
                            .filter { ".${it.extension.lowercase()}" in extensions }
                            .map { it.toString() }
                            .toList()
                    }
                }
                else -> emptyList()
            }.also { files ->
                logger.info { "Suspicious files found: ${files.size} files" }
            }
        } catch (e: Exception) {
            logger.error { "Error scanning path $path: ${e.message}" }
            emptyList()
        }
    }
}
