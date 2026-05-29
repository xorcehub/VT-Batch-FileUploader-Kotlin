package com.vtbatch.desktop.ui.navigation

import com.vtbatch.model.FileEntry

// FindNavigator — handles Find command + PageUp/PageDown keyboard navigation.
// Pure functions: search() returns a new FindMatches, navigate() cycles through them.
// The reducer stores FindMatches in AppState; the UI reads it for scroll position.

object FindNavigator {
    data class FindMatches(
        val query: String = "",
        val matchIndices: List<Int> = emptyList(),
        val currentIndex: Int = -1
    ) {
        val hasMatches: Boolean get() = matchIndices.isNotEmpty()
        val currentMatch: Int get() = if (hasMatches && currentIndex in matchIndices.indices)
            matchIndices[currentIndex] else -1
        val matchCountText: String get() = if (hasMatches) "${currentIndex + 1}/${matchIndices.size}" else "No matches"
    }

    /**
     * Search files by name (case-insensitive substring match).
     * Returns a FindMatches with all matching indices.
     */
    fun search(query: String, files: List<FileEntry>): FindMatches {
        if (query.isBlank() || files.isEmpty()) return FindMatches()

        val lowerQuery = query.lowercase()
        val indices = files.mapIndexedNotNull { index, entry ->
            if (entry.fileName.lowercase().contains(lowerQuery)) index else null
        }

        return FindMatches(
            query = query,
            matchIndices = indices,
            currentIndex = if (indices.isNotEmpty()) 0 else -1
        )
    }

    /**
     * Navigate to the next (+1) or previous (-1) match.
     * Wraps around (modular arithmetic).
     */
    fun navigate(matches: FindMatches, direction: Int): FindMatches {
        if (!matches.hasMatches) return matches
        val size = matches.matchIndices.size
        val newIndex = ((matches.currentIndex + direction) % size).let {
            if (it < 0) it + size else it
        }
        return matches.copy(currentIndex = newIndex)
    }
}
