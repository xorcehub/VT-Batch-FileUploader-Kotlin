package com.vtbatch.model

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Maps exceptions to user-friendly messages.
 * Matches Python's error_handler.py behavior exactly.
 */
class ErrorHandler {
    fun handle(error: Throwable, context: Map<String, Any>? = null): String {
        logError(error, context)
        return getUserMessage(error)
    }

    private fun logError(error: Throwable, context: Map<String, Any>?) {
        val ctx = if (context != null) " $context" else ""
        when (error) {
            is APIConnectionError, is APITimeoutError -> logger.warn { "Network error$ctx: $error" }
            is APIRateLimitError -> logger.warn { "Rate limit error$ctx: $error" }
            is ConfigurationError, is CacheError -> logger.error { "Application error$ctx: $error" }
            is VTBatchError -> logger.error { "Application error$ctx: $error" }
            else -> logger.error(error) { "Unexpected error$ctx: $error" }
        }
    }

    fun getUserMessage(error: Throwable): String = when (error) {
        is APIRateLimitError -> {
            if (error.retryAfter != null) "Rate limit exceeded. Please wait ${error.retryAfter.toInt()} seconds."
            else "API rate limit exceeded. Please try again later."
        }
        is APITimeoutError -> "Request timed out. Please check your connection and try again."
        is APIConnectionError -> "Unable to connect to VirusTotal. Please check your internet connection."
        is APIResponseError -> when (error.statusCode) {
            401 -> "Invalid API key. Please check your configuration."
            403 -> "Access denied. Your API key may lack required permissions."
            404 -> "Resource not found on VirusTotal."
            in 500..599 -> "VirusTotal service is experiencing issues. Please try again later."
            else -> "API error: ${error.message}"
        }
        is FileHashError -> "Could not hash file: ${error.message}"
        is FileUploadError -> "Upload failed: ${error.message}"
        is FileAnalysisError -> "Analysis failed: ${error.message}"
        is CacheError -> "Cache error: ${error.message}"
        is ConfigurationError -> "Configuration error: ${error.message}"
        is VTBatchError -> error.message ?: "Unknown error"
        else -> "An unexpected error occurred: ${error.message ?: "Unknown error"}"
    }
}
