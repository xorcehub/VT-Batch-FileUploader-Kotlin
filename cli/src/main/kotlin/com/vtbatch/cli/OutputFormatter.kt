package com.vtbatch.cli

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.*
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Formats CLI output as JSON or human-readable text.
 * Matches Python CLI's output.py behavior.
 */
class OutputFormatter(private val format: OutputFormat = OutputFormat.JSON) {

    enum class OutputFormat { JSON, TEXT }

    /** Print a successful result */
    fun success(command: String, data: Map<String, Any?> = emptyMap(), summary: Map<String, Any?> = emptyMap()) {
        when (format) {
            OutputFormat.JSON -> printJson(command, data, summary)
            OutputFormat.TEXT -> printText(command, data, summary)
        }
    }

    /** Print an error */
    fun error(command: String, message: String, type: String = "Error", exitCode: Int = 2) {
        when (format) {
            OutputFormat.JSON -> {
                val obj = buildJsonObject {
                    put("success", false)
                    put("command", command)
                    put("timestamp", timestamp())
                    put("error", buildJsonObject {
                        put("type", type)
                        put("message", message)
                    })
                    put("exit_code", exitCode)
                }
                println(obj.toJsonString())
            }
            OutputFormat.TEXT -> {
                System.err.println("[ERROR] $message")
            }
        }
    }

    /** Print a progress message (only in text mode, to stderr) */
    fun progress(message: String) {
        if (format == OutputFormat.TEXT) {
            System.err.println("[...] $message")
        }
    }

    /** Print a verbose/debug message (only in text mode, to stderr) */
    fun debug(message: String) {
        if (format == OutputFormat.TEXT) {
            System.err.println("[DEBUG] $message")
        }
    }

    private fun printJson(command: String, data: Map<String, Any?>, summary: Map<String, Any?>) {
        val obj = buildJsonObject {
            put("success", true)
            put("command", command)
            put("timestamp", timestamp())
            put("data", toJsonElement(data).jsonObject)
            if (summary.isNotEmpty()) {
                put("summary", toJsonElement(summary).jsonObject)
            }
            put("errors", buildJsonArray {})
        }
        println(obj.toJsonString())
    }

    private fun printText(command: String, data: Map<String, Any?>, summary: Map<String, Any?>) {
        println("[OK] $command")
        printDataMap(data, indent = 1)
        if (summary.isNotEmpty()) {
            println("Summary:")
            printDataMap(summary, indent = 1)
        }
    }

    private fun printDataMap(data: Map<String, Any?>, indent: Int) {
        val prefix = "  ".repeat(indent)
        for ((key, value) in data) {
            when (value) {
                is Map<*, *> -> {
                    println("${prefix}${key}:")
                    @Suppress("UNCHECKED_CAST")
                    printDataMap(value as Map<String, Any?>, indent + 1)
                }
                is List<*> -> {
                    println("${prefix}${key}:")
                    for ((i, item) in value.withIndex()) {
                        if (item is Map<*, *>) {
                            @Suppress("UNCHECKED_CAST")
                            println("${prefix}  [${i + 1}]")
                            printDataMap(item as Map<String, Any?>, indent + 2)
                        } else {
                            println("${prefix}  - $item")
                        }
                    }
                }
                null -> println("${prefix}${key}: N/A")
                else -> println("${prefix}${key}: $value")
            }
        }
    }

    companion object {
        internal val PRETTY_JSON = Json { prettyPrint = true }

        fun timestamp(): String = Instant.now().toString()
    }
}

// ── Extension: convert Any? to JsonElement ────────────────────────────

private fun toJsonElement(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is String -> JsonPrimitive(value)
    is Map<*, *> -> buildJsonObject {
        @Suppress("UNCHECKED_CAST")
        for ((k, v) in (value as Map<String, Any?>)) {
            put(k, toJsonElement(v))
        }
    }
    is List<*> -> buildJsonArray {
        for (item in value) {
            add(toJsonElement(item))
        }
    }
    else -> {
        logger.warn { "Serializing unknown type ${value.javaClass.simpleName} via toString()" }
        JsonPrimitive(value.toString())
    }
}

private fun JsonObject.toJsonString(): String = OutputFormatter.PRETTY_JSON.encodeToString(JsonObject.serializer(), this)
