package com.vtbatch.model

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/** Input validation — matches Python's input_validator.py behavior */
object InputValidator {
    private val HASH_PATTERN = Regex("^[a-fA-F0-9]+$")
    private val EXTENSION_PATTERN = Regex("^\\.?[a-zA-Z0-9_-]+$")
    private val CONTROL_CHARS = Regex("[\\x00-\\x1f\\x7f-\\x9f]")

    fun validateSearchTerm(term: String, config: AppConfig = AppConfig.default): String {
        if (term.isBlank()) throw InputValidationError("Search term cannot be empty", "search_term", "(empty)")
        var result = term.trim()
        if (result.length > config.maxSearchTermLength) {
            val origLen = result.length
            result = result.take(config.maxSearchTermLength)
            logger.warn { "Search term truncated from $origLen to ${config.maxSearchTermLength}" }
        }
        return CONTROL_CHARS.replace(result, "")
    }

    fun validateHash(hashValue: String, config: AppConfig = AppConfig.default): String {
        if (hashValue.isBlank()) throw InputValidationError("Hash value cannot be empty", "hash", "(empty)")
        val hash = hashValue.trim().lowercase()
        if (hash.length < config.minHashLength)
            throw InputValidationError("Hash too short: expected at least ${config.minHashLength}, got ${hash.length}", "hash", hash.take(16))
        if (hash.length > config.maxHashLength)
            throw InputValidationError("Hash too long: expected at most ${config.maxHashLength}, got ${hash.length}", "hash", hash.take(16) + "...")
        if (!HASH_PATTERN.matches(hash))
            throw InputValidationError("Invalid hash format: must be hexadecimal (0-9, a-f)", "hash", hash.take(16))
        return hash
    }

    fun validateExtension(extension: String, config: AppConfig = AppConfig.default): String {
        if (extension.isBlank()) throw InputValidationError("Extension cannot be empty", "extension", "(empty)")
        var ext = extension.trim().lowercase()
        if (!ext.startsWith(".")) ext = ".$ext"
        if (ext.length > config.maxExtensionLength)
            throw InputValidationError("Extension too long: max ${config.maxExtensionLength}", "extension", ext)
        if (!EXTENSION_PATTERN.matches(ext))
            throw InputValidationError("Invalid extension: only alphanumeric, hyphens, underscores", "extension", ext)
        return ext
    }

    fun validateCommandLength(command: String, config: AppConfig = AppConfig.default): String {
        if (command.isBlank()) throw InputValidationError("Command cannot be empty", "command", "(empty)")
        val cmd = command.trim()
        if (cmd.length > config.maxCommandLength)
            throw InputValidationError("Command too long: max ${config.maxCommandLength}", "command", cmd.take(50) + "...")
        return cmd
    }

    /** Sanitize file path for display — truncates with ellipsis if too long */
    fun sanitizeFilePathForDisplay(filePath: String, config: AppConfig = AppConfig.default): Pair<String, Boolean> {
        if (filePath.isEmpty()) return "" to false
        var sanitized = CONTROL_CHARS.replace(filePath, "")
        var truncated = false
        if (sanitized.length > config.maxFilePathDisplayLength) {
            truncated = true
            val filename = sanitized.substringAfterLast('/', sanitized.substringAfterLast('\\'))
            val maxLen = config.maxFilePathDisplayLength
            sanitized = if (filename.length >= maxLen - 3) {
                filename.take(maxLen - 3) + "..."
            } else {
                val available = maxLen - filename.length - 5
                if (available > 10) "${sanitized.take(available)}/.../$filename"
                else sanitized.take(maxLen - 3) + "..."
            }
        }
        return sanitized to truncated
    }
}
