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

                // VT link button (if analysis is available)
                if (file.analysisUrl != null) {
                    TextButton(onClick = {
                        try {
                            if (Desktop.isDesktopSupported()) {
                                Desktop.getDesktop().browse(URI(file.analysisUrl))
                            }
                        } catch (_: Exception) { /* ignore browser launch failures */ }
                    }) {
                        Text("Open VT", color = MaterialTheme.colorScheme.primary)
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
    val bodySmall = MaterialTheme.typography.bodySmall

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
