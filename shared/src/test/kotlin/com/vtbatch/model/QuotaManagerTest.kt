package com.vtbatch.model

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertTrue(manager.saveEntry("abc123hash", entry))
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
        manager.saveEntry("hash1", QuotaManager.CacheEntry(filename = "a.exe", lastScan = now))
        assertTrue(manager.clearCache())
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
        assertTrue(manager.saveData(statusData))
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
        assertTrue(manager.saveData(statusData))
        assertTrue(manager.loadData().isEmpty())
    }

    @Test
    fun `saveEntry overwrites existing entry`() {
        val (manager, _) = tempQuotaManager()
        val now = LocalDateTime.now().toString()
        manager.saveEntry("hash1", QuotaManager.CacheEntry(filename = "old.exe", lastScan = now))
        manager.saveEntry("hash1", QuotaManager.CacheEntry(filename = "new.exe", lastScan = now))
        val loaded = manager.loadData()
        assertEquals(1, loaded.size)
        assertEquals("new.exe", loaded["hash1"]?.filename)
    }
}
