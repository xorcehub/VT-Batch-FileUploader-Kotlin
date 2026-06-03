package com.vtbatch.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SecureApiKeyTest {

    @Test
    fun `get returns the original value`() {
        val key = SecureApiKey("my-secret-api-key-12345")
        assertEquals("my-secret-api-key-12345", key.get())
    }

    @Test
    fun `constructor rejects empty string`() {
        assertFailsWith<IllegalArgumentException> {
            SecureApiKey("")
        }
    }

    @Test
    fun `clear zeros the key and isCleared returns true`() {
        val key = SecureApiKey("test-key-value")
        key.clear()
        assertTrue(key.isCleared)
    }

    @Test
    fun `get throws after clear`() {
        val key = SecureApiKey("test-key-value")
        key.clear()
        assertFailsWith<IllegalStateException> {
            key.get()
        }
    }

    @Test
    fun `displayMasked shows prefix and suffix with masking`() {
        val key = SecureApiKey("abcdefghij1234567890xyz")
        val masked = key.displayMasked()
        assertTrue(masked.startsWith("abc"), "Should start with prefix, got: $masked")
        assertTrue(masked.endsWith("xyz"), "Should end with suffix, got: $masked")
        assertTrue(masked.contains("*"), "Should contain asterisks, got: $masked")
    }

    @Test
    fun `displayMasked returns all stars for short key`() {
        val key = SecureApiKey("abc")
        val masked = key.displayMasked()
        assertEquals("***", masked)
    }

    @Test
    fun `displayMasked returns cleared indicator after clear`() {
        val key = SecureApiKey("test-key-value")
        key.clear()
        assertEquals("<cleared>", key.displayMasked())
    }

    @Test
    fun `toString hides the key value`() {
        val key = SecureApiKey("super-secret-key")
        val str = key.toString()
        assertTrue(str.contains("SecureApiKey("), "Should have class name prefix")
        assertFalse(str.contains("super-secret-key"), "toString should NOT contain the raw key")
    }

    @Test
    fun `equals returns true for same key values`() {
        val key1 = SecureApiKey("same-key")
        val key2 = SecureApiKey("same-key")
        assertEquals(key1, key2)
    }

    @Test
    fun `equals returns false for different key values`() {
        val key1 = SecureApiKey("key-one")
        val key2 = SecureApiKey("key-two")
        assertNotEquals(key1, key2)
    }

    @Test
    fun `equals returns false if either key is cleared`() {
        val key1 = SecureApiKey("same-key")
        val key2 = SecureApiKey("same-key")
        key1.clear()
        assertNotEquals(key1, key2)
    }

    @Test
    fun `hashCode returns 0 after clear`() {
        val key = SecureApiKey("test-key")
        key.clear()
        assertEquals(0, key.hashCode())
    }

    @Test
    fun `hashCode returns non-zero before clear`() {
        val key = SecureApiKey("test-key")
        assertTrue(key.hashCode() != 0)
    }

    @Test
    fun `multiple clears are safe`() {
        val key = SecureApiKey("test-key")
        key.clear()
        key.clear() // Should not throw
        assertTrue(key.isCleared)
    }

    @Test
    fun `custom prefix and suffix length`() {
        val key = SecureApiKey("abcdefghijklmnop", prefixLen = 5, suffixLen = 4)
        val masked = key.displayMasked()
        assertTrue(masked.startsWith("abcde"), "Should start with 5-char prefix, got: $masked")
        assertTrue(masked.endsWith("mnop"), "Should end with 4-char suffix, got: $masked")
    }
}
