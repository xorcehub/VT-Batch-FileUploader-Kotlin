package com.vtbatch.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.vtbatch.desktop.theme.AppColors
import com.vtbatch.desktop.mvi.extOfName
import com.vtbatch.model.ColorTag
import com.vtbatch.model.FileEntry

// FilterBar — two rows of toggleable chips for filtering the file list.
// All filters start selected (everything visible). User clicks to deselect
// (hide) items. "All / None" button toggles the whole row.
//
// Counts shown on each chip reflect the FULL (unfiltered) file list so the
// user always sees the true population. The chip's selected state reflects
// whether it's currently visible.

@Composable
fun FilterBar(
    files: List<FileEntry>,
    deselectedExtensions: Set<String>,
    deselectedColorTags: Set<ColorTag>,
    onToggleExtension: (String) -> Unit,
    onToggleColor: (ColorTag) -> Unit,
    onSelectAllExtensions: () -> Unit,
    onDeselectAllExtensions: () -> Unit,
    onSelectAllColors: () -> Unit,
    onDeselectAllColors: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (files.isEmpty()) return

    // Each section's counts reflect the OTHER filter's deselections,
    // so toggling .py updates the status counts and vice versa.
    val colorFiltered = files.filter { it.colorTag !in deselectedColorTags }
    val extFiltered = files.filter { extOfName(it.fileName) !in deselectedExtensions }

    val extensionCounts = colorFiltered
        .groupBy { extOfName(it.fileName) }
        .mapValues { it.value.size }
        .toSortedMap()
    val availableExtensions = files
        .map { extOfName(it.fileName) }
        .toSortedSet()

    val colorCounts = extFiltered
        .groupBy { it.colorTag }
        .mapValues { it.value.size }
    val availableColors = files
        .map { it.colorTag }
        .toSet()

    val allExtsSelected = deselectedExtensions.isEmpty()
    val allColorsSelected = deselectedColorTags.isEmpty()

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Extensions row
            FilterSection(
                label = "Extensions",
                allSelected = allExtsSelected,
                onSelectAll = onSelectAllExtensions,
                onDeselectAll = onDeselectAllExtensions
            ) {
                availableExtensions.forEach { ext ->
                    val count = extensionCounts[ext] ?: 0
                    FilterChip(
                        selected = ext !in deselectedExtensions,
                        onClick = { onToggleExtension(ext) },
                        label = { Text("$ext ($count)") }
                    )
                }
            }

            // Color tags row
            FilterSection(
                label = "Status",
                allSelected = allColorsSelected,
                onSelectAll = onSelectAllColors,
                onDeselectAll = onDeselectAllColors
            ) {
                availableColors.sortedBy { it.ordinal }.forEach { tag ->
                    val count = colorCounts[tag] ?: 0
                    val info = colorInfo(tag)
                    FilterChip(
                        selected = tag !in deselectedColorTags,
                        onClick = { onToggleColor(tag) },
                        label = { Text("${info.label} ($count)") },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(info.color)
                            )
                        }
                    )
                }
            }
        }
    }
}

// ── Helpers ──────────────────────────────────────────────────────────

@Composable
private fun FilterSection(
    label: String,
    allSelected: Boolean,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    chips: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp)
        )
        TextButton(
            onClick = { if (allSelected) onDeselectAll() else onSelectAll() },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
        ) {
            Text(
                text = if (allSelected) "None" else "All",
                style = MaterialTheme.typography.labelSmall
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f)
        ) {
            chips()
        }
    }
}

private data class ColorInfo(val label: String, val color: androidx.compose.ui.graphics.Color)

private fun colorInfo(tag: ColorTag): ColorInfo = when (tag) {
    ColorTag.CLEAN -> ColorInfo("Clean", AppColors.CleanGreen)
    ColorTag.SUSPICIOUS -> ColorInfo("Suspicious", AppColors.SuspiciousOrange)
    ColorTag.MALICIOUS -> ColorInfo("Malicious", AppColors.MaliciousRed)
    ColorTag.NEUTRAL -> ColorInfo("Neutral", AppColors.NeutralGray)
    ColorTag.ERROR -> ColorInfo("Error", AppColors.ErrorRed)
}
