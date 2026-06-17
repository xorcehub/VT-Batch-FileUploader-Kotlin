package com.vtbatch.desktop.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vtbatch.model.ProgressInfo

// ProgressSection — 3 progress bars (hashing, uploading, total).
// LinearProgressIndicator is Material3's built-in progress bar.

@Composable
fun ProgressSection(
    hashingProgress: ProgressInfo,
    uploadProgress: ProgressInfo,
    totalProgress: ProgressInfo,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ProgressRow(label = "Hashing", progress = hashingProgress, color = MaterialTheme.colorScheme.primary)
        ProgressRow(label = "Uploading", progress = uploadProgress, color = MaterialTheme.colorScheme.secondary)
        ProgressRow(label = "Total", progress = totalProgress, color = MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
private fun ProgressRow(
    label: String,
    progress: ProgressInfo,
    color: androidx.compose.ui.graphics.Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(80.dp)
        )
        LinearProgressIndicator(
            progress = { progress.percent.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.weight(1f).height(8.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "${(progress.percent * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(40.dp)
        )
        if (progress.speedFormatted.isNotBlank()) {
            Text(
                text = "(${progress.speedFormatted})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
