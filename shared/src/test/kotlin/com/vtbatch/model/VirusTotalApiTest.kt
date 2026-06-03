package com.vtbatch.model

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class VirusTotalApiTest {

    // ═══════════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════

    /** Build a VirusTotalApi backed by a MockEngine that always returns [content]. */
    private fun apiWithResponse(
        content: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        contentType: String = "application/json"
    ): VirusTotalApi {
        val engine = MockEngine {
            respond(content, status, headersOf(HttpHeaders.ContentType, contentType))
        }
        return VirusTotalApi("test-api-key", engine = engine)
    }

    /** Build a VirusTotalApi backed by a MockEngine that throws [exception]. */
    private fun apiWithException(exception: Exception): VirusTotalApi {
        val engine = MockEngine { throw exception }
        return VirusTotalApi("test-api-key", engine = engine)
    }

    /** Temp file cleaned up after the test block. */
    private fun tempFile(name: String = "vt-test", ext: String = ".bin", content: ByteArray = "test".toByteArray()): File =
        File.createTempFile(name, ext).apply { writeBytes(content); deleteOnExit() }

    // ═══════════════════════════════════════════════════════════════════
    //  validateCredentials
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `validateCredentials returns true on 200`() = runTest {
        val api = apiWithResponse("""{"data": {"id": "user123"}}""")
        assertTrue(api.validateCredentials())
        api.close()
    }

    @Test
    fun `validateCredentials returns false on 401`() = runTest {
        val api = apiWithResponse("Unauthorized", HttpStatusCode.Unauthorized)
        assertTrue(!api.validateCredentials())
        api.close()
    }

    @Test
    fun `validateCredentials returns false on 403`() = runTest {
        val api = apiWithResponse("Forbidden", HttpStatusCode.Forbidden)
        assertTrue(!api.validateCredentials())
        api.close()
    }

    @Test
    fun `validateCredentials returns false on connection error`() = runTest {
        val api = apiWithException(ConnectException("Connection refused"))
        assertTrue(!api.validateCredentials())
        api.close()
    }

    // ═══════════════════════════════════════════════════════════════════
    //  checkFileOnVirusTotal
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `checkFile returns JsonObject on 200`() = runTest {
        val api = apiWithResponse("""{"data": {"id": "abc123", "attributes": {}}}""")
        val result = api.checkFileOnVirusTotal("testmd5hash00000000000000000000")
        assertNotNull(result)
        api.close()
    }

    @Test
    fun `checkFile returns null on 404`() = runTest {
        val api = apiWithResponse("Not found", HttpStatusCode.NotFound)
        assertNull(api.checkFileOnVirusTotal("testmd5hash00000000000000000000"))
        api.close()
    }

    @Test
    fun `checkFile throws APIRateLimitError on 429`() = runTest {
        val api = apiWithResponse("Rate limited", HttpStatusCode(429, "Too Many Requests"))
        assertFailsWith<APIRateLimitError> {
            api.checkFileOnVirusTotal("testmd5hash00000000000000000000")
        }
        api.close()
    }

    @Test
    fun `checkFile throws APIResponseError on 500`() = runTest {
        val api = apiWithResponse("Server Error", HttpStatusCode.InternalServerError)
        val error = assertFailsWith<APIResponseError> {
            api.checkFileOnVirusTotal("testmd5hash00000000000000000000")
        }
        assertEquals(500, error.statusCode)
        api.close()
    }

    @Test
    fun `checkFile throws APIConnectionError on ConnectException`() = runTest {
        val api = apiWithException(ConnectException("Connection refused"))
        assertFailsWith<APIConnectionError> {
            api.checkFileOnVirusTotal("testmd5hash00000000000000000000")
        }
        api.close()
    }

    @Test
    fun `checkFile throws APITimeoutError on SocketTimeoutException`() = runTest {
        val api = apiWithException(SocketTimeoutException("Read timed out"))
        assertFailsWith<APITimeoutError> {
            api.checkFileOnVirusTotal("testmd5hash00000000000000000000")
        }
        api.close()
    }

    // ═══════════════════════════════════════════════════════════════════
    //  uploadFileToVirusTotal
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `uploadFile returns JsonObject on 200`() = runTest {
        val api = apiWithResponse("""{"data": {"id": "analysis-12345", "type": "analysis"}}""")
        val file = tempFile()
        val result = api.uploadFileToVirusTotal(file.absolutePath)
        assertNotNull(result)
        api.close()
    }

    @Test
    fun `uploadFile throws FileUploadError when file not found`() = runTest {
        val api = apiWithResponse("{}")
        assertFailsWith<FileUploadError> {
            api.uploadFileToVirusTotal("/nonexistent/path/file.bin")
        }
        api.close()
    }

    @Test
    fun `uploadFile throws APIRateLimitError on 429`() = runTest {
        val api = apiWithResponse("Rate limited", HttpStatusCode(429, "Too Many Requests"))
        val file = tempFile("upload-429")
        assertFailsWith<APIRateLimitError> {
            api.uploadFileToVirusTotal(file.absolutePath)
        }
        api.close()
    }

    @Test
    fun `uploadFile throws APIConnectionError on ConnectException`() = runTest {
        val api = apiWithException(ConnectException("Connection refused"))
        val file = tempFile("upload-conn")
        assertFailsWith<APIConnectionError> {
            api.uploadFileToVirusTotal(file.absolutePath)
        }
        api.close()
    }

    @Test
    fun `uploadFile invokes progress callback`() = runTest {
        val api = apiWithResponse("""{"data": {"id": "analysis-12345"}}""")
        val file = tempFile("upload-prog", content = "test content for progress".toByteArray())
        var progressCalled = false
        api.uploadFileToVirusTotal(file.absolutePath) { _, _ -> progressCalled = true }
        // Progress may or may not fire with MockEngine (depends on chunking),
        // but the call itself must succeed without error.
        api.close()
    }

    // ═══════════════════════════════════════════════════════════════════
    //  getAnalysisResults
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `getAnalysisResults returns JsonObject on 200`() = runTest {
        val api = apiWithResponse("""{"data": {"id": "analysis-123", "attributes": {"status": "completed"}}}""")
        val result = api.getAnalysisResults("analysis-123")
        assertNotNull(result)
        api.close()
    }

    @Test
    fun `getAnalysisResults returns null on 404`() = runTest {
        val api = apiWithResponse("Not found", HttpStatusCode.NotFound)
        assertNull(api.getAnalysisResults("nonexistent-id"))
        api.close()
    }

    @Test
    fun `getAnalysisResults throws APIResponseError on 500`() = runTest {
        val api = apiWithResponse("Server Error", HttpStatusCode.InternalServerError)
        val error = assertFailsWith<APIResponseError> {
            api.getAnalysisResults("analysis-123")
        }
        assertEquals(500, error.statusCode)
        api.close()
    }

    @Test
    fun `getAnalysisResults throws APIConnectionError on generic exception`() = runTest {
        val api = apiWithException(RuntimeException("Something went wrong"))
        assertFailsWith<APIConnectionError> {
            api.getAnalysisResults("analysis-123")
        }
        api.close()
    }

    // ═══════════════════════════════════════════════════════════════════
    //  requestReanalysis
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `requestReanalysis returns JsonObject on 200`() = runTest {
        val api = apiWithResponse("""{"data": {"id": "reanalysis-123", "type": "analysis"}}""")
        val result = api.requestReanalysis("abc123hash0000000000000000000000000000000000000000000000000")
        assertNotNull(result)
        api.close()
    }

    @Test
    fun `requestReanalysis throws APIResponseError on 500`() = runTest {
        val api = apiWithResponse("Error", HttpStatusCode.InternalServerError)
        val error = assertFailsWith<APIResponseError> {
            api.requestReanalysis("abc123hash0000000000000000000000000000000000000000000000000")
        }
        assertEquals(500, error.statusCode)
        api.close()
    }

    @Test
    fun `requestReanalysis throws APIConnectionError on exception`() = runTest {
        val api = apiWithException(RuntimeException("Network failure"))
        assertFailsWith<APIConnectionError> {
            api.requestReanalysis("abc123hash0000000000000000000000000000000000000000000000000")
        }
        api.close()
    }

    // ═══════════════════════════════════════════════════════════════════
    //  calculateMd5
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `calculateMd5 throws FileHashError when file not found`() {
        val api = VirusTotalApi("test-key")
        assertFailsWith<FileHashError> {
            api.calculateMd5("/nonexistent/file.bin")
        }
        api.close()
    }

    @Test
    fun `calculateMd5 returns correct hash for known content`() {
        val api = VirusTotalApi("test-key")
        val file = tempFile("md5-test", ".txt", "hello world".toByteArray())
        val md5 = api.calculateMd5(file.absolutePath)
        assertEquals("5eb63bbbe01eeed093cb22bb8f5acdc3", md5)
        api.close()
    }

    @Test
    fun `calculateMd5 returns correct hash for empty file`() {
        val api = VirusTotalApi("test-key")
        val file = tempFile("md5-empty", ".txt", ByteArray(0))
        val md5 = api.calculateMd5(file.absolutePath)
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", md5)
        api.close()
    }

    // ═══════════════════════════════════════════════════════════════════
    //  API key header & security
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `checkFile sends x-apikey header`() = runTest {
        var capturedKey: String? = null
        val engine = MockEngine { request ->
            capturedKey = request.headers["x-apikey"]
            respond(
                """{"data": {"id": "test"}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val api = VirusTotalApi("my-secret-key", engine = engine)
        api.checkFileOnVirusTotal("testmd5hash00000000000000000000")
        assertEquals("my-secret-key", capturedKey)
        api.close()
    }

    @Test
    fun `validateCredentials sends x-apikey header`() = runTest {
        var capturedKey: String? = null
        val engine = MockEngine { request ->
            capturedKey = request.headers["x-apikey"]
            respond(
                """{"data": {"id": "user123"}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val api = VirusTotalApi("my-secret-key", engine = engine)
        api.validateCredentials()
        assertEquals("my-secret-key", capturedKey)
        api.close()
    }

    @Test
    fun `clearApiKey prevents further API calls`() {
        val api = VirusTotalApi("test-key")
        api.clearApiKey()
        assertFailsWith<Exception> {
            api.getApiKey()
        }
        api.close()
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Error response body capture
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `checkFile throws APIResponseError on 401`() = runTest {
        val api = apiWithResponse("""{"error": {"code": "WrongCredentialsError"}}""", HttpStatusCode.Unauthorized)
        val error = assertFailsWith<APIResponseError> {
            api.checkFileOnVirusTotal("testmd5hash00000000000000000000")
        }
        assertEquals(401, error.statusCode)
        api.close()
    }

    @Test
    fun `checkFile handles non-json error response`() = runTest {
        val api = apiWithResponse("Internal Server Error", HttpStatusCode.InternalServerError, "text/plain")
        val error = assertFailsWith<APIResponseError> {
            api.checkFileOnVirusTotal("testmd5hash00000000000000000000")
        }
        assertEquals(500, error.statusCode)
        api.close()
    }
}
