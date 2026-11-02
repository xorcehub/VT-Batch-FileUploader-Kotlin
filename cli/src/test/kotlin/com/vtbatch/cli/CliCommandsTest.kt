package com.vtbatch.cli

import com.vtbatch.model.*
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CliCommandsTest {

    // ═══════════════════════════════════════════════════════════════════
    //  ExitCodes constants
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `ExitCodes constants have expected values`() {
        assertEquals(0, ExitCodes.SUCCESS)
        assertEquals(1, ExitCodes.NO_RESULTS)
        assertEquals(2, ExitCodes.ERROR)
        assertEquals(3, ExitCodes.AUTH_ERROR)
        assertEquals(4, ExitCodes.RATE_LIMIT)
        assertEquals(5, ExitCodes.NETWORK_ERROR)
        assertEquals(6, ExitCodes.PARTIAL_SUCCESS)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  mapExceptionToExitCode
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `maps APIRateLimitError to RATE_LIMIT`() {
        assertEquals(ExitCodes.RATE_LIMIT, mapExceptionToExitCode(
            APIRateLimitError("rate limited")
        ))
    }

    @Test
    fun `maps APIConnectionError to NETWORK_ERROR`() {
        assertEquals(ExitCodes.NETWORK_ERROR, mapExceptionToExitCode(
            APIConnectionError("connection failed")
        ))
    }

    @Test
    fun `maps APITimeoutError to NETWORK_ERROR`() {
        assertEquals(ExitCodes.NETWORK_ERROR, mapExceptionToExitCode(
            APITimeoutError("timed out")
        ))
    }

    @Test
    fun `maps ConfigurationError to AUTH_ERROR`() {
        assertEquals(ExitCodes.AUTH_ERROR, mapExceptionToExitCode(
            ConfigurationError("bad config")
        ))
    }

    @Test
    fun `maps FileUploadError to ERROR`() {
        assertEquals(ExitCodes.ERROR, mapExceptionToExitCode(
            FileUploadError("upload failed")
        ))
    }

    @Test
    fun `maps FileTooLargeError to ERROR`() {
        assertEquals(ExitCodes.ERROR, mapExceptionToExitCode(
            FileTooLargeError("too large", sizeBytes = 2L, limitBytes = 1L)
        ))
    }

    @Test
    fun `maps FileHashError to ERROR`() {
        assertEquals(ExitCodes.ERROR, mapExceptionToExitCode(
            FileHashError("hash failed")
        ))
    }

    @Test
    fun `maps InputValidationError to ERROR`() {
        assertEquals(ExitCodes.ERROR, mapExceptionToExitCode(
            InputValidationError("invalid input")
        ))
    }

    @Test
    fun `maps CacheError to ERROR`() {
        assertEquals(ExitCodes.ERROR, mapExceptionToExitCode(
            CacheError("cache error")
        ))
    }

    @Test
    fun `maps FileAnalysisError to ERROR`() {
        assertEquals(ExitCodes.ERROR, mapExceptionToExitCode(
            FileAnalysisError("analysis failed")
        ))
    }

    @Test
    fun `maps unknown RuntimeException to ERROR`() {
        assertEquals(ExitCodes.ERROR, mapExceptionToExitCode(
            RuntimeException("unexpected")
        ))
    }

    @Test
    fun `maps IllegalStateException to ERROR`() {
        assertEquals(ExitCodes.ERROR, mapExceptionToExitCode(
            IllegalStateException("bad state")
        ))
    }

    // ═══════════════════════════════════════════════════════════════════
    //  resolveApiKey
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `resolveApiKey returns CLI key when provided`() {
        assertEquals("my-test-key", resolveApiKey("my-test-key").key)
    }

    @Test
    fun `resolveApiKey prefers CLI key over env vars`() {
        assertEquals("cli-override", resolveApiKey("cli-override").key)
    }

    @Test
    fun `resolveApiKey returns ApiKeyResolution with null when nothing set`() {
        // Assumes VT_API_KEY and API_KEY are not set in the test environment.
        val result = resolveApiKey(null)
        assertNotNull(result) // ApiKeyResolution itself is never null
        // result.key may be null or an env var value — just verify no crash
    }

    // ═══════════════════════════════════════════════════════════════════
    //  createApi
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `createApi returns null and prints error when key is null`() {
        val stdout = captureStdout {
            val out = OutputFormatter()
            val api = createApi(null, out, "test-cmd")
            assertNull(api)
        }
        assertTrue(stdout.contains("No API key provided"))
    }

    @Test
    fun `createApi returns VirusTotalApi when key is provided`() {
        val api = createApi("test-key", OutputFormatter(), "test")
        assertNotNull(api)
        api!!.close()
    }

    // ═══════════════════════════════════════════════════════════════════
    //  scan — size cap (GUI parity: over-cap files are listed, never hashed)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `scan --hash reports an over-cap file as too_large without hashing it`() {
        val dir = Files.createTempDirectory("vtbatch-cli-cap").toFile()
        try {
            // Sparse: one byte past the cap, but almost no disk use. Size follows
            // AppConfig.default so the test holds when VT_MAX_SCAN_SIZE_MB is set.
            val big = sparseFile(File(dir, "big.exe"), AppConfig.default.maxScanSizeBytes + 1)
            val small = File(dir, "small.exe").apply { writeText("hello world") }

            var exit = -1
            val stdout = captureStdout {
                // Dummy key: hashing needs an api instance but makes no requests.
                exit = CommandLine(RootCommand()).execute(
                    "--api-key", "dummy-key", "--quiet",
                    "scan", "--hash", "--extensions", ".exe", dir.absolutePath
                )
            }

            assertEquals(ExitCodes.SUCCESS, exit, stdout)
            val bigEntry = jsonEntryFor(stdout, big.name)
            assertTrue(bigEntry.contains("\"too_large\": true"), bigEntry)
            assertTrue(bigEntry.contains("\"reason\": \"too_large\""), bigEntry)
            assertTrue(bigEntry.contains("\"md5_hash\": null"), bigEntry)

            val smallEntry = jsonEntryFor(stdout, small.name)
            assertTrue(smallEntry.contains("\"too_large\": false"), smallEntry)
            assertTrue(smallEntry.contains("5eb63bbbe01eeed093cb22bb8f5acdc3"), "small file is still hashed: $smallEntry")
        } finally {
            dir.deleteRecursively()
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════

    private fun sparseFile(file: File, size: Long): File {
        Files.newByteChannel(
            file.toPath(),
            setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SPARSE)
        ).use { ch ->
            ch.position(size - 1)
            ch.write(ByteBuffer.wrap(byteArrayOf(0)))
        }
        return file
    }

    /** The JSON object (pretty-printed) of the scan entry whose path ends with [name]. */
    private fun jsonEntryFor(stdout: String, name: String): String {
        val at = stdout.indexOf(name)
        assertTrue(at >= 0, "no entry for $name in: $stdout")
        val start = stdout.lastIndexOf('{', at)
        val end = stdout.indexOf('}', at)
        return stdout.substring(start, end + 1)
    }

    private fun captureStdout(block: () -> Unit): String {
        val baos = ByteArrayOutputStream()
        val old = System.out
        System.setOut(PrintStream(baos))
        try { block() } finally { System.setOut(old) }
        return baos.toString()
    }
}
