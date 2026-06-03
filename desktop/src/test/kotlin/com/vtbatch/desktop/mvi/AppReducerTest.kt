package com.vtbatch.desktop.mvi

import com.vtbatch.model.*
import com.vtbatch.desktop.ui.navigation.FindNavigator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppReducerTest {

    private val initialState = AppState()
    private val reducer = AppReducer

    // ── User Actions ────────────────────────────────────────────────

    @Test
    fun `DropFiles adds scanning log`() {
        val result = reducer.reduce(initialState, AppIntent.DropFiles(listOf("C:\\test")))
        assertTrue(result.statusLog.last().contains("Scanning dropped paths"))
    }

    @Test
    fun `SubmitCommand echoes command to log`() {
        val result = reducer.reduce(initialState, AppIntent.SubmitCommand("help"))
        assertEquals("> help", result.statusLog.last())
    }

    @Test
    fun `StartProcessing sets isProcessing true when files pending`() {
        val state = initialState.copy(files = listOf(
            FileEntry(path = "test.exe", fileName = "test.exe", fileSizeBytes = 100,
                fileSizeFormatted = "100 B", md5Hash = "abc", status = FileStatus.PENDING)
        ))
        val result = reducer.reduce(state, AppIntent.StartProcessing)
        assertTrue(result.isProcessing)
        assertTrue(result.statusLog.last().contains("Starting processing"))
    }

    @Test
    fun `StartProcessing does nothing when no files to process`() {
        val result = reducer.reduce(initialState, AppIntent.StartProcessing)
        assertFalse(result.isProcessing)
        assertTrue(result.statusLog.last().contains("No files to process"))
    }

    @Test
    fun `TogglePause toggles pause state`() {
        val result = reducer.reduce(initialState, AppIntent.TogglePause)
        assertTrue(result.isPaused)
        val result2 = reducer.reduce(result, AppIntent.TogglePause)
        assertFalse(result2.isPaused)
    }

    @Test
    fun `UploadNewFiles sets isUploading when files need upload`() {
        val state = initialState.copy(files = listOf(
            FileEntry(path = "test.exe", fileName = "test.exe", fileSizeBytes = 100,
                fileSizeFormatted = "100 B", md5Hash = "abc", status = FileStatus.HASHED_NOT_FOUND)
        ))
        val result = reducer.reduce(state, AppIntent.UploadNewFiles)
        assertTrue(result.isUploading)
    }

    @Test
    fun `UploadNewFiles skips when no files to upload`() {
        val result = reducer.reduce(initialState, AppIntent.UploadNewFiles)
        assertFalse(result.isUploading)
    }

    @Test
    fun `OpenHashedFiles counts files with URLs`() {
        val state = initialState.copy(files = listOf(
            FileEntry(path = "a.exe", fileName = "a.exe", fileSizeBytes = 100,
                fileSizeFormatted = "100 B", analysisUrl = "https://vt.example.com"),
            FileEntry(path = "b.exe", fileName = "b.exe", fileSizeBytes = 100,
                fileSizeFormatted = "100 B")
        ))
        val result = reducer.reduce(state, AppIntent.OpenHashedFiles)
        assertTrue(result.statusLog.last().contains("1 file"))
    }

    @Test
    fun `ClearList resets everything`() {
        val state = initialState.copy(
            files = listOf(FileEntry(path = "a", fileName = "a", fileSizeBytes = 1, fileSizeFormatted = "1 B")),
            isProcessing = true, statusLog = listOf("old log")
        )
        val result = reducer.reduce(state, AppIntent.ClearList)
        assertTrue(result.files.isEmpty())
        assertFalse(result.isProcessing)
        assertFalse(result.isUploading)
        assertNull(result.currentFile)
        assertTrue(result.statusLog.last().contains("cleared"))
    }

    // ── Credential Dialog ───────────────────────────────────────────

    @Test
    fun `ShowCredentialDialog sets flag`() {
        val result = reducer.reduce(initialState, AppIntent.ShowCredentialDialog)
        assertTrue(result.showCredentialDialog)
    }

    @Test
    fun `HideCredentialDialog clears flag`() {
        val state = initialState.copy(showCredentialDialog = true)
        val result = reducer.reduce(state, AppIntent.HideCredentialDialog)
        assertFalse(result.showCredentialDialog)
    }

    @Test
    fun `CredentialsValidated sets hasCredentials`() {
        val result = reducer.reduce(initialState, AppIntent.CredentialsValidated("key", "user"))
        assertTrue(result.hasCredentials)
        assertFalse(result.isValidatingCredentials)
    }

    @Test
    fun `CredentialsInvalid clears hasCredentials`() {
        val result = reducer.reduce(initialState, AppIntent.CredentialsInvalid("bad key"))
        assertFalse(result.hasCredentials)
    }

    // ── Async Results ───────────────────────────────────────────────

    @Test
    fun `FilesScanned replaces file list`() {
        val files = listOf(
            FileEntry(path = "a.exe", fileName = "a.exe", fileSizeBytes = 100, fileSizeFormatted = "100 B")
        )
        val result = reducer.reduce(initialState, AppIntent.FilesScanned(files, "Found 1 file"))
        assertEquals(1, result.files.size)
        assertTrue(result.statusLog.last().contains("Found 1 file"))
    }

    @Test
    fun `FileProcessed updates single file`() {
        val entry = FileEntry(path = "test.exe", fileName = "test.exe", fileSizeBytes = 100,
            fileSizeFormatted = "100 B", md5Hash = "abc", status = FileStatus.PENDING)
        val state = initialState.copy(files = listOf(entry))

        val updated = entry.copy(status = FileStatus.HASHED_FOUND, detectionRatio = "0/72")
        val result = reducer.reduce(state, AppIntent.FileProcessed("test.exe", updated))

        assertEquals(FileStatus.HASHED_FOUND, result.files[0].status)
        assertEquals("0/72", result.files[0].detectionRatio)
    }

    @Test
    fun `FileProcessed does not affect other files`() {
        val file1 = FileEntry(path = "a.exe", fileName = "a.exe", fileSizeBytes = 100, fileSizeFormatted = "100 B")
        val file2 = FileEntry(path = "b.exe", fileName = "b.exe", fileSizeBytes = 200, fileSizeFormatted = "200 B")
        val state = initialState.copy(files = listOf(file1, file2))

        val updated = file1.copy(status = FileStatus.HASHED_FOUND)
        val result = reducer.reduce(state, AppIntent.FileProcessed("a.exe", updated))

        assertEquals(FileStatus.HASHED_FOUND, result.files[0].status)
        assertEquals(FileStatus.PENDING, result.files[1].status) // unchanged
    }

    @Test
    fun `FileUploaded updates status to UPLOADED_AWAITING`() {
        val entry = FileEntry(path = "test.exe", fileName = "test.exe", fileSizeBytes = 100,
            fileSizeFormatted = "100 B", status = FileStatus.UPLOADING)
        val state = initialState.copy(files = listOf(entry))

        val result = reducer.reduce(state, AppIntent.FileUploaded("test.exe", "id123", "https://vt.example.com"))
        assertEquals(FileStatus.UPLOADED_AWAITING, result.files[0].status)
        assertEquals("https://vt.example.com", result.files[0].analysisUrl)
    }

    @Test
    fun `AnalysisCompleted updates file and logs`() {
        val entry = FileEntry(path = "t.exe", fileName = "t.exe", fileSizeBytes = 100,
            fileSizeFormatted = "100 B", status = FileStatus.UPLOADED_AWAITING)
        val state = initialState.copy(files = listOf(entry))

        val updated = entry.copy(status = FileStatus.HASHED_FOUND, detectionRatio = "5/72")
        val result = reducer.reduce(state, AppIntent.AnalysisCompleted("t.exe", updated))

        assertEquals(FileStatus.HASHED_FOUND, result.files[0].status)
        assertTrue(result.statusLog.last().contains("Analysis complete"))
    }

    @Test
    fun `AnalysisTimeout sets ANALYSIS_TIMEOUT status`() {
        val entry = FileEntry(path = "t.exe", fileName = "t.exe", fileSizeBytes = 100,
            fileSizeFormatted = "100 B", status = FileStatus.UPLOADED_AWAITING)
        val state = initialState.copy(files = listOf(entry))

        val result = reducer.reduce(state, AppIntent.AnalysisTimeout("t.exe"))
        assertEquals(FileStatus.ANALYSIS_TIMEOUT, result.files[0].status)
    }

    @Test
    fun `QuotaUpdated stores quota data`() {
        val result = reducer.reduce(initialState,
            AppIntent.QuotaUpdated(QuotaData(42, 500), QuotaData(500, 5000)))
        assertNotNull(result.quotaDaily)
        assertEquals(42, result.quotaDaily!!.used)
        assertEquals(500, result.quotaDaily!!.total)
    }

    @Test
    fun `QuotaUpdated clears quotaError`() {
        val state = initialState.copy(quotaError = "Unable to fetch")
        val result = reducer.reduce(state,
            AppIntent.QuotaUpdated(QuotaData(42, 500), QuotaData(500, 5000)))
        assertNull(result.quotaError)
    }

    @Test
    fun `QuotaError sets error and clears quota data`() {
        val state = initialState.copy(
            quotaDaily = QuotaData(42, 500),
            quotaMonthly = QuotaData(500, 5000)
        )
        val result = reducer.reduce(state, AppIntent.QuotaError("Unable to fetch"))
        assertNull(result.quotaDaily)
        assertNull(result.quotaMonthly)
        assertEquals("Unable to fetch", result.quotaError)
    }

    @Test
    fun `RecheckTimerTick updates timer state`() {
        val result = reducer.reduce(initialState, AppIntent.RecheckTimerTick(120, 3))
        assertEquals(120, result.recheckRemaining)
        assertEquals(3, result.recheckPendingCount)
    }

    @Test
    fun `ProcessingCompleted resets isProcessing`() {
        val state = initialState.copy(isProcessing = true)
        val result = reducer.reduce(state, AppIntent.ProcessingCompleted)
        assertFalse(result.isProcessing)
        assertNull(result.currentFile)
    }

    @Test
    fun `UploadCompleted resets isUploading`() {
        val state = initialState.copy(isUploading = true)
        val result = reducer.reduce(state, AppIntent.UploadCompleted)
        assertFalse(result.isUploading)
    }

    @Test
    fun `Error adds to status log`() {
        val result = reducer.reduce(initialState, AppIntent.Error("Something went wrong"))
        assertTrue(result.statusLog.last().contains("Something went wrong"))
    }

    @Test
    fun `LogMessage adds to status log`() {
        val result = reducer.reduce(initialState, AppIntent.LogMessage("Info message"))
        assertEquals("Info message", result.statusLog.last())
    }

    // ── Find ────────────────────────────────────────────────────────

    @Test
    fun `FindFiles sets matches`() {
        val files = listOf(
            FileEntry(path = "malware.exe", fileName = "malware.exe", fileSizeBytes = 100, fileSizeFormatted = "100 B"),
            FileEntry(path = "clean.pdf", fileName = "clean.pdf", fileSizeBytes = 200, fileSizeFormatted = "200 B")
        )
        val state = initialState.copy(files = files)
        val result = reducer.reduce(state, AppIntent.FindFiles("mal"))
        assertTrue(result.findMatches.hasMatches)
        assertEquals(1, result.findMatches.matchIndices.size)
    }

    @Test
    fun `FindFiles with blank query clears matches`() {
        val state = initialState.copy(findMatches = FindNavigator.FindMatches(
            query = "old", matchIndices = listOf(0), currentIndex = 0
        ))
        val result = reducer.reduce(state, AppIntent.FindFiles(""))
        assertFalse(result.findMatches.hasMatches)
    }

    @Test
    fun `NavigateMatches cycles through results`() {
        val matches = FindNavigator.FindMatches(query = "test", matchIndices = listOf(0, 5, 10), currentIndex = 0)
        val state = initialState.copy(findMatches = matches)

        val next = reducer.reduce(state, AppIntent.NavigateMatches(1))
        assertEquals(1, next.findMatches.currentIndex)

        val wrap = reducer.reduce(next, AppIntent.NavigateMatches(1))
        assertEquals(2, wrap.findMatches.currentIndex)

        val wrapAround = reducer.reduce(wrap, AppIntent.NavigateMatches(1))
        assertEquals(0, wrapAround.findMatches.currentIndex)
    }
}
