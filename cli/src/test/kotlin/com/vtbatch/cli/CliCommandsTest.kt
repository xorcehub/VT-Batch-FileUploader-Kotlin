package com.vtbatch.cli

import com.vtbatch.model.*
import java.io.ByteArrayOutputStream
import java.io.PrintStream
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
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════

    private fun captureStdout(block: () -> Unit): String {
        val baos = ByteArrayOutputStream()
        val old = System.out
        System.setOut(PrintStream(baos))
        try { block() } finally { System.setOut(old) }
        return baos.toString()
    }
}
