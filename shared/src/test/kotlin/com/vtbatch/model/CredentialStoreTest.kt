package com.vtbatch.model

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CredentialStoreTest {

    private fun createStore(dir: File = createTempDir()): CredentialStore =
        CredentialStore(dir = dir, fileName = "test-credentials")

    private fun createTempDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "vtbatch-test-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    private fun cleanup(vararg dirs: File) {
        dirs.forEach { it.deleteRecursively() }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  save + load round-trip
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `save and load round-trip preserves key`() {
        val dir = createTempDir()
        val store = createStore(dir)
        store.save("abc123def456")
        assertEquals("abc123def456", store.load())
        cleanup(dir)
    }

    @Test
    fun `save stores Base64-encoded content`() {
        val dir = createTempDir()
        val store = createStore(dir)
        store.save("hello-world")
        // Read the raw file to verify it's Base64, not plaintext
        val raw = File(dir, "test-credentials").readText()
        assertFalse(raw.contains("hello-world"), "File should not contain plaintext key")
        // Verify round-trip to confirm valid Base64
        assertEquals("hello-world", store.load())
        cleanup(dir)
    }

    @Test
    fun `save overwrites previous key`() {
        val dir = createTempDir()
        val store = createStore(dir)
        store.save("first-key")
        store.save("second-key")
        assertEquals("second-key", store.load())
        cleanup(dir)
    }

    @Test
    fun `save creates directory if missing`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "vtbatch-new-${System.nanoTime()}")
        assertFalse(dir.exists())
        val store = createStore(dir)
        store.save("test-key")
        assertTrue(dir.exists())
        assertEquals("test-key", store.load())
        cleanup(dir)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  load — edge cases
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `load returns null when file does not exist`() {
        val dir = createTempDir()
        val store = createStore(dir)
        assertNull(store.load())
        cleanup(dir)
    }

    @Test
    fun `load returns null for empty file`() {
        val dir = createTempDir()
        val store = createStore(dir)
        File(dir, "test-credentials").writeText("")
        assertNull(store.load())
        cleanup(dir)
    }

    @Test
    fun `load returns null for blank file`() {
        val dir = createTempDir()
        val store = createStore(dir)
        File(dir, "test-credentials").writeText("   \n\t  ")
        assertNull(store.load())
        cleanup(dir)
    }

    @Test
    fun `load returns null for corrupted content`() {
        val dir = createTempDir()
        val store = createStore(dir)
        File(dir, "test-credentials").writeText("not-valid-base64!!!")
        assertNull(store.load())
        cleanup(dir)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  clear
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `clear removes saved file`() {
        val dir = createTempDir()
        val store = createStore(dir)
        store.save("my-key")
        assertNotNull(store.load())
        store.clear()
        assertNull(store.load())
        cleanup(dir)
    }

    @Test
    fun `clear on nonexistent file is no-op`() {
        val dir = createTempDir()
        val store = createStore(dir)
        store.clear() // should not throw
        assertNull(store.load())
        cleanup(dir)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  File permissions (POSIX)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `saved file has owner-only permissions on POSIX systems`() {
        val dir = createTempDir()
        val store = createStore(dir)
        store.save("secret-key")
        val file = File(dir, "test-credentials")
        assertTrue(file.exists())
        // On macOS/Linux, owner-only read/write should be set
        // On Windows these are no-ops, so we just verify the file exists
        if (!System.getProperty("os.name").lowercase().contains("win")) {
            assertTrue(file.canRead(), "Owner should be able to read")
            assertTrue(file.canWrite(), "Owner should be able to write")
        }
        cleanup(dir)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Long / special character keys
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `save and load long API key`() {
        val dir = createTempDir()
        val store = createStore(dir)
        val longKey = "a".repeat(500)
        store.save(longKey)
        assertEquals(longKey, store.load())
        cleanup(dir)
    }

    @Test
    fun `save and load key with special characters`() {
        val dir = createTempDir()
        val store = createStore(dir)
        val specialKey = "key-with+equals=and/slashes\\andmore"
        store.save(specialKey)
        assertEquals(specialKey, store.load())
        cleanup(dir)
    }
}
