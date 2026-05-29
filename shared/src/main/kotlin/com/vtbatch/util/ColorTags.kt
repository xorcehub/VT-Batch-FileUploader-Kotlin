package com.vtbatch.util

// Color constants used across the UI.
// In Compose, colors are represented as Color(r, g, b, alpha) with 0f-1f range.
// These are defined here in shared so both desktop and (future) CLI can reference them.

object ColorTags {
    const val CLEAN_HEX = "#4CAF50"          // Green
    const val SUSPICIOUS_HEX = "#FF9800"     // Orange/Yellow
    const val MALICIOUS_HEX = "#F44336"      // Red
    const val NEUTRAL_HEX = "#9E9E9E"        // Gray
    const val ERROR_HEX = "#B71C1C"          // Dark red
    const val PENDING_HEX = "#78909C"        // Blue-gray
    const val UPLOADING_HEX = "#2196F3"      // Blue
    const val SUCCESS_HEX = "#66BB6A"        // Light green
}
