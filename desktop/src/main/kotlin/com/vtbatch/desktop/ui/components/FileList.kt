package com.vtbatch.desktop.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vtbatch.desktop.ui.navigation.FindNavigator
import com.vtbatch.model.FileEntry

// FileList — scrollable list of file entries, color-coded by status.
// LazyColumn = Compose's efficient scrolling list (like RecyclerView on Android).
// It only renders visible items, so 10,000 files won't kill performance.

@Composable
fun FileList(
    files: List<FileEntry>,
    expandedFilePath: String? = null,
    findMatches: FindNavigator.FindMatches = FindNavigator.FindMatches(),
    onToggleExpansion: (String) -> Unit = {},
    onRecheck: (String) -> Unit = {},
    onRemove: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Scroll to the current find match whenever it changes
    LaunchedEffect(findMatches.currentMatch) {
        val targetIndex = findMatches.currentMatch
        if (targetIndex >= 0 && targetIndex < files.size) {
            listState.animateScrollToItem(targetIndex)
        }
    }

    FileListContent(
        files = files,
        expandedFilePath = expandedFilePath,
        findMatches = findMatches,
        listState = listState,
        onToggleExpansion = onToggleExpansion,
        onRecheck = onRecheck,
        onRemove = onRemove,
        modifier = modifier
    )
}

@Composable
private fun FileListContent(
    files: List<FileEntry>,
    expandedFilePath: String?,
    findMatches: FindNavigator.FindMatches,
    listState: LazyListState,
    onToggleExpansion: (String) -> Unit,
    onRecheck: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        itemsIndexed(files, key = { _, file -> file.path }) { fileIndex, file ->
            val isCurrentFindMatch = findMatches.hasMatches && fileIndex == findMatches.currentMatch

            FileListEntry(
                file = file,
                isExpanded = expandedFilePath == file.path,
                isFindHighlighted = isCurrentFindMatch,
                onToggleExpansion = onToggleExpansion,
                onRecheck = onRecheck,
                onRemove = onRemove
            )
        }
    }
}
