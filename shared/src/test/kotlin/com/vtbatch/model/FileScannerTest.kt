package com.vtbatch.model

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
