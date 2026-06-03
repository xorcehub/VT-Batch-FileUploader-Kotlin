package com.vtbatch.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutputFormatterTest {

    private fun captureStdout(block: () -> Unit): String {
        val baos = ByteArrayOutputStream()
        val old = System.out
        System.setOut(PrintStream(baos))
        try { block() } finally { System.setOut(old) }
        return baos.toString()
    }

    private fun captureStderr(block: () -> Unit): String {
        val baos = ByteArrayOutputStream()
        val old = System.err
        System.setErr(PrintStream(baos))
        try { block() } finally { System.setErr(old) }
        return baos.toString()
    }

    // ═══════════════════════════════════════════════════════════════════
    //  JSON mode — success
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `JSON success contains success true`() {
        val out = captureStdout {
            OutputFormatter().success("test", mapOf("key" to "value"))
        }
        assertTrue(out.contains("\"success\": true"))
    }

    @Test
    fun `JSON success includes command name`() {
        val out = captureStdout {
            OutputFormatter().success("upload", mapOf("files" to 3))
        }
        assertTrue(out.contains("\"command\": \"upload\""))
    }

    @Test
    fun `JSON success includes timestamp`() {
        val out = captureStdout {
            OutputFormatter().success("test", mapOf("result" to "ok"))
        }
        assertTrue(out.contains("\"timestamp\":"))
    }

    @Test
    fun `JSON success includes data fields`() {
        val out = captureStdout {
            OutputFormatter().success("check", mapOf("found" to true, "hash" to "abc123"))
        }
        assertTrue(out.contains("\"found\": true"))
        assertTrue(out.contains("\"hash\": \"abc123\""))
    }

    @Test
    fun `JSON success includes summary when provided`() {
        val out = captureStdout {
            OutputFormatter().success("scan", mapOf("files" to listOf("a.exe")), mapOf("total" to 1))
        }
        assertTrue(out.contains("\"summary\""))
        assertTrue(out.contains("\"total\": 1"))
    }

    @Test
    fun `JSON success omits summary when empty`() {
        val out = captureStdout {
            OutputFormatter().success("test", mapOf("ok" to true))
        }
        assertFalse(out.contains("\"summary\""))
    }

    // ═══════════════════════════════════════════════════════════════════
    //  JSON mode — error
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `JSON error contains success false`() {
        val out = captureStdout {
            OutputFormatter().error("check", "not found", "Error", 2)
        }
        assertTrue(out.contains("\"success\": false"))
    }

    @Test
    fun `JSON error includes error message and type`() {
        val out = captureStdout {
            OutputFormatter().error("upload", "file too large", "FileTooLarge", 2)
        }
        assertTrue(out.contains("\"message\": \"file too large\""))
        assertTrue(out.contains("\"type\": \"FileTooLarge\""))
    }

    @Test
    fun `JSON error includes exit code`() {
        val out = captureStdout {
            OutputFormatter().error("validate", "bad key", "AuthError", 3)
        }
        assertTrue(out.contains("\"exit_code\": 3"))
    }

    // ═══════════════════════════════════════════════════════════════════
    //  TEXT mode
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `TEXT success prints OK and command`() {
        val out = captureStdout {
            OutputFormatter(OutputFormatter.OutputFormat.TEXT)
                .success("scan", mapOf("files" to 5))
        }
        assertTrue(out.contains("[OK] scan"))
    }

    @Test
    fun `TEXT error prints ERROR and message to stderr`() {
        val err = captureStderr {
            OutputFormatter(OutputFormatter.OutputFormat.TEXT)
                .error("check", "not found")
        }
        assertTrue(err.contains("[ERROR]"))
        assertTrue(err.contains("not found"))
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Progress / debug — visibility by mode
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `progress prints in TEXT mode`() {
        val err = captureStderr {
            OutputFormatter(OutputFormatter.OutputFormat.TEXT)
                .progress("Hashing file.exe...")
        }
        assertTrue(err.contains("[...]"))
        assertTrue(err.contains("Hashing file.exe..."))
    }

    @Test
    fun `progress is silent in JSON mode`() {
        val err = captureStderr {
            OutputFormatter().progress("Hashing file.exe...")
        }
        assertTrue(err.isEmpty())
    }

    @Test
    fun `debug prints in TEXT mode`() {
        val err = captureStderr {
            OutputFormatter(OutputFormatter.OutputFormat.TEXT)
                .debug("Sending request...")
        }
        assertTrue(err.contains("[DEBUG]"))
    }

    @Test
    fun `debug is silent in JSON mode`() {
        val err = captureStderr {
            OutputFormatter().debug("Sending request...")
        }
        assertTrue(err.isEmpty())
    }
}
