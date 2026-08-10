package com.vtbatch.model

import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuotaManagerTest {

    private fun tempQuotaManager(): Pair<QuotaManager, File> {
        val tempFile = File.createTempFile("vt_test_cache", ".json")
        tempFile.deleteOnExit()
        val config = AppConfig(
            timeout = 30, shortTimeout = 15, longTimeout = 60,
            cacheDurationHours = 24
        )
        return QuotaManager(tempFile.absolutePath, config) to tempFile
    }

    @Test
    fun `loadData returns empty map for missing file`() {
        val tempFile = File.createTempFile("vt_test_missing", ".json")
        tempFile.delete()
        tempFile.deleteOnExit()
        val config = AppConfig(timeout = 30, shortTimeout = 15, longTimeout = 60)
        val manager = QuotaManager(tempFile.absolutePath, config)
        assertTrue(manager.loadData().isEmpty())
    }

    @Test
    fun `loadData returns empty map for empty file`() {
        val (manager, _) = tempQuotaManager()
        assertTrue(manager.loadData().isEmpty())
    }

    @Test
    fun `loadData returns empty map for corrupted JSON`() {
        val (manager, file) = tempQuotaManager()
        file.writeText("NOT VALID JSON {{{")
        assertTrue(manager.loadData().isEmpty())
    }

    @Test
    fun `saveEntry and loadData round-trip`() {
        val (manager, _) = tempQuotaManager()
        val now = LocalDateTime.now().toString()
        val entry = QuotaManager.CacheEntry(
            filename = "test.exe",
            size = 1024L,
            path = "/test/test.exe",
            url = "https://vt.com/file/test",
            lastScan = now,
            status = "HASHED_FOUND",
            lastAnalysisStats = "0 malicious, 72 harmless",
            detectionCount = 0
        )
        assertTrue(runBlocking { manager.saveEntry("abc123hash", entry) })
        val loaded = manager.loadData()
        assertEquals(1, loaded.size)
        val loadedEntry = loaded["abc123hash"]
        assertNotNull(loadedEntry)
        assertEquals("test.exe", loadedEntry.filename)
        assertEquals(1024L, loadedEntry.size)
        assertEquals("/test/test.exe", loadedEntry.path)
        assertEquals(0, loadedEntry.detectionCount)
    }

    @Test
    fun `loadData filters out expired entries`() {
        val (manager, file) = tempQuotaManager()
        val oldDate = LocalDateTime.now().minusHours(25).toString()
        file.writeText("""
        {
            "old_hash": {
                "filename": "old.exe",
                "last_scan": "$oldDate",
                "status": "HASHED_FOUND"
            }
        }
        """.trimIndent())
        assertTrue(manager.loadData().isEmpty(), "Expired entry should be filtered out")
    }

    @Test
    fun `loadData keeps non-expired entries`() {
        val (manager, file) = tempQuotaManager()
        val recentDate = LocalDateTime.now().minusHours(1).toString()
        file.writeText("""
        {
            "fresh_hash": {
                "filename": "fresh.exe",
                "last_scan": "$recentDate",
                "status": "HASHED_FOUND"
            }
        }
        """.trimIndent())
        val loaded = manager.loadData()
        assertEquals(1, loaded.size)
        assertNotNull(loaded["fresh_hash"])
    }

    @Test
    fun `loadData skips entries without lastScan`() {
        val (manager, file) = tempQuotaManager()
        file.writeText("""
        {
            "no_scan": {
                "filename": "noscan.exe",
                "status": "PENDING"
            }
        }
        """.trimIndent())
        assertTrue(manager.loadData().isEmpty(), "Entry without lastScan should be skipped")
    }

    @Test
    fun `clearCache empties the cache`() {
        val (manager, _) = tempQuotaManager()
        val now = LocalDateTime.now().toString()
        runBlocking { manager.saveEntry("hash1", QuotaManager.CacheEntry(filename = "a.exe", lastScan = now)) }
        assertTrue(runBlocking { manager.clearCache() })
        assertTrue(manager.loadData().isEmpty())
    }

    @Test
    fun `saveData writes entries from file status map`() {
        val (manager, _) = tempQuotaManager()
        val statusData = mapOf(
            "/test/file.exe" to mapOf<String, Any?>(
                "md5_hash" to "abc123",
                "status" to "HASHED_FOUND",
                "analysis_url" to "https://vt.com/file/abc123"
            )
        )
        assertTrue(runBlocking { manager.saveData(statusData) })
        val loaded = manager.loadData()
        assertEquals(1, loaded.size)
        assertNotNull(loaded["abc123"])
    }

    @Test
    fun `saveData skips entries without md5_hash`() {
        val (manager, _) = tempQuotaManager()
        val statusData = mapOf(
            "/test/file.exe" to mapOf<String, Any?>(
                "status" to "PENDING"
            )
        )
        assertTrue(runBlocking { manager.saveData(statusData) })
        assertTrue(manager.loadData().isEmpty())
    }

    @Test
    fun `saveEntry overwrites existing entry`() {
        val (manager, _) = tempQuotaManager()
        val now = LocalDateTime.now().toString()
        runBlocking { manager.saveEntry("hash1", QuotaManager.CacheEntry(filename = "old.exe", lastScan = now)) }
        runBlocking { manager.saveEntry("hash1", QuotaManager.CacheEntry(filename = "new.exe", lastScan = now)) }
        val loaded = manager.loadData()
        assertEquals(1, loaded.size)
        assertEquals("new.exe", loaded["hash1"]?.filename)
    }

    // W5: saveEntries persists a batch in a single read-modify-write and round-trips.
    @Test
    fun `saveEntries persists a batch in one write`() {
        val (manager, _) = tempQuotaManager()
        val now = LocalDateTime.now().toString()
        val batch = mapOf(
            "hash1" to QuotaManager.CacheEntry(filename = "a.exe", lastScan = now),
            "hash2" to QuotaManager.CacheEntry(filename = "b.exe", lastScan = now),
            "hash3" to QuotaManager.CacheEntry(filename = "c.exe", lastScan = now)
        )

        assertTrue(runBlocking { manager.saveEntries(batch) })

        val loaded = manager.loadData()
        assertEquals(3, loaded.size)
        assertEquals("a.exe", loaded["hash1"]?.filename)
        assertEquals("b.exe", loaded["hash2"]?.filename)
        assertEquals("c.exe", loaded["hash3"]?.filename)
    }

    @Test
    fun `saveEntries merges with existing entries`() {
        val (manager, _) = tempQuotaManager()
        val now = LocalDateTime.now().toString()
        runBlocking { manager.saveEntry("existing", QuotaManager.CacheEntry(filename = "old.exe", lastScan = now)) }

        runBlocking { manager.saveEntries(mapOf(
            "new1" to QuotaManager.CacheEntry(filename = "n1.exe", lastScan = now),
            "new2" to QuotaManager.CacheEntry(filename = "n2.exe", lastScan = now)
        )) }

        val loaded = manager.loadData()
        assertEquals(3, loaded.size, "saveEntries must merge, not replace, the existing entries")
        assertNotNull(loaded["existing"])
    }

    @Test
    fun `saveEntries on empty map is a no-op`() {
        val (manager, file) = tempQuotaManager()
        // An empty file exists from tempQuotaManager; empty batch must not throw or rewrite.
        assertTrue(runBlocking { manager.saveEntries(emptyMap()) })
        assertTrue(manager.loadData().isEmpty())
    }

    // W9: writes go through temp-file + atomic move. After a write the cache file is
    // valid (round-trips) and no .tmp sibling lingers.
    @Test
    fun `atomic write leaves no tmp file and produces a valid cache`() {
        val (manager, file) = tempQuotaManager()
        val now = LocalDateTime.now().toString()
        runBlocking { manager.saveEntries(mapOf(
            "h1" to QuotaManager.CacheEntry(filename = "x.exe", lastScan = now)
        )) }

        val tmp = File(file.parentFile, "${file.name}.tmp")
        assertFalse(tmp.exists(), ".tmp sibling must be cleaned up after the atomic move")
        // The target file must hold valid, complete JSON (not a torn write).
        assertEquals(1, manager.loadData().size)
    }

    @Test
    fun `saveEntry still works after delegating to saveEntries`() {
        // Regression guard: saveEntry now delegates to saveEntries(mapOf(...)). Confirm
        // the single-entry path still round-trips and overwrites.
        val (manager, _) = tempQuotaManager()
        val now = LocalDateTime.now().toString()
        assertTrue(runBlocking { manager.saveEntry("h", QuotaManager.CacheEntry(filename = "a.exe", lastScan = now)) })
        assertTrue(runBlocking { manager.saveEntry("h", QuotaManager.CacheEntry(filename = "b.exe", lastScan = now)) })
        val loaded = manager.loadData()
        assertEquals(1, loaded.size)
        assertEquals("b.exe", loaded["h"]?.filename)
    }

    // --- Regression tests for timestamp format mismatch bug ---

    @Test
    fun `loadData reads Instant-formatted timestamps (Z suffix)`() {
        // Regression: buildCacheEntry() wrote Instant.now().toString() which
        // produces "2026-06-03T09:35:32.510212Z" — the Z suffix caused
        // LocalDateTime.parse() to fail, silently discarding every cache entry.
        val (manager, file) = tempQuotaManager()
        val instantTs = Instant.now().toString()  // e.g. "2026-06-03T09:35:32.510212Z"
        file.writeText("""
            {
              "abc123": {
                "last_scan": "$instantTs",
                "filename": "malware.exe"
              }
            }
        """.trimIndent())

        val loaded = manager.loadData()
        assertEquals(1, loaded.size, "Instant-formatted entry should NOT be silently discarded")
        assertEquals("malware.exe", loaded["abc123"]?.filename)
    }

    @Test
    fun `loadData reads LocalDateTime-formatted timestamps (no Z)`() {
        val (manager, file) = tempQuotaManager()
        val localTs = LocalDateTime.now().toString()  // e.g. "2026-06-03T09:35:32.510212"
        file.writeText("""
            {
              "def456": {
                "last_scan": "$localTs",
                "filename": "clean.exe"
              }
            }
        """.trimIndent())

        val loaded = manager.loadData()
        assertEquals(1, loaded.size)
        assertEquals("clean.exe", loaded["def456"]?.filename)
    }

    @Test
    fun `loadData handles mixed timestamp formats in same cache file`() {
        val (manager, file) = tempQuotaManager()
        val instantTs = Instant.now().toString()
        val localTs = LocalDateTime.now().toString()
        file.writeText("""
            {
              "hash_instant": {
                "last_scan": "$instantTs",
                "filename": "elf-binary"
              },
              "hash_local": {
                "last_scan": "$localTs",
                "filename": "macho-binary"
              }
            }
        """.trimIndent())

        val loaded = manager.loadData()
        assertEquals(2, loaded.size, "Both Instant and LocalDateTime entries should be readable")
        assertNotNull(loaded["hash_instant"])
        assertNotNull(loaded["hash_local"])
    }

    @Test
    fun `loadData skips unparseable timestamps`() {
        val (manager, file) = tempQuotaManager()
        file.writeText("""
            {
              "good": {
                "last_scan": "${LocalDateTime.now()}",
                "filename": "good.exe"
              },
              "bad": {
                "last_scan": "not-a-timestamp-at-all",
                "filename": "bad.exe"
              }
            }
        """.trimIndent())

        val loaded = manager.loadData()
        assertEquals(1, loaded.size)
        assertNotNull(loaded["good"])
        assertNull(loaded["bad"])
    }
}
