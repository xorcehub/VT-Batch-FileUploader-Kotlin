package com.vtbatch.desktop.ui.navigation

import com.vtbatch.model.FileEntry
import com.vtbatch.model.FileStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FindNavigatorTest {

    private fun entry(name: String) = FileEntry(
        path = "/test/$name",
        fileName = name,
        fileSizeBytes = 1024L,
        fileSizeFormatted = "1.0 KB"
    )

    // ═══════════════════════════════════════════════════════════════════
    //  search — basic
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `search finds matching files`() {
        val files = listOf(entry("malware.exe"), entry("clean.doc"), entry("trojan.exe"))
        val result = FindNavigator.search("exe", files)
        assertEquals(2, result.matchIndices.size)
        assertEquals(0, result.matchIndices[0]) // malware.exe
        assertEquals(2, result.matchIndices[1]) // trojan.exe
    }

    @Test
    fun `search is case-insensitive`() {
        val files = listOf(entry("README.MD"), entry("notes.txt"))
        val result = FindNavigator.search("readme", files)
        assertEquals(1, result.matchIndices.size)
        assertEquals(0, result.matchIndices[0])
    }

    @Test
    fun `search with blank query returns empty matches`() {
        val files = listOf(entry("test.exe"))
        val result = FindNavigator.search("", files)
        assertFalse(result.hasMatches)
        assertEquals(-1, result.currentIndex)
    }

    @Test
    fun `search with empty file list returns empty matches`() {
        val result = FindNavigator.search("test", emptyList())
        assertFalse(result.hasMatches)
    }

    @Test
    fun `search with no matches returns empty`() {
        val files = listOf(entry("clean.doc"), entry("readme.txt"))
        val result = FindNavigator.search("exe", files)
        assertFalse(result.hasMatches)
        assertEquals(-1, result.currentIndex)
    }

    @Test
    fun `search stores query in result`() {
        val result = FindNavigator.search("exe", listOf(entry("test.exe")))
        assertEquals("exe", result.query)
    }

    @Test
    fun `search sets current index to 0 when matches found`() {
        val result = FindNavigator.search("exe", listOf(entry("test.exe")))
        assertEquals(0, result.currentIndex)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  navigate — next / previous / wrap-around
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `navigate next advances to second match`() {
        val files = listOf(entry("a.exe"), entry("b.exe"), entry("c.exe"))
        val matches = FindNavigator.search("exe", files)
        val next = FindNavigator.navigate(matches, +1)
        assertEquals(1, next.currentIndex)
    }

    @Test
    fun `navigate wraps around forward`() {
        val files = listOf(entry("a.exe"), entry("b.exe"))
        val matches = FindNavigator.search("exe", files)
        // At index 0, go to 1
        val step1 = FindNavigator.navigate(matches, +1)
        assertEquals(1, step1.currentIndex)
        // At index 1, wrap to 0
        val step2 = FindNavigator.navigate(step1, +1)
        assertEquals(0, step2.currentIndex)
    }

    @Test
    fun `navigate previous wraps backward`() {
        val files = listOf(entry("a.exe"), entry("b.exe"), entry("c.exe"))
        val matches = FindNavigator.search("exe", files)
        // At index 0, previous should wrap to last
        val prev = FindNavigator.navigate(matches, -1)
        assertEquals(2, prev.currentIndex)
    }

    @Test
    fun `navigate on empty matches returns same`() {
        val matches = FindNavigator.FindMatches()
        val result = FindNavigator.navigate(matches, +1)
        assertEquals(matches, result)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  FindMatches computed properties
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `hasMatches is true when indices present`() {
        val matches = FindNavigator.FindMatches(matchIndices = listOf(0, 3), currentIndex = 0)
        assertTrue(matches.hasMatches)
    }

    @Test
    fun `hasMatches is false when empty`() {
        val matches = FindNavigator.FindMatches()
        assertFalse(matches.hasMatches)
    }

    @Test
    fun `currentMatch returns file index`() {
        val matches = FindNavigator.FindMatches(matchIndices = listOf(2, 5, 8), currentIndex = 1)
        assertEquals(5, matches.currentMatch)
    }

    @Test
    fun `currentMatch returns minus 1 when no matches`() {
        val matches = FindNavigator.FindMatches()
        assertEquals(-1, matches.currentMatch)
    }

    @Test
    fun `matchCountText shows position and total`() {
        val matches = FindNavigator.FindMatches(matchIndices = listOf(0, 1), currentIndex = 0)
        assertEquals("1/2", matches.matchCountText)
    }

    @Test
    fun `matchCountText shows no matches when empty`() {
        val matches = FindNavigator.FindMatches()
        assertEquals("No matches", matches.matchCountText)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Edge cases
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `search matches substring in middle of filename`() {
        val files = listOf(entry("my-trojan-file.exe"))
        val result = FindNavigator.search("trojan", files)
        assertTrue(result.hasMatches)
        assertEquals(0, result.matchIndices[0])
    }

    @Test
    fun `navigate preserves query and match indices`() {
        val files = listOf(entry("a.exe"), entry("b.exe"))
        val matches = FindNavigator.search("exe", files)
        val next = FindNavigator.navigate(matches, +1)
        assertEquals(matches.query, next.query)
        assertEquals(matches.matchIndices, next.matchIndices)
    }
}
