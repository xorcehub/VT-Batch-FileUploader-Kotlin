package com.vtbatch.model

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsStoreTest {

    private fun createStore(): Pair<SettingsStore, File> {
        val dir = File(System.getProperty("java.io.tmpdir"), "vtbatch-settings-test-${System.nanoTime()}")
        dir.mkdirs()
        return SettingsStore(dir = dir) to dir
    }

    private fun cleanup(dir: File) { dir.deleteRecursively() }

    @Test
    fun `load returns defaults for missing file`() {
        val (store, dir) = createStore()
        val settings = store.load()
        assertNull(settings.analysisPollInterval)
        assertNull(settings.cacheDurationHours)
        cleanup(dir)
    }

    @Test
    fun `load returns defaults for empty file`() {
        val (store, dir) = createStore()
        File(dir, "settings.json").writeText("")
        val settings = store.load()
        assertNull(settings.analysisPollInterval)
        cleanup(dir)
    }

    @Test
    fun `load returns defaults for corrupted JSON`() {
        val (store, dir) = createStore()
        File(dir, "settings.json").writeText("not json {{{")
        val settings = store.load()
        assertNull(settings.analysisPollInterval)
        cleanup(dir)
    }

    @Test
    fun `save and load round-trip`() {
        val (store, dir) = createStore()
        val settings = UserSettings(
            analysisPollInterval = 20,
            cacheDurationHours = 48,
            shortTimeout = 30
        )
        store.save(settings)
        val loaded = store.load()
        assertEquals(20, loaded.analysisPollInterval)
        assertEquals(48, loaded.cacheDurationHours)
        assertEquals(30, loaded.shortTimeout)
        assertNull(loaded.analysisInitialDelay)
        assertNull(loaded.analysisMaxRetries)
        cleanup(dir)
    }

    @Test
    fun `save creates directory if missing`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "vtbatch-new-settings-${System.nanoTime()}")
        assertTrue(!dir.exists())
        val store = SettingsStore(dir = dir)
        store.save(UserSettings(analysisPollInterval = 15))
        assertTrue(dir.exists())
        assertEquals(15, store.load().analysisPollInterval)
        cleanup(dir)
    }

    @Test
    fun `save overwrites previous settings`() {
        val (store, dir) = createStore()
        store.save(UserSettings(analysisPollInterval = 10))
        store.save(UserSettings(analysisPollInterval = 30, cacheDurationHours = 12))
        val loaded = store.load()
        assertEquals(30, loaded.analysisPollInterval)
        assertEquals(12, loaded.cacheDurationHours)
        cleanup(dir)
    }

    @Test
    fun `AppConfig resolve uses settings values`() {
        val settings = UserSettings(analysisPollInterval = 99, cacheDurationHours = 72)
        val (config, overridden) = AppConfig.resolve(settings)
        assertEquals(99, config.analysisPollInterval)
        assertEquals(72, config.cacheDurationHours)
        // Other fields keep defaults
        assertEquals(ANALYSIS_INITIAL_DELAY, config.analysisInitialDelay)
        assertTrue(overridden.isEmpty())
    }

    @Test
    fun `AppConfig resolve env var overrides settings`() {
        // This test only works if the env vars are NOT set
        val settings = UserSettings(analysisPollInterval = 99)
        val (_, overridden) = AppConfig.resolve(settings)
        // If VT_ANALYSIS_POLL_INTERVAL is set in env, it overrides
        if (System.getenv("VT_ANALYSIS_POLL_INTERVAL") != null) {
            assertTrue(overridden.contains("analysisPollInterval"))
        }
    }
}
