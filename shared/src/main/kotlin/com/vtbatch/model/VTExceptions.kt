package com.vtbatch.model

// Exception hierarchy matching the Python version exactly.
// "sealed class" in Kotlin = a restricted class hierarchy where all subclasses
// are known at compile time. The compiler guarantees exhaustive `when` checks.
// "open class" would also work but sealed is more Kotlin-idiomatic for this.

// Base exception — all custom errors inherit from this.
// Like Python's VTBatchError with message, context dict, and original_error.
open class VTBatchError(
    override val message: String,
    val context: Map<String, Any> = emptyMap(),
    val originalError: Throwable? = null
) : Exception(message, originalError) {

    override fun toString(): String = buildString {
        append(message)
        if (context.isNotEmpty()) {
            val ctx = context.entries.joinToString(", ") { "${it.key}=${it.value}" }
            append(" [$ctx]")
        }
        if (originalError != null) {
            append(" (caused by: $originalError)")
        }
    }

    fun toDict(): Map<String, String?> = mapOf(
        "type" to this::class.simpleName,
        "message" to message,
        "original_error" to originalError?.toString()
    )
}

// === Configuration Errors ===
class ConfigurationError(
    message: String,
    context: Map<String, Any> = emptyMap(),
    originalError: Throwable? = null
) : VTBatchError(message, context, originalError)

// === Network Errors ===
open class NetworkError(
    message: String,
    context: Map<String, Any> = emptyMap(),
    originalError: Throwable? = null
) : VTBatchError(message, context, originalError)

class APIConnectionError(
    message: String,
    context: Map<String, Any> = emptyMap(),
    originalError: Throwable? = null
) : NetworkError(message, context, originalError)

class APITimeoutError(
    message: String,
    context: Map<String, Any> = emptyMap(),
    originalError: Throwable? = null
) : NetworkError(message, context, originalError)

class APIRateLimitError(
    message: String = "API rate limit exceeded",
    val retryAfter: Double? = null,
    context: Map<String, Any> = emptyMap(),
    originalError: Throwable? = null
) : NetworkError(message, context, originalError)

class APIResponseError(
    message: String,
    val statusCode: Int? = null,
    val responseBody: String? = null,
    context: Map<String, Any> = emptyMap(),
    originalError: Throwable? = null
) : NetworkError(message, context, originalError)

// === File Processing Errors ===
open class FileProcessingError(
    message: String,
    context: Map<String, Any> = emptyMap(),
    originalError: Throwable? = null
) : VTBatchError(message, context, originalError)

class FileHashError(
    message: String,
    context: Map<String, Any> = emptyMap(),
    originalError: Throwable? = null
) : FileProcessingError(message, context, originalError)

class FileUploadError(
    message: String,
    context: Map<String, Any> = emptyMap(),
    originalError: Throwable? = null
) : FileProcessingError(message, context, originalError)

class FileAnalysisError(
    message: String,
    context: Map<String, Any> = emptyMap(),
    originalError: Throwable? = null
) : FileProcessingError(message, context, originalError)

// === Cache Errors ===
class CacheError(
    message: String,
    context: Map<String, Any> = emptyMap(),
    originalError: Throwable? = null
) : VTBatchError(message, context, originalError)

// === Input Validation Errors ===
class InputValidationError(
    message: String,
    val fieldName: String? = null,
    val invalidValue: String? = null,
    context: Map<String, Any> = emptyMap(),
    originalError: Throwable? = null
) : VTBatchError(message, context, originalError) {

    override fun toString(): String = buildString {
        if (fieldName != null) append("[$fieldName] ")
        append(message)
        if (context.isNotEmpty()) {
            val ctx = context.entries.joinToString(", ") { "${it.key}=${it.value}" }
            append(" [$ctx]")
        }
        if (originalError != null) append(" (caused by: $originalError)")
    }
}
