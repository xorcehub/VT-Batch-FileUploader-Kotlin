package com.vtbatch.model

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest

private val logger = KotlinLogging.logger {}

/**
 * VirusTotal API client using Ktor (async HTTP client).
 * Matches the Python version's endpoints and error handling.
 *
 * @param engine Optional HTTP engine — pass [MockEngine] in tests to inject responses.
 */
class VirusTotalApi(
    apiKey: String,
    private val rateLimiter: RateLimiter? = null,
    private val config: AppConfig = AppConfig.default,
    engine: HttpClientEngine = OkHttp.create()
) : java.io.Closeable {
    private val secureKey = SecureApiKey(apiKey)
    private val baseUrl = config.apiBaseUrl

    private val client = HttpClient(engine) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = config.timeout * 1000L
            connectTimeoutMillis = config.shortTimeout * 1000L
        }
    }

    /** Get the API key for HTTP headers. Internal-only to avoid exposing as immutable String. */
    internal fun getApiKey(): String = secureKey.get()

    fun clearApiKey() = secureKey.clear()

    /** Validate API key by hitting /users/current */
    suspend fun validateCredentials(): Boolean {
        return try {
            val response = client.get("$baseUrl/users/current") {
                header("x-apikey", getApiKey())
                timeout { requestTimeoutMillis = 10_000 }
            }
            when (response.status.value) {
                200 -> true
                401, 403 -> false
                else -> false
            }
        } catch (e: Exception) {
            logger.error { "Error validating credentials: $e" }
            false
        }
    }

    /** Calculate MD5 hash of a local file */
    fun calculateMd5(filePath: String): String {
        val file = File(filePath)
        if (!file.exists()) throw FileHashError("File not found: $filePath", mapOf("file_path" to filePath))
        if (!file.canRead()) throw FileHashError("Permission denied: $filePath", mapOf("file_path" to filePath))

        return try {
            val digest = MessageDigest.getInstance("MD5")
            file.inputStream().buffered().use { stream ->
                val buffer = ByteArray(4096)
                var read = stream.read(buffer)
                while (read != -1) {
                    digest.update(buffer, 0, read)
                    read = stream.read(buffer)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            throw FileHashError("Could not hash file: $filePath", mapOf("file_path" to filePath), e)
        }
    }

    /** Check if a file hash exists on VirusTotal. Returns null if 404. */
    suspend fun checkFileOnVirusTotal(md5Hash: String): JsonObject? {
        rateLimiter?.acquire()

        return try {
            val response = client.get("$baseUrl/files/$md5Hash") {
                header("x-apikey", getApiKey())
                timeout { requestTimeoutMillis = config.shortTimeout * 1000L }
            }

            when (response.status.value) {
                200 -> response.body<JsonObject>()
                404 -> null
                429 -> {
                    val retryAfter = response.headers["Retry-After"]?.toDoubleOrNull()
                    throw APIRateLimitError("API rate limit exceeded", retryAfter = retryAfter, context = mapOf("hash" to md5Hash))
                }
                else -> throw APIResponseError(
                    "API returned ${response.status.value}",
                    statusCode = response.status.value,
                    context = mapOf("hash" to md5Hash)
                )
            }
        } catch (e: java.net.ConnectException) {
            throw APIConnectionError("Unable to connect to VirusTotal API", mapOf("hash" to md5Hash), e)
        } catch (e: java.net.SocketTimeoutException) {
            throw APITimeoutError("Request timed out checking hash", mapOf("hash" to md5Hash), e)
        } catch (e: Exception) {
            if (e is VTBatchError) throw e
            throw APIConnectionError("Request failed: ${e.message}", mapOf("hash" to md5Hash), e)
        }
    }

    /**
     * Upload a file to VirusTotal with optional byte-level progress tracking.
     * Files >= 32MB use a special upload URL obtained from /files/upload_url.
     */
    suspend fun uploadFileToVirusTotal(filePath: String, onProgress: ((bytesSent: Long, totalBytes: Long) -> Unit)? = null): JsonObject {
        rateLimiter?.acquire()
        val file = File(filePath)

        if (!file.exists()) throw FileUploadError("File not found: $filePath", mapOf("file_path" to filePath))

        // Pick the right upload URL based on file size
        val uploadUrl = if (file.length() >= config.largeFileThreshold) {
            logger.info { "Large file (${formatFileSize(file.length())}), requesting special upload URL..." }
            getLargeFileUploadUrl()
                ?: throw FileUploadError("Failed to get upload URL for large file", mapOf("file_path" to filePath))
        } else {
            "$baseUrl/files"
        }

        return try {
            val fileBytes = file.readBytes()
            val totalSize = fileBytes.size.toLong()
            val response = client.post(uploadUrl) {
                header("x-apikey", getApiKey())
                timeout { requestTimeoutMillis = config.longTimeout * 1000L }

                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("file", fileBytes, Headers.build {
                                append(HttpHeaders.ContentDisposition, "filename=\"${file.name}\"")
                                append(HttpHeaders.ContentType, "application/octet-stream")
                            })
                        }
                    )
                )

                onUpload { bytesSentTotal, _ ->
                    onProgress?.invoke(bytesSentTotal, totalSize)
                }
            }

            when (response.status.value) {
                200 -> response.body<JsonObject>()
                429 -> {
                    val retryAfter = response.headers["Retry-After"]?.toDoubleOrNull()
                    throw APIRateLimitError("Rate limit exceeded during upload", retryAfter = retryAfter, context = mapOf("file_path" to filePath))
                }
                else -> throw APIResponseError(
                    "Upload failed with status ${response.status.value}",
                    statusCode = response.status.value,
                    context = mapOf("file_path" to filePath)
                )
            }
        } catch (e: VTBatchError) { throw e }
        catch (e: java.net.ConnectException) {
            throw APIConnectionError("Unable to connect to VirusTotal API", mapOf("file_path" to filePath), e)
        } catch (e: java.net.SocketTimeoutException) {
            throw APITimeoutError("Upload timed out", mapOf("file_path" to filePath), e)
        } catch (e: Exception) {
            throw APIConnectionError("Upload failed: ${e.message}", mapOf("file_path" to filePath), e)
        }
    }

    /** Get special upload URL for files >= 32MB */
    private suspend fun getLargeFileUploadUrl(): String? {
        rateLimiter?.acquire()
        return try {
            val response = client.get("$baseUrl/files/upload_url") {
                header("x-apikey", getApiKey())
                timeout { requestTimeoutMillis = config.shortTimeout * 1000L }
            }
            if (response.status.value == 200) {
                val json = response.body<JsonObject>()
                json["data"]?.jsonPrimitive?.content
            } else null
        } catch (e: Exception) {
            logger.warn { "Failed to get large file upload URL: ${e.message}" }
            null
        }
    }

    /** Get analysis results by analysis ID */
    suspend fun getAnalysisResults(analysisId: String): JsonObject? {
        rateLimiter?.acquire()

        return try {
            val response = client.get("$baseUrl/analyses/$analysisId") {
                header("x-apikey", getApiKey())
            }
            when (response.status.value) {
                200 -> response.body<JsonObject>()
                404 -> null
                else -> throw APIResponseError("Analysis check returned ${response.status.value}", statusCode = response.status.value)
            }
        } catch (e: VTBatchError) { throw e }
        catch (e: Exception) {
            throw APIConnectionError("Failed to get analysis: ${e.message}", originalError = e)
        }
    }

    /** Request re-analysis of a file */
    suspend fun requestReanalysis(hash: String): JsonObject? {
        rateLimiter?.acquire()

        return try {
            val response = client.post("$baseUrl/files/$hash/reanalyse") {
                header("x-apikey", getApiKey())
            }
            when (response.status.value) {
                200 -> response.body<JsonObject>()
                else -> throw APIResponseError("Re-analysis request failed with ${response.status.value}", statusCode = response.status.value)
            }
        } catch (e: VTBatchError) { throw e }
        catch (e: Exception) {
            throw APIConnectionError("Re-analysis request failed: ${e.message}", originalError = e)
        }
    }

    override fun close() {
        client.close()
        secureKey.clear()
    }
}
