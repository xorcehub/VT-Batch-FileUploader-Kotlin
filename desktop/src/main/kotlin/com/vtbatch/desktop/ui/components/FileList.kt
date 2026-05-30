package com.vtbatch.desktop.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vtbatch.model.FileEntry

// FileList — scrollable list of file entries, color-coded by status.
// LazyColumn = Compose's efficient scrolling list (like RecyclerView on Android).
// It only renders visible items, so 10,000 files won't kill performance.

@Composable
fun FileList(
    files: List<FileEntry>,
    expandedFilePath: String? = null,
    onToggleExpansion: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(files, key = { it.path }) { file ->
            FileListEntry(
                file = file,
                isExpanded = expandedFilePath == file.path,
                onToggleExpansion = onToggleExpansion
            )
        }
    }
}
