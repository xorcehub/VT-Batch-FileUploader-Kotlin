package com.vtbatch.desktop.ui.components

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

@Composable
fun FileListEntry(
    file: FileEntry,
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
            .clip(RoundedCornerShape(6.dp)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
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
                    if (file.md5Hash != null) {
                        val hash: String = file.md5Hash!!
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
    }
}
