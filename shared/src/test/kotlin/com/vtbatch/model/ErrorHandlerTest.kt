package com.vtbatch.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ErrorHandlerTest {

    private val handler = ErrorHandler()

    // ═══════════════════════════════════════════════════════════════════
    //  APIRateLimitError
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `rate limit with retryAfter includes seconds`() {
        val msg = handler.getUserMessage(APIRateLimitError("rate limited", retryAfter = 60.0))
        assertTrue(msg.contains("60"))
        assertTrue(msg.contains("seconds"))
    }

    @Test
    fun `rate limit without retryAfter is generic`() {
        val msg = handler.getUserMessage(APIRateLimitError("rate limited"))
        assertTrue(msg.contains("rate limit", ignoreCase = true))
        assertTrue(msg.contains("try again", ignoreCase = true))
        assertTrue(!msg.contains("seconds"))
    }

    // ═══════════════════════════════════════════════════════════════════
    //  APITimeoutError
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `timeout error suggests checking connection`() {
        val msg = handler.getUserMessage(APITimeoutError("timed out"))
        assertTrue(msg.contains("timed out", ignoreCase = true))
        assertTrue(msg.contains("connection", ignoreCase = true))
    }

    // ═══════════════════════════════════════════════════════════════════
    //  APIConnectionError
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `connection error suggests checking internet`() {
        val msg = handler.getUserMessage(APIConnectionError("connection failed"))
        assertTrue(msg.contains("Unable to connect", ignoreCase = true))
        assertTrue(msg.contains("internet", ignoreCase = true))
    }

    // ═══════════════════════════════════════════════════════════════════
    //  APIResponseError — status code mapping
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `401 response says invalid API key`() {
        val msg = handler.getUserMessage(APIResponseError("unauthorized", statusCode = 401))
        assertTrue(msg.contains("Invalid API key", ignoreCase = true))
    }

    @Test
    fun `403 response says access denied`() {
        val msg = handler.getUserMessage(APIResponseError("forbidden", statusCode = 403))
        assertTrue(msg.contains("Access denied", ignoreCase = true))
    }

    @Test
    fun `404 response says not found`() {
        val msg = handler.getUserMessage(APIResponseError("not found", statusCode = 404))
        assertTrue(msg.contains("not found", ignoreCase = true))
    }

    @Test
    fun `500 response says service issues`() {
        val msg = handler.getUserMessage(APIResponseError("internal error", statusCode = 500))
        assertTrue(msg.contains("experiencing issues", ignoreCase = true))
    }

    @Test
    fun `503 response says service issues`() {
        val msg = handler.getUserMessage(APIResponseError("unavailable", statusCode = 503))
        assertTrue(msg.contains("experiencing issues", ignoreCase = true))
    }

    @Test
    fun `other status code falls back to generic API error`() {
        val msg = handler.getUserMessage(APIResponseError("conflict", statusCode = 409))
        assertTrue(msg.contains("API error"))
    }

    // ═══════════════════════════════════════════════════════════════════
    //  File processing errors
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `FileHashError includes error message`() {
        val msg = handler.getUserMessage(FileHashError("disk read failed"))
        assertTrue(msg.contains("Could not hash file"))
        assertTrue(msg.contains("disk read failed"))
    }

    @Test
    fun `FileUploadError includes error message`() {
        val msg = handler.getUserMessage(FileUploadError("network interrupted"))
        assertTrue(msg.contains("Upload failed"))
        assertTrue(msg.contains("network interrupted"))
    }

    @Test
    fun `FileAnalysisError includes error message`() {
        val msg = handler.getUserMessage(FileAnalysisError("no results"))
        assertTrue(msg.contains("Analysis failed"))
        assertTrue(msg.contains("no results"))
    }

    // ═══════════════════════════════════════════════════════════════════
    //  CacheError / ConfigurationError
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `CacheError includes error message`() {
        val msg = handler.getUserMessage(CacheError("file corrupt"))
        assertTrue(msg.contains("Cache error"))
        assertTrue(msg.contains("file corrupt"))
    }

    @Test
    fun `ConfigurationError includes error message`() {
        val msg = handler.getUserMessage(ConfigurationError("missing key"))
        assertTrue(msg.contains("Configuration error"))
        assertTrue(msg.contains("missing key"))
    }

    // ═══════════════════════════════════════════════════════════════════
    //  VTBatchError fallback
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `VTBatchError returns message`() {
        val msg = handler.getUserMessage(VTBatchError("generic batch error"))
        assertEquals("generic batch error", msg)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Generic Throwable fallback
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `unknown exception returns unexpected error message`() {
        val msg = handler.getUserMessage(RuntimeException("something broke"))
        assertTrue(msg.contains("unexpected error", ignoreCase = true))
        assertTrue(msg.contains("something broke"))
    }

    @Test
    fun `unknown exception with null message returns Unknown error`() {
        val msg = handler.getUserMessage(RuntimeException())
        assertTrue(msg.contains("Unknown error", ignoreCase = true))
    }

    // ═══════════════════════════════════════════════════════════════════
    //  handle() returns same message as getUserMessage()
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `handle returns same message as getUserMessage`() {
        val error = APIConnectionError("failed")
        assertEquals(handler.getUserMessage(error), handler.handle(error))
    }

    @Test
    fun `handle with context still returns user message`() {
        val error = APIRateLimitError("limited")
        val msg = handler.handle(error, mapOf("endpoint" to "files"))
        assertTrue(msg.contains("rate limit", ignoreCase = true))
    }
}
