package com.vtbatch.desktop.mvi

import com.vtbatch.model.*
import kotlinx.coroutines.*
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class SideEffectsTest {

    // ═══════════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════

    private data class TestEnv(
        val sideEffects: SideEffects,
        val dispatched: MutableList<AppIntent>,
        val container: AppContainer,
        val scope: CoroutineScope
    )

    private fun createEnv(apiKey: String? = null): TestEnv {
        val dispatched = Collections.synchronizedList(mutableListOf<AppIntent>())
        val container = AppContainer(apiKey = apiKey)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val se = SideEffects(container, { dispatched.add(it) }, scope)
        return TestEnv(se, dispatched, container, scope)
    }

    private fun testEntry(
        path: String = "/test/file.exe",
        fileName: String = "file.exe",
        status: FileStatus = FileStatus.HASHED_FOUND,
        md5Hash: String? = "abc123def45600000000000000000000",
        detectionRatio: String? = "0/70",
        analysisUrl: String? = "https://www.virustotal.com/gui/file/sha256hash",
        sha256Hash: String? = "sha256hash0000000000000000000000000000000000000000000000",
        lastAnalysisDate: String? = "2025-01-15 10:30"
    ) = FileEntry(
        path = path,
        fileName = fileName,
        fileSizeBytes = 1024L,
        fileSizeFormatted = "1.0 KB",
        md5Hash = md5Hash,
        sha256Hash = sha256Hash,
        status = status,
        detectionRatio = detectionRatio,
        analysisUrl = analysisUrl,
        lastAnalysisDate = lastAnalysisDate
    )

    /**
     * Spin-wait on [dispatched] until [predicate] matches.
     * Uses blocking sleep to avoid coroutine scope issues.
     */
    private fun waitForIntent(
        dispatched: List<AppIntent>,
        predicate: (AppIntent) -> Boolean,
        timeoutMs: Long = 4000
    ) {
        val start = System.currentTimeMillis()
        while (!dispatched.any(predicate)) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                throw AssertionError(
                    "Timeout waiting for intent. Got: ${dispatched.map { it::class.simpleName }}"
                )
            }
            Thread.sleep(50)
        }
    }

    private fun waitForLog(dispatched: List<AppIntent>, substring: String) {
        waitForIntent(dispatched, { intent -> intent is AppIntent.LogMessage && intent.message.contains(substring) })
    }

    private fun waitForIntentType(dispatched: List<AppIntent>, klass: Class<out AppIntent>) {
        waitForIntent(dispatched, { intent -> klass.isInstance(intent) })
    }

    private fun waitForError(dispatched: List<AppIntent>, substring: String) {
        waitForIntent(dispatched, { intent -> intent is AppIntent.Error && intent.message.contains(substring) })
    }

    private fun cleanup(env: TestEnv) {
        env.scope.cancel()
        env.container.shutdown()
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Command routing — help
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `help command dispatches help text`() {
        val env = createEnv()
        env.sideEffects.executeCommand("help", emptyList())
        waitForLog(env.dispatched, "Available commands")

        val msg = env.dispatched.filterIsInstance<AppIntent.LogMessage>()
            .first { it.message.contains("Available commands") }
        assertTrue(msg.message.contains("check"))
        assertTrue(msg.message.contains("update"))
        assertTrue(msg.message.contains("find"))
        assertTrue(msg.message.contains("list"))
        cleanup(env)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Command routing — clear
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `clear command dispatches ClearList`() {
        val env = createEnv()
        env.sideEffects.executeCommand("clear", emptyList())
        waitForIntentType(env.dispatched, AppIntent.ClearList::class.java)
        assertTrue(env.dispatched.contains(AppIntent.ClearList))
        cleanup(env)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Command routing — find
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `find command dispatches FindFiles with search term`() {
        val env = createEnv()
        env.sideEffects.executeCommand("find malware", emptyList())
        waitForIntentType(env.dispatched, AppIntent.FindFiles::class.java)
        assertEquals("malware", env.dispatched.filterIsInstance<AppIntent.FindFiles>().first().term)
        cleanup(env)
    }

    @Test
    fun `find command trims whitespace`() {
        val env = createEnv()
        env.sideEffects.executeCommand("find   trojan.exe  ", emptyList())
        waitForIntentType(env.dispatched, AppIntent.FindFiles::class.java)
        assertEquals("trojan.exe", env.dispatched.filterIsInstance<AppIntent.FindFiles>().first().term)
        cleanup(env)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Command routing — unknown
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `unknown command shows unknown message`() {
        val env = createEnv()
        env.sideEffects.executeCommand("foobar", emptyList())
        waitForLog(env.dispatched, "Unknown command")
        val msg = env.dispatched.filterIsInstance<AppIntent.LogMessage>()
            .first { it.message.contains("Unknown command") }
        assertTrue(msg.message.contains("foobar"))
        assertTrue(msg.message.contains("help"))
        cleanup(env)
    }

    @Test
    fun `empty command does nothing`() {
        val env = createEnv()
        env.sideEffects.executeCommand("", emptyList())
        env.sideEffects.executeCommand("   ", emptyList())
        assertTrue(env.dispatched.isEmpty())
        cleanup(env)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Command routing — stats
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `stats command shows telemetry data`() {
        val env = createEnv()
        env.sideEffects.executeCommand("stats", emptyList())
        waitForLog(env.dispatched, "Local Statistics")
        val msg = env.dispatched.filterIsInstance<AppIntent.LogMessage>()
            .first { it.message.contains("Local Statistics") }
        assertTrue(msg.message.contains("Files scanned"))
        assertTrue(msg.message.contains("Cache hit rate"))
        assertTrue(msg.message.contains("Upload success"))
        cleanup(env)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Command routing — api
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `api command shows masked key when configured`() {
        val env = createEnv(apiKey = "abcdef1234567890abcdef1234567890")
        env.sideEffects.executeCommand("api", emptyList())
        waitForLog(env.dispatched, "API Key")
        val msg = env.dispatched.filterIsInstance<AppIntent.LogMessage>()
            .first { it.message.contains("API Key") }
        assertTrue(msg.message.contains("abcd****7890"))
        assertFalse(msg.message.contains("abcdef1234567890"))
        cleanup(env)
    }

    @Test
    fun `api command shows no-key message when not configured`() {
        val env = createEnv()
        env.sideEffects.executeCommand("api", emptyList())
        waitForLog(env.dispatched, "No API key")
        cleanup(env)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Command routing — list
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `list command groups files by extension`() {
        val files = listOf(
            testEntry(fileName = "malware.exe", path = "/test/malware.exe"),
            testEntry(fileName = "trojan.exe", path = "/test/trojan.exe"),
            testEntry(fileName = "doc.docm", path = "/test/doc.docm")
        )
        val env = createEnv()
        env.sideEffects.executeCommand("list", files)
        waitForLog(env.dispatched, "Files by extension")
        val msg = env.dispatched.filterIsInstance<AppIntent.LogMessage>()
            .first { it.message.contains("Files by extension") }
        assertTrue(msg.message.contains(".exe"))
        assertTrue(msg.message.contains(".docm"))
        assertTrue(msg.message.contains("3 total"))
        cleanup(env)
    }

    @Test
    fun `list exe filters by extension`() {
        val files = listOf(
            testEntry(fileName = "malware.exe", path = "/test/malware.exe"),
            testEntry(fileName = "trojan.exe", path = "/test/trojan.exe"),
            testEntry(fileName = "doc.docm", path = "/test/doc.docm")
        )
        val env = createEnv()
        env.sideEffects.executeCommand("list exe", files)
        waitForLog(env.dispatched, "Files with extension")
        val msg = env.dispatched.filterIsInstance<AppIntent.LogMessage>()
            .first { it.message.contains("Files with extension") }
        assertTrue(msg.message.contains(".exe"))
        assertTrue(msg.message.contains("2"))  // 2 .exe files
        assertFalse(msg.message.contains(".docm"))
        cleanup(env)
    }

    @Test
    fun `list with unmatched extension shows no-files message`() {
        val files = listOf(testEntry(fileName = "malware.exe", path = "/test/malware.exe"))
        val env = createEnv()
        env.sideEffects.executeCommand("list pdf", files)
        waitForLog(env.dispatched, "No files with extension")
        cleanup(env)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  removeGreen logic
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `removeGreen removes files with 0 detections`() {
        val files = listOf(
            testEntry(fileName = "clean.exe", detectionRatio = "0/70"),
            testEntry(fileName = "malware.exe", detectionRatio = "45/70"),
            testEntry(fileName = "clean2.doc", detectionRatio = "0/70")
        )
        val env = createEnv()
        env.sideEffects.executeCommand("remove-green", files)
        waitForIntentType(env.dispatched, AppIntent.FilesUpdated::class.java)
        val update = env.dispatched.filterIsInstance<AppIntent.FilesUpdated>().first()
        assertEquals(1, update.files.size)
        assertEquals("malware.exe", update.files[0].fileName)
        cleanup(env)
    }

    @Test
    fun `removeGreen keeps files with null detection ratio`() {
        val files = listOf(
            testEntry(fileName = "unknown.exe", detectionRatio = null),
            testEntry(fileName = "clean.exe", detectionRatio = "0/70")
        )
        val env = createEnv()
        env.sideEffects.executeCommand("remove-green", files)
        waitForIntentType(env.dispatched, AppIntent.FilesUpdated::class.java)
        val update = env.dispatched.filterIsInstance<AppIntent.FilesUpdated>().first()
        assertEquals(1, update.files.size)
        assertEquals("unknown.exe", update.files[0].fileName)
        cleanup(env)
    }

    @Test
    fun `removeGreen keeps files with malformed ratio`() {
        val files = listOf(
            testEntry(fileName = "bad.exe", detectionRatio = "invalid"),
            testEntry(fileName = "clean.exe", detectionRatio = "0/70")
        )
        val env = createEnv()
        env.sideEffects.executeCommand("remove-green", files)
        waitForIntentType(env.dispatched, AppIntent.FilesUpdated::class.java)
        val update = env.dispatched.filterIsInstance<AppIntent.FilesUpdated>().first()
        assertEquals(1, update.files.size)
        assertEquals("bad.exe", update.files[0].fileName)
        cleanup(env)
    }

    @Test
    fun `removeGreen log message shows removal count`() {
        val files = listOf(
            testEntry(fileName = "clean.exe", detectionRatio = "0/70"),
            testEntry(fileName = "malware.exe", detectionRatio = "45/70")
        )
        val env = createEnv()
        env.sideEffects.executeCommand("remove-green", files)
        waitForLog(env.dispatched, "Removed 1 clean file")
        cleanup(env)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  open-red without malicious files
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `open-red with no malicious files shows message`() {
        val files = listOf(testEntry(fileName = "clean.exe", detectionRatio = "0/70"))
        val env = createEnv()
        env.sideEffects.executeCommand("open-red", files)
        waitForLog(env.dispatched, "No malicious")
        cleanup(env)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Error paths when no API key
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `check command without API key shows error`() {
        val env = createEnv()
        env.sideEffects.executeCommand("check abc123", emptyList())
        waitForError(env.dispatched, "No API key")
        cleanup(env)
    }

    @Test
    fun `update command without API key shows error`() {
        val env = createEnv()
        env.sideEffects.executeCommand("update", listOf(testEntry()))
        waitForError(env.dispatched, "No API key")
        cleanup(env)
    }

    @Test
    fun `force command without API key shows error`() {
        val env = createEnv()
        env.sideEffects.executeCommand("force", listOf(testEntry()))
        waitForError(env.dispatched, "No API key")
        cleanup(env)
    }

    // Per-row Recheck button -> recheckFile. Guards share the force path.
    @Test
    fun `recheckFile without API key shows error`() {
        val env = createEnv()
        env.sideEffects.recheckFile(testEntry())
        waitForError(env.dispatched, "No API key")
        cleanup(env)
    }

    @Test
    fun `recheckFile without any hash shows message`() {
        val env = createEnv(apiKey = "abcdef1234567890abcdef1234567890")
        env.sideEffects.recheckFile(testEntry(md5Hash = null, sha256Hash = null))
        waitForLog(env.dispatched, "no hash available")
        cleanup(env)
    }

    // parseForceRecheckTargets: pure parser, no VT API needed.
    // Dates compared as epochs; "force-older" hyphen + space forms normalize the same.
    @Test
    fun `force-older hyphen form selects files older than date`() {
        val sel = parseForceRecheckTargets("force-older 2030-01-01", listOf(testEntry()))
        assertEquals(1, sel.targets.size)
        assertTrue(sel.error == null)
    }

    @Test
    fun `force older space form still selects files older than date`() {
        val sel = parseForceRecheckTargets("force older 2030-01-01", listOf(testEntry()))
        assertEquals(1, sel.targets.size)
    }

    @Test
    fun `force-older excludes files newer than the cutoff`() {
        val sel = parseForceRecheckTargets("force-older 2000-01-01", listOf(testEntry()))
        assertTrue(sel.targets.isEmpty())
    }

    // Regression: the reported bug. "28-07-26" is not YYYY-MM-DD; the old string
    // compare made every "20xx-..." date sort before it and re-checked everything.
    // Now the bad format is rejected with an error, not silently applied.
    @Test
    fun `force-older rejects non-ISO date instead of matching everything`() {
        val sel = parseForceRecheckTargets("force-older 28-07-26", listOf(testEntry()))
        assertTrue(sel.targets.isEmpty())
        assertTrue(sel.error?.contains("Could not parse date") == true)
    }

    // Regression: a file last analyzed on the cutoff day is NOT "older than" it.
    // testEntry().lastAnalysisDate = "2025-01-15 10:30".
    @Test
    fun `force-older excludes a file from the cutoff day itself`() {
        val sel = parseForceRecheckTargets("force-older 2025-01-15", listOf(testEntry()))
        assertTrue(sel.targets.isEmpty())
        assertTrue(sel.error == null)
    }

    @Test
    fun `force without args targets all hashed files`() {
        val sel = parseForceRecheckTargets("force", listOf(testEntry()))
        assertEquals(1, sel.targets.size)
    }

    @Test
    fun `force with hash targets only the matching file`() {
        val md5 = "abc123def45600000000000000000000"
        val sel = parseForceRecheckTargets("force $md5", listOf(testEntry(md5Hash = md5)))
        assertEquals(1, sel.targets.size)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Case-insensitive commands
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `HELP is case-insensitive`() {
        val env = createEnv()
        env.sideEffects.executeCommand("HELP", emptyList())
        waitForLog(env.dispatched, "Available commands")
        cleanup(env)
    }

    @Test
    fun `CLEAR is case-insensitive`() {
        val env = createEnv()
        env.sideEffects.executeCommand("CLEAR", emptyList())
        waitForIntentType(env.dispatched, AppIntent.ClearList::class.java)
        cleanup(env)
    }

    @Test
    fun `Find is case-insensitive`() {
        val env = createEnv()
        env.sideEffects.executeCommand("Find test", emptyList())
        waitForIntentType(env.dispatched, AppIntent.FindFiles::class.java)
        cleanup(env)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  api-swap removed (stub — removed from help listing)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `api-swap shows unknown command message`() {
        val env = createEnv()
        env.sideEffects.executeCommand("api-swap", emptyList())
        waitForLog(env.dispatched, "Unknown command")
        cleanup(env)
    }
}
