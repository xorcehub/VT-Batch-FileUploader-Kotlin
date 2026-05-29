package com.vtbatch.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vtbatch.desktop.theme.VTBatchTheme
import com.vtbatch.desktop.ui.components.*
import com.vtbatch.model.*

// @Composable = a function that describes UI. Compose automatically
// re-executes it whenever the data it reads changes (reactive).
// This is the root layout — it composes all the UI sections top-to-bottom.

// For Phase 1, we use hardcoded preview data. In Phase 3, the MVI store
// will provide real data that updates reactively.

// Preview data — shows all 4 color states in the file list
private val previewFiles = listOf(
    FileEntry(
        path = "C:\\Users\\jurij\\Downloads\\suspicious.exe",
        fileName = "suspicious.exe",
        fileSizeBytes = 2_516_582,
        fileSizeFormatted = "2.4 MB",
        md5Hash = "abc123def456abc123def456abc123de",
        status = FileStatus.HASHED_FOUND,
        detectionRatio = "2/72",
        analysisUrl = "https://www.virustotal.com/gui/file/abc123",
        lastAnalysisDate = "2024-01-15"
    ),
    FileEntry(
        path = "C:\\Users\\jurij\\Downloads\\malware.dll",
        fileName = "malware.dll",
        fileSizeBytes = 876_544,
        fileSizeFormatted = "856 KB",
        md5Hash = "deadbeefdeadbeefdeadbeefdeadbeef",
        status = FileStatus.ANALYSIS_COMPLETE,
        detectionRatio = "45/72",
        analysisUrl = "https://www.virustotal.com/gui/file/deadbeef",
        lastAnalysisDate = "2024-01-14"
    ),
    FileEntry(
        path = "C:\\Users\\jurij\\Downloads\\clean_document.pdf",
        fileName = "clean_document.pdf",
        fileSizeBytes = 15_728_640,
        fileSizeFormatted = "15.0 MB",
        md5Hash = "1234567890abcdef1234567890abcdef",
        status = FileStatus.HASHED_FOUND,
        detectionRatio = "0/72",
        analysisUrl = "https://www.virustotal.com/gui/file/12345678",
        lastAnalysisDate = "2024-01-13"
    ),
    FileEntry(
        path = "C:\\Users\\jurij\\Downloads\\pending_update.msi",
        fileName = "pending_update.msi",
        fileSizeBytes = 52_428_800,
        fileSizeFormatted = "50.0 MB",
        status = FileStatus.PENDING
    ),
    FileEntry(
        path = "C:\\Users\\jurij\\Downloads\\uploading.zip",
        fileName = "uploading.zip",
        fileSizeBytes = 104_857_600,
        fileSizeFormatted = "100.0 MB",
        md5Hash = "feedfacefeedfacefeedfacefeedface",
        status = FileStatus.UPLOADING
    ),
    FileEntry(
        path = "C:\\Users\\jurij\\Downloads\\error_corrupt.bin",
        fileName = "error_corrupt.bin",
        fileSizeBytes = 512,
        fileSizeFormatted = "512 B",
        status = FileStatus.ERROR,
        errorMessage = "Failed to compute hash: file is corrupted"
    ),
)

@Composable
fun App() {
    VTBatchTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Top section: Drop zone + Buttons
                DropZone(
                    modifier = Modifier.fillMaxWidth(),
                    onDrop = { /* Phase 3: dispatch DropFiles intent */ },
                    onSubmitCommand = { /* Phase 3: dispatch SubmitCommand intent */ }
                )

                ButtonBar(
                    modifier = Modifier.fillMaxWidth(),
                    onStart = { /* Phase 3 */ },
                    onPause = { /* Phase 3 */ },
                    onOpenHashed = { /* Phase 3 */ },
                    onUpload = { /* Phase 3 */ },
                    onClear = { /* Phase 3 */ }
                )

                // Middle: File list (takes remaining vertical space)
                Box(modifier = Modifier.weight(1f, fill = true)) {
                    FileList(
                        files = previewFiles,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Bottom: Processing info + progress + log + status bar
                ProcessingInfo(
                    currentFile = "example.exe",
                    currentStatus = "Hashing",
                    modifier = Modifier.fillMaxWidth()
                )

                ProgressSection(
                    hashingProgress = ProgressInfo(percent = 0.67f, speedFormatted = "45 MB/s", fileCount = 3, elapsedFormatted = "2.3s"),
                    uploadProgress = ProgressInfo(percent = 0.15f, speedFormatted = "12 MB/s", fileCount = 2, elapsedFormatted = "5.1s"),
                    totalProgress = ProgressInfo(percent = 0.45f, speedFormatted = "", fileCount = 6, elapsedFormatted = "7.4s"),
                    modifier = Modifier.fillMaxWidth()
                )

                StatusReport(
                    logLines = listOf(
                        "Found 6 files, 3 cached...",
                        "Processing example.exe...",
                        "Hash computed: abc123def456...",
                        "VT lookup: file found, 2/72 detections",
                        "Uploading pending_update.msi..."
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                StatusBar(
                    hashingInfo = "2.3s, 45 MB/s (3 files)",
                    uploadInfo = "5.1s, 12 MB/s (2 files)",
                    quotaInfo = "42/500 daily",
                    recheckInfo = "4:32 (3 pending)",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
