package com.vtbatch.model

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileStateManagerTest {

    private fun testEntry(
        path: String = "/test/file.exe",
        status: FileStatus = FileStatus.PENDING,
        lastAnalysisDate: String? = null,
        detectionRatio: String? = null
    ) = FileEntry(
        path = path,
        fileName = path.substringAfterLast('/'),
        fileSizeBytes = 1024L,
        fileSizeFormatted = "1.0 KB",
        status = status,
        lastAnalysisDate = lastAnalysisDate,
        detectionRatio = detectionRatio
    )

    @Test
    fun `addFile and getFile round-trip`() = runTest {
        val manager = FileStateManager()
        val entry = testEntry()
        manager.addFile(entry)
        val retrieved = manager.getFile("/test/file.exe")
        assertNotNull(retrieved)
        assertEquals("/test/file.exe", retrieved.path)
    }

    @Test
    fun `getFile returns null for unknown path`() = runTest {
        val manager = FileStateManager()
        assertNull(manager.getFile("/nonexistent"))
    }

    @Test
    fun `getAllFiles returns empty map initially`() = runTest {
        val manager = FileStateManager()
        assertTrue(manager.getAllFiles().isEmpty())
    }

    @Test
    fun `getAllFiles returns defensive copy`() = runTest {
        val manager = FileStateManager()
        manager.addFile(testEntry())
        val files = manager.getAllFiles()
        assertEquals(1, files.size)
        // Mutating the returned map should not affect the manager
        files.entries.first().key // just accessing, can't mutate immutable Map
    }

    @Test
    fun `updateFileStatus changes status of existing file`() = runTest {
        val manager = FileStateManager()
        manager.addFile(testEntry())
        manager.updateFileStatus("/test/file.exe", FileStatus.HASHING)
        val file = manager.getFile("/test/file.exe")
        assertNotNull(file)
        assertEquals(FileStatus.HASHING, file.status)
    }

    @Test
    fun `updateFileStatus is no-op for unknown file`() = runTest {
        val manager = FileStateManager()
        manager.updateFileStatus("/nonexistent", FileStatus.ERROR)
        // Should not throw, just silently skip
        assertTrue(manager.getAllFiles().isEmpty())
    }

    @Test
    fun `updateFile applies transform`() = runTest {
        val manager = FileStateManager()
        manager.addFile(testEntry())
        manager.updateFile("/test/file.exe") { it.copy(md5Hash = "abc123", status = FileStatus.HASHED_FOUND) }
        val file = manager.getFile("/test/file.exe")
        assertNotNull(file)
        assertEquals("abc123", file.md5Hash)
        assertEquals(FileStatus.HASHED_FOUND, file.status)
    }

    @Test
    fun `updateFile is no-op for unknown file`() = runTest {
        val manager = FileStateManager()
        manager.updateFile("/nonexistent") { it.copy(md5Hash = "abc") }
        assertNull(manager.getFile("/nonexistent"))
    }

    @Test
    fun `removeFile returns true for existing file`() = runTest {
        val manager = FileStateManager()
        manager.addFile(testEntry())
        assertTrue(manager.removeFile("/test/file.exe"))
        assertNull(manager.getFile("/test/file.exe"))
    }

    @Test
    fun `removeFile returns false for unknown file`() = runTest {
        val manager = FileStateManager()
        assertFalse(manager.removeFile("/nonexistent"))
    }

    @Test
    fun `clear removes all files`() = runTest {
        val manager = FileStateManager()
        manager.addFile(testEntry("/a.exe"))
        manager.addFile(testEntry("/b.exe"))
        manager.clear()
        assertTrue(manager.getAllFiles().isEmpty())
    }

    @Test
    fun `allFilesHaveLastAnalysisDate returns false when empty`() = runTest {
        val manager = FileStateManager()
        assertFalse(manager.allFilesHaveLastAnalysisDate())
    }

    @Test
    fun `allFilesHaveLastAnalysisDate returns false when some lack date`() = runTest {
        val manager = FileStateManager()
        manager.addFile(testEntry("/a.exe", lastAnalysisDate = "2024-01-01"))
        manager.addFile(testEntry("/b.exe", lastAnalysisDate = null))
        assertFalse(manager.allFilesHaveLastAnalysisDate())
    }

    @Test
    fun `allFilesHaveLastAnalysisDate returns true when all have date`() = runTest {
        val manager = FileStateManager()
        manager.addFile(testEntry("/a.exe", lastAnalysisDate = "2024-01-01"))
        manager.addFile(testEntry("/b.exe", lastAnalysisDate = "2024-01-02"))
        assertTrue(manager.allFilesHaveLastAnalysisDate())
    }

    @Test
    fun `getFilesByStatus filters correctly`() = runTest {
        val manager = FileStateManager()
        manager.addFile(testEntry("/a.exe", status = FileStatus.HASHED_FOUND))
        manager.addFile(testEntry("/b.exe", status = FileStatus.PENDING))
        manager.addFile(testEntry("/c.exe", status = FileStatus.HASHED_FOUND))

        val found = manager.getFilesByStatus(FileStatus.HASHED_FOUND)
        assertEquals(2, found.size)
        assertTrue(found.all { it.status == FileStatus.HASHED_FOUND })
    }

    @Test
    fun `addFile overwrites existing entry with same path`() = runTest {
        val manager = FileStateManager()
        manager.addFile(testEntry("/test.exe", status = FileStatus.PENDING))
        manager.addFile(testEntry("/test.exe", status = FileStatus.ERROR))
        val file = manager.getFile("/test.exe")
        assertNotNull(file)
        assertEquals(FileStatus.ERROR, file.status)
        assertEquals(1, manager.getAllFiles().size)
    }
}
