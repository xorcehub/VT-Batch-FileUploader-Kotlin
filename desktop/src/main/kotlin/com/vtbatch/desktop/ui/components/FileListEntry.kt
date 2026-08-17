package com.vtbatch.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vtbatch.desktop.theme.AppColors
import com.vtbatch.model.*
import java.awt.Desktop
import java.net.URI

// FileListEntry — one row in the file list. Shows filename, size, hash,
// status, detection ratio, and VT URL. Color-coded by detection result.
// Click to expand/collapse a detail panel with rich VT analysis data.

@Composable
fun FileListEntry(
    file: FileEntry,
    isExpanded: Boolean = false,
    onToggleExpansion: (String) -> Unit = {},
    onRecheck: (String) -> Unit = {},
    onRemove: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val accentColor = when (file.colorTag) {
        ColorTag.CLEAN -> AppColors.CleanGreen
        ColorTag.SUSPICIOUS -> AppColors.SuspiciousOrange
        ColorTag.MALICIOUS -> AppColors.MaliciousRed
        ColorTag.ERROR -> AppColors.ErrorRed
        ColorTag.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable { onToggleExpansion(file.path) },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column {
            // ── Main row (always visible) ─────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Color indicator bar on the left
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(48.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(accentColor)
                )

                Spacer(modifier = Modifier.width(12.dp))

                // File info
                Column(modifier = Modifier.weight(1f)) {
                    // Line 1: Filename + status badge
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = file.fileName,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = accentColor.copy(alpha = 0.15f),
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Text(
                                text = file.status.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = accentColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    // Line 2: Size | Hash
                    Row {
                        Text(
                            text = "Size: ${file.fileSizeFormatted}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        file.md5Hash?.let { hash ->
                            Text(
                                text = "  |  Hash: ${hash.take(16)}...",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Line 3: Detections (if available)
                    if (file.detectionRatio != null) {
                        Text(
                            text = "Detections: ${file.detectionRatio}",
                            style = MaterialTheme.typography.bodySmall,
                            color = accentColor
                        )
                    }

                    // Error message
                    if (file.errorMessage != null) {
                        Text(
                            text = "Error: ${file.errorMessage}",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.ErrorRed
                        )
                    }
                }

                // Action buttons — all share the same pill style for a uniform look
                val btnColors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
                val btnShape = RoundedCornerShape(12.dp)
                val btnPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)

                val hasHash = file.md5Hash != null || file.sha256Hash != null
                val isRechecking = file.status == FileStatus.QUEUED_FOR_RECHECK ||
                    file.status == FileStatus.RECHECKING

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Recheck: filled button normally, swapped for a spinner while
                    // queued/rechecking so it can't be double-fired (VT's reanalyse
                    // endpoint 409s on repeats).
                    if (hasHash && !isRechecking) {
                        Button(
                            onClick = { onRecheck(file.path) },
                            shape = btnShape,
                            colors = btnColors,
                            contentPadding = btnPadding
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Recheck",
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Recheck", style = MaterialTheme.typography.labelMedium)
                        }
                    } else if (hasHash && isRechecking) {
                        // Reserve the Recheck button's footprint and center the spinner in it,
                        // so Open VT keeps its position instead of collapsing onto the spinner.
                        Box(
                            modifier = Modifier.width(96.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // VT link button (if analysis is available)
                    if (file.analysisUrl != null) {
                        Button(
                            onClick = {
                                try {
                                    if (Desktop.isDesktopSupported()) {
                                        Desktop.getDesktop().browse(URI(file.analysisUrl))
                                    }
                                } catch (_: Exception) { /* ignore browser launch failures */ }
                            },
                            shape = btnShape,
                            colors = btnColors,
                            contentPadding = btnPadding
                        ) {
                            Text("Open VT", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    // Separator + remove button — grouped so separator is
                    // centered in the gap, not governed by spacedBy.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        // horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(16.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )
                        Button(
                            onClick = { onRemove(file.path) },
                            modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 1.dp),
                            shape = btnShape,
                            colors = btnColors,
                            contentPadding = PaddingValues(horizontal = 5.dp, vertical = 5.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove file",
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }

            // ── Expandable detail panel ───────────────────────────────
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                FileDetailPanel(file = file, accentColor = accentColor)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
//  Detail panel — 3-column layout showing rich VT analysis data
// ─────────────────────────────────────────────────────────────────────

@Composable
private fun FileDetailPanel(file: FileEntry, accentColor: androidx.compose.ui.graphics.Color) {
    val dimText = MaterialTheme.colorScheme.onSurfaceVariant

    // One wrapping Column so every section stacks vertically. Without it,
    // Compose places un-parented siblings at the same origin and they paint
    // over each other (the overlay bug).
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // ── Column 1: Threat Analysis ─────────────────────────────
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                SectionHeader("Threat Analysis")
                file.popularThreatLabel?.let { label ->
                    val labelColor = threatLabelColor(label)
                    DetailRow("Threat Label", label, valueColor = labelColor)
                }
                file.detectionRatio?.let { DetailRow("Detections", it, valueColor = accentColor) }
                file.lastAnalysisStats?.let { DetailRow("Engine Stats", it) }
                file.lastAnalysisDate?.let { DetailRow("Last Analysis", it) }
            }

            // ── Column 2: File Identity ──────────────────────────────
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                SectionHeader("File Identity")
                file.typeDescription?.let { DetailRow("File Type", it) }
                file.meaningfulName?.let { DetailRow("Name", it) }
                file.sha256Hash?.let { hash ->
                    DetailRow("SHA256", hash.take(16) + "...", fontFamily = FontFamily.Monospace)
                }
                file.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
                    DetailRow("Tags", tags.take(5).joinToString(", ") + if (tags.size > 5) " +" else "")
                }
            }

            // ── Column 3: Community ──────────────────────────────────
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                SectionHeader("Community")
                file.reputation?.let { rep ->
                    val sign = if (rep >= 0) "+" else ""
                    val repColor = when {
                        rep > 0 -> AppColors.CleanGreen
                        rep < 0 -> AppColors.MaliciousRed
                        else -> dimText
                    }
                    DetailRow("Reputation", "$sign$rep", valueColor = repColor)
                }
                file.totalVotes?.let { votes ->
                    DetailRow("Votes", "${votes.harmless} harmless / ${votes.malicious} malicious")
                }
                file.timesSubmitted?.let { DetailRow("Submitted", "${it}x") }
                file.firstSubmissionDate?.let { DetailRow("First Seen", it) }
            }
        }

        // ── AV Detections (per-engine hits) ────────────────────────
        file.engineHits?.takeIf { it.isNotEmpty() }?.let { hits ->
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                SectionHeader("AV Detections (${hits.size})")
                hits.forEach { hit ->
                    DetailRow(hit.engine, hit.verdict, valueColor = accentColor)
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 2.dp)
    )
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    fontFamily: FontFamily? = null
) {
    Row {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            style = if (fontFamily != null)
                MaterialTheme.typography.bodySmall.copy(fontFamily = fontFamily)
            else
                MaterialTheme.typography.bodySmall,
            color = valueColor
        )
    }
}

/** Color-code threat labels: red for trojan/ransomware, orange for PUA/adware. */
private fun threatLabelColor(label: String): androidx.compose.ui.graphics.Color {
    val lower = label.lowercase()
    return when {
        lower.contains("trojan") || lower.contains("ransomware") ||
            lower.contains("backdoor") || lower.contains("rootkit") ||
            lower.contains("worm") || lower.contains("dropper") -> AppColors.MaliciousRed
        lower.contains("pua") || lower.contains("adware") ||
            lower.contains("spyware") || lower.contains("riskware") -> AppColors.SuspiciousOrange
        else -> AppColors.SuspiciousOrange
    }
}
