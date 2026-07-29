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
 * Magic byte signatures for executable binary formats.
 * Each entry is a pair of (byte offset, expected bytes).
 * Used to detect extensionless Unix executables by reading file headers.
 */
private val EXECUTABLE_MAGIC_SIGNATURES = listOf(
    // ELF — Linux / Unix binaries (offset 0, 4 bytes)
    0 to byteArrayOf(0x7F, 0x45, 0x4C, 0x46),  // \x7FELF

    // Mach-O — macOS binaries (offset 0, 4 bytes)
    0 to byteArrayOf(0xFE.toByte(), 0xED.toByte(), 0xFA.toByte(), 0xCE.toByte()), // MH_MAGIC    (32-bit)
    0 to byteArrayOf(0xFE.toByte(), 0xED.toByte(), 0xFA.toByte(), 0xCF.toByte()), // MH_MAGIC_64 (64-bit)
    0 to byteArrayOf(0xCE.toByte(), 0xFA.toByte(), 0xED.toByte(), 0xFE.toByte()), // MH_CIGAM    (reversed 32-bit)
    0 to byteArrayOf(0xCF.toByte(), 0xFA.toByte(), 0xED.toByte(), 0xFE.toByte()), // MH_CIGAM_64 (reversed 64-bit)

    // FAT Mach-O — Universal macOS binaries (offset 0, 4 bytes)
    0 to byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()), // FAT_MAGIC
    0 to byteArrayOf(0xBE.toByte(), 0xBA.toByte(), 0xFE.toByte(), 0xCA.toByte()), // FAT_CIGAM

    // PE — Windows executables without extension (offset 0, 2 bytes)
    0 to byteArrayOf(0x4D, 0x5A),  // MZ

    // Shebang — script interpreters (offset 0, 2 bytes)
    0 to byteArrayOf(0x23, 0x21),  // #!
)

/**
 * Scans directories for files matching suspicious extensions.
 * Also detects extensionless executables by checking magic byte signatures
 * (ELF, Mach-O, FAT Mach-O, PE, shebang scripts).
 * Uses java.nio.file (modern Java file API) instead of Python's pathlib.
 */
class FileScanner(
    private val extensions: Set<String> = ExtensionsConfig.getSuspiciousExtensions(),
    private val maxDepth: Int = 20,
    private val maxFiles: Int = 1000
) {
    /** Reload extensions from config */
    fun reloadExtensions(): FileScanner = FileScanner(ExtensionsConfig.getSuspiciousExtensions())

    /**
     * Scan a path (file or directory) for files with suspicious extensions
     * or executable magic bytes (for extensionless files).
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
                    if (isSuspicious(resolved)) listOf(resolved.toString())
                    else emptyList()
                }
                Files.isDirectory(resolved) -> {
                    Files.walk(resolved, maxDepth, FileVisitOption.FOLLOW_LINKS).use { stream ->
                        // Take one extra match to detect whether we hit the limit, then
                        // warn and drop it — without materializing the entire match set
                        // (a dir can hold far more suspicious files than maxFiles).
                        val matched = stream.filter { it.isRegularFile() }
                            .filter { !Files.isHidden(it) }
                            .filter { isSuspicious(it) }
                            .map { it.toString() }
                            .limit((maxFiles + 1).toLong())
                            .toList()
                        if (matched.size > maxFiles) {
                            logger.warn { "Truncated scan at maxFiles=$maxFiles for $path — MORE suspicious files exist beyond this limit." }
                        }
                        matched.take(maxFiles)
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

    /**
     * Check if a file is suspicious: matches a known extension
     * or (for extensionless files) has an executable magic byte signature.
     */
    private fun isSuspicious(file: Path): Boolean {
        val ext = ".${file.extension.lowercase()}"
        if (ext in extensions) return true

        // Extensionless files: check for executable magic bytes
        if (file.extension.isEmpty()) {
            return hasExecutableMagicBytes(file)
        }

        return false
    }

    /**
     * Read the first bytes of a file and compare against known executable
     * magic signatures (ELF, Mach-O, FAT Mach-O, PE, shebang).
     * Returns true if any signature matches.
     */
    internal fun hasExecutableMagicBytes(file: Path): Boolean {
        return try {
            Files.newInputStream(file).buffered().use { stream ->
                val header = ByteArray(8)
                val bytesRead = stream.read(header)
                if (bytesRead < 2) return false

                EXECUTABLE_MAGIC_SIGNATURES.any { (offset, magic) ->
                    if (offset + magic.size > bytesRead) return@any false
                    header.copyOfRange(offset, offset + magic.size).contentEquals(magic)
                }
            }
        } catch (e: Exception) {
            logger.debug { "Could not read magic bytes from ${file.name}: ${e.message}" }
            false
        }
    }
}
