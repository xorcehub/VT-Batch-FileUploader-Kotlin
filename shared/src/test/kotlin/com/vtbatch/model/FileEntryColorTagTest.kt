package com.vtbatch.model

import kotlin.test.Test
import kotlin.test.assertEquals

class FileEntryColorTagTest {

    private fun entry(
        status: FileStatus = FileStatus.ANALYSIS_COMPLETE,
        detectionRatio: String? = null
    ) = FileEntry(
        path = "/test/file.exe",
        fileName = "file.exe",
        fileSizeBytes = 1024L,
        fileSizeFormatted = "1.0 KB",
        status = status,
        detectionRatio = detectionRatio
    )

    @Test
    fun `CLEAN tag for zero detections`() {
        val e = entry(detectionRatio = "0/72")
        assertEquals(ColorTag.CLEAN, e.colorTag)
    }

    @Test
    fun `SUSPICIOUS tag for 1 detection`() {
        val e = entry(detectionRatio = "1/72")
        assertEquals(ColorTag.SUSPICIOUS, e.colorTag)
    }

    @Test
    fun `SUSPICIOUS tag for 3 detections`() {
        val e = entry(detectionRatio = "3/72")
        assertEquals(ColorTag.SUSPICIOUS, e.colorTag)
    }

    @Test
    fun `MALICIOUS tag for 4 detections`() {
        val e = entry(detectionRatio = "4/72")
        assertEquals(ColorTag.MALICIOUS, e.colorTag)
    }

    @Test
    fun `MALICIOUS tag for many detections`() {
        val e = entry(detectionRatio = "55/72")
        assertEquals(ColorTag.MALICIOUS, e.colorTag)
    }

    @Test
    fun `NEUTRAL tag when detectionRatio is null for ANALYSIS_COMPLETE`() {
        val e = entry(status = FileStatus.ANALYSIS_COMPLETE, detectionRatio = null)
        assertEquals(ColorTag.NEUTRAL, e.colorTag)
    }

    @Test
    fun `CLEAN tag for HASHED_FOUND with zero detections`() {
        val e = entry(status = FileStatus.HASHED_FOUND, detectionRatio = "0/72")
        assertEquals(ColorTag.CLEAN, e.colorTag)
    }

    @Test
    fun `MALICIOUS tag for HASHED_FOUND with detections`() {
        val e = entry(status = FileStatus.HASHED_FOUND, detectionRatio = "5/72")
        assertEquals(ColorTag.MALICIOUS, e.colorTag)
    }

    @Test
    fun `ERROR tag for ERROR status`() {
        val e = entry(status = FileStatus.ERROR)
        assertEquals(ColorTag.ERROR, e.colorTag)
    }

    @Test
    fun `NEUTRAL tag for PENDING status`() {
        val e = entry(status = FileStatus.PENDING)
        assertEquals(ColorTag.NEUTRAL, e.colorTag)
    }

    @Test
    fun `NEUTRAL tag for UPLOADING status`() {
        val e = entry(status = FileStatus.UPLOADING)
        assertEquals(ColorTag.NEUTRAL, e.colorTag)
    }

    @Test
    fun `NEUTRAL tag for malformed detection ratio`() {
        val e = entry(detectionRatio = "invalid")
        assertEquals(ColorTag.NEUTRAL, e.colorTag)
    }
}
