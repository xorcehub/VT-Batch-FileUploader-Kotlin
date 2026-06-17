package com.vtbatch.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileExporterTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun sampleFile(
        status: FileStatus = FileStatus.HASHED_FOUND,
        detections: String = "2/72",
        hits: List<EngineHit>? = listOf(
            EngineHit("Kaspersky", "Trojan.Win32.Generic"),
            EngineHit("Microsoft", "Trojan:Win32/Wacatac")
        )
    ): FileEntry = FileEntry(
        path = "C:\\samples\\evil.exe",
        fileName = "evil.exe",
        fileSizeBytes = 2048,
        fileSizeFormatted = "2.0 KB",
        md5Hash = "d41d8cd98f00b204e9800998ecf8427e",
        sha256Hash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        status = status,
        analysisUrl = "https://www.virustotal.com/gui/file/e3b0c442",
        detectionRatio = detections,
        lastAnalysisDate = "2026-06-01 12:00",
        lastAnalysisStats = "2 malicious, 70 harmless",
        popularThreatLabel = "trojan",
        typeDescription = "Win64 EXE",
        meaningfulName = "evil.exe",
        tags = listOf("executable", "windows"),
        timesSubmitted = 7,
        reputation = -3,
        firstSubmissionDate = "2025-01-01 00:00",
        lastSubmissionDate = "2026-06-01 12:00",
        totalVotes = 1 to 4,
        errorMessage = null,
        engineHits = hits
    )

    @Test
    fun `buildDocument sets count and timestamp`() {
        val doc = FileExporter.buildDocument(
            listOf(sampleFile(), sampleFile().copy(path = "C:\\other.bin", fileName = "other.bin")),
            exportedAt = "2026-06-16T00:00:00"
        )
        assertEquals("2026-06-16T00:00:00", doc.exportedAt)
        assertEquals(2, doc.fileCount)
    }

    @Test
    fun `toJson produces valid JSON with avDetections`() {
        val text = FileExporter.toJson(listOf(sampleFile()), exportedAt = "2026-06-16T00:00:00")

        // Round-trip parses cleanly
        val parsed = json.parseToJsonElement(text).toString()
        assertTrue(parsed.contains("\"avDetections\""))
        assertTrue(parsed.contains("\"Kaspersky\""))
        assertTrue(parsed.contains("\"Trojan.Win32.Generic\""))
        assertTrue(parsed.contains("\"detectionRatio\""))
        assertTrue(parsed.contains("\"2/72\""))
        assertTrue(parsed.contains("\"popularThreatLabel\""))
        assertTrue(parsed.contains("\"totalVotesMalicious\""))
    }

    @Test
    fun `toExportFile omits avDetections when file is clean`() {
        val clean = sampleFile(detections = "0/72", hits = null)
        val export = FileExporter.toExportFile(clean)
        assertNull(export.avDetections)
        assertEquals("0/72", export.detectionRatio)
    }

    @Test
    fun `exported file preserves all identity fields`() {
        val export = FileExporter.toExportFile(sampleFile())
        assertEquals("evil.exe", export.fileName)
        assertEquals(2048, export.sizeBytes)
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", export.md5)
        assertNotNull(export.sha256)
        assertEquals("Win64 EXE", export.typeDescription)
        assertEquals(1, export.totalVotesHarmless)
        assertEquals(4, export.totalVotesMalicious)
        assertEquals(listOf("executable", "windows"), export.tags)
    }

    @Test
    fun `empty file list produces empty document`() {
        val doc = FileExporter.buildDocument(emptyList(), exportedAt = "now")
        assertEquals(0, doc.fileCount)
        assertTrue(doc.files.isEmpty())
    }
}
