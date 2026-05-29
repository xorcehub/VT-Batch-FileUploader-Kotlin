package com.vtbatch.desktop.ui.navigation

// FindNavigator — handles Find command + PageUp/PageDown keyboard navigation.
// Stub for Phase 1; will be wired to MVI in Phase 3.

// In Kotlin, `object` = a singleton (only one instance ever exists).
// Like a Python class where all methods are @classmethod and there's no __init__.
object FindNavigator {
    data class FindMatches(
        val query: String = "",
        val matchIndices: List<Int> = emptyList(),
        val currentIndex: Int = -1
    ) {
        val hasMatches: Boolean get() = matchIndices.isNotEmpty()
        val currentMatch: Int get() = if (hasMatches) matchIndices[currentIndex.coerceIn(matchIndices.indices)] else -1
        val matchCountText: String get() = if (hasMatches) "${currentIndex + 1}/${matchIndices.size}" else "No matches"
    }
}
