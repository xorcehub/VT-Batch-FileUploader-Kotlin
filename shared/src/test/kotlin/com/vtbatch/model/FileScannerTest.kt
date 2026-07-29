package com.vtbatch.model

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileScannerTest {

    private val tmpDir: File = createTempDirectory("filescanner").toFile()

    private fun createFile(name: String, parent: File = tmpDir): File {
        val file = File(parent, name)
        file.parentFile?.mkdirs()
        file.writeText("test content for $name")
        file.deleteOnExit()
        return file
    }

    private fun createBinaryFile(name: String, magicBytes: ByteArray, parent: File = tmpDir): File {
        val file = File(parent, name)
        file.parentFile?.mkdirs()
        file.outputStream().use { it.write(magicBytes) }
        file.deleteOnExit()
        return file
    }

    @Test
    fun `getSuspiciousFiles finds exe files`() {
        createFile("malware.exe")
        createFile("document.pdf")
        createFile("photo.jpg")

        val scanner = FileScanner(setOf(".exe", ".dll", ".bat"))
        val results = scanner.getSuspiciousFiles(tmpDir.absolutePath)

        assertEquals(1, results.size)
        assertTrue(results[0].endsWith("malware.exe"))
    }

    @Test
    fun `getSuspiciousFiles finds multiple suspicious extensions`() {
        createFile("program.exe")
        createFile("library.dll")
        createFile("script.bat")
        createFile("readme.txt")

        val scanner = FileScanner(setOf(".exe", ".dll", ".bat"))
        val results = scanner.getSuspiciousFiles(tmpDir.absolutePath)

        assertEquals(3, results.size)
    }

    @Test
    fun `getSuspiciousFiles returns empty for non-existent path`() {
        val scanner = FileScanner(setOf(".exe"))
        val results = scanner.getSuspiciousFiles("/nonexistent/path/12345")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `getSuspiciousFiles accepts single file path`() {
        val file = createFile("single.exe")
        val scanner = FileScanner(setOf(".exe"))
        val results = scanner.getSuspiciousFiles(file.absolutePath)
        assertEquals(1, results.size)
    }

    @Test
    fun `getSuspiciousFiles ignores non-matching single file`() {
        val file = createFile("clean.pdf")
        val scanner = FileScanner(setOf(".exe"))
        val results = scanner.getSuspiciousFiles(file.absolutePath)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `getSuspiciousFiles walks subdirectories`() {
        val subDir = File(tmpDir, "subdir")
        subDir.mkdirs()
        subDir.deleteOnExit()
        createFile("nested.exe", subDir)
        createFile("top.bat")

        val scanner = FileScanner(setOf(".exe", ".bat"))
        val results = scanner.getSuspiciousFiles(tmpDir.absolutePath)
        assertEquals(2, results.size)
    }

    // --- Magic byte detection tests ---

    @Test
    fun `detects ELF binary without extension`() {
        // \x7FELF + padding
        val elfFile = createBinaryFile("linux-binary", byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0x02, 0x01, 0x01, 0x00))
        val scanner = FileScanner(emptySet())
        val results = scanner.getSuspiciousFiles(elfFile.absolutePath)
        assertEquals(1, results.size)
    }

    @Test
    fun `detects Mach-O 64-bit binary without extension`() {
        val machoFile = createBinaryFile("macos-binary", byteArrayOf(0xFE.toByte(), 0xED.toByte(), 0xFA.toByte(), 0xCF.toByte(), 0x00, 0x00, 0x00, 0x00))
        val scanner = FileScanner(emptySet())
        val results = scanner.getSuspiciousFiles(machoFile.absolutePath)
        assertEquals(1, results.size)
    }

    @Test
    fun `detects Mach-O 32-bit binary without extension`() {
        val machoFile = createBinaryFile("macos-32", byteArrayOf(0xFE.toByte(), 0xED.toByte(), 0xFA.toByte(), 0xCE.toByte()))
        val scanner = FileScanner(emptySet())
        val results = scanner.getSuspiciousFiles(machoFile.absolutePath)
        assertEquals(1, results.size)
    }

    @Test
    fun `detects FAT Mach-O universal binary without extension`() {
        val fatFile = createBinaryFile("universal-binary", byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 0x00, 0x00, 0x00, 0x02))
        val scanner = FileScanner(emptySet())
        val results = scanner.getSuspiciousFiles(fatFile.absolutePath)
        assertEquals(1, results.size)
    }

    @Test
    fun `detects PE binary without extension`() {
        val peFile = createBinaryFile("windows-bin", byteArrayOf(0x4D, 0x5A, 0x90.toByte(), 0x00)) // MZ header
        val scanner = FileScanner(emptySet())
        val results = scanner.getSuspiciousFiles(peFile.absolutePath)
        assertEquals(1, results.size)
    }

    @Test
    fun `detects shebang script without extension`() {
        val scriptFile = createBinaryFile("deploy-script", "#!/bin/bash\n".toByteArray())
        val scanner = FileScanner(emptySet())
        val results = scanner.getSuspiciousFiles(scriptFile.absolutePath)
        assertEquals(1, results.size)
    }

    @Test
    fun `ignores extensionless non-executable file`() {
        val textFile = createFile("readme")  // plain text content, no magic bytes
        val scanner = FileScanner(emptySet())
        val results = scanner.getSuspiciousFiles(textFile.absolutePath)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `ignores empty file without extension`() {
        val emptyFile = File(tmpDir, "empty")
        emptyFile.createNewFile()
        emptyFile.deleteOnExit()
        val scanner = FileScanner(emptySet())
        val results = scanner.getSuspiciousFiles(emptyFile.absolutePath)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `hasExecutableMagicBytes returns false for non-existent file`() {
        val scanner = FileScanner(emptySet())
        assertFalse(scanner.hasExecutableMagicBytes(java.nio.file.Paths.get("/nonexistent/fileXYZ")))
    }

    @Test
    fun `detects binaries mixed with extension files in directory scan`() {
        createFile("known.exe")
        createBinaryFile("elf-binary", byteArrayOf(0x7F, 0x45, 0x4C, 0x46))
        createBinaryFile("macho-binary", byteArrayOf(0xFE.toByte(), 0xED.toByte(), 0xFA.toByte(), 0xCF.toByte()))
        createFile("readme.txt")
        createFile("plain-text")  // no magic bytes
        createBinaryFile("script", "#!/usr/bin/env python3\n".toByteArray())

        val scanner = FileScanner(setOf(".exe"))
        val results = scanner.getSuspiciousFiles(tmpDir.absolutePath)

        assertEquals(4, results.size)  // .exe + ELF + Mach-O + shebang
    }

    // ── Truncation (C2) ──────────────────────────────────────────────────

    /** Attach a capturing appender to the root logger for the duration of [block]. */
    private fun captureWarns(block: () -> Unit): List<ILoggingEvent> {
        val appender = ListAppender<ILoggingEvent>()
        val root = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        root.addAppender(appender)
        appender.start()
        try {
            block()
        } finally {
            root.detachAppender(appender)
        }
        return appender.list
    }

    @Test
    fun `truncates at maxFiles and warns when more suspicious files exist`() {
        // Isolated dir: the class-level tmpDir is shared across tests, and several
        // other tests create *.exe files in it, so scanning it here would be
        // order-dependent. 5 .exe files, maxFiles = 3 -> truncated, must warn.
        val dir = createTempDirectory("filescanner-trunc").toFile().apply { deleteOnExit() }
        repeat(5) { i -> File(dir, "malware$i.exe").writeText("payload") }

        val scanner = FileScanner(setOf(".exe"), maxFiles = 3)
        var results: List<String> = emptyList()
        val observed = captureWarns { results = scanner.getSuspiciousFiles(dir.absolutePath) }

        assertEquals(3, results.size)
        assertTrue(observed.any {
            it.level == Level.WARN && it.formattedMessage.contains("Truncated scan at maxFiles=3")
        }, "Expected a truncation warning. Got: ${observed.map { it.formattedMessage }}")
    }

    @Test
    fun `does not warn when suspicious file count is exactly at the limit`() {
        val dir = createTempDirectory("filescanner-exact").toFile().apply { deleteOnExit() }
        repeat(3) { i -> File(dir, "malware$i.exe").writeText("payload") }
        val scanner = FileScanner(setOf(".exe"), maxFiles = 3)

        val observed = captureWarns { scanner.getSuspiciousFiles(dir.absolutePath) }

        // Exactly maxFiles matches -> no truncation, no warn.
        assertFalse(observed.any {
            it.level == Level.WARN && it.formattedMessage.contains("Truncated scan")
        }, "Should not warn when count == maxFiles. Got: ${observed.map { it.formattedMessage }}")
    }
}
