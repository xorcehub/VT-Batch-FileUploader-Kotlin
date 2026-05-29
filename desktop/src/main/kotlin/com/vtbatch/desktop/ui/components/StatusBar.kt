package com.vtbatch.desktop.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// StatusBar — bottom bar showing hashing speed, upload speed, API quota,
// and recheck timer. Always visible at the bottom of the window.

@Composable
fun StatusBar(
    hashingInfo: String,
    uploadInfo: String,
    quotaInfo: String,
    recheckInfo: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = MaterialTheme.shapes.extraSmall
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatusChip(label = "Hashing", value = hashingInfo)
            StatusChip(label = "Upload", value = uploadInfo)
            StatusChip(label = "Quota", value = quotaInfo)
            StatusChip(label = "Recheck", value = recheckInfo)
        }
    }
}

@Composable
private fun StatusChip(label: String, value: String) {
    Row {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
