package com.vtbatch.desktop.mvi

import com.vtbatch.model.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

private val logger = KotlinLogging.logger {}

private val VT_FILE_URL = "https://www.virustotal.com/gui/file/"

/**
 * All async side effects for the MVI architecture.
 *
 * Side effects are the only place where impure operations happen:
 * - API calls to VirusTotal
 * - File I/O (hashing, scanning, cache)
 * - Browser launches
 * - Timer/coroutine management
 *
 * Each side effect runs in a coroutine and dispatches result intents
 * back into the store. The reducer is never called directly here —
 * only `dispatch()` is used to emit intents.
 */
class SideEffects(
    private val container: AppContainer,
    private val dispatch: (AppIntent) -> Unit,
    private val scope: CoroutineScope
) {
    private val scanner = FileScanner()
    private var scanJob: Job? = null
    private var processJob: Job? = null
    private var uploadJob: Job? = null
    private var quotaRefreshJob: Job? = null

    init {
        // Try fetching quota on startup (works if env vars are set).
        // If credentials come from persisted storage, they'll trigger
        // fetchQuota again via SubmitCredentials -> validateCredentials.
        fetchQuota()
        // Refresh quota every 60 seconds (matching Python source)
        quotaRefreshJob = scope.launch {
            while (true) {
                delay(60 * 1000L)
                fetchQuota()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  FILE SCANNING (DropFiles)
    // ═══════════════════════════════════════════════════════════════════

    /** Cancel all running operations (matching Python's handle_clear_list) */
    fun cancelAll() {
        scanJob?.cancel()
        processJob?.cancel()
        uploadJob?.cancel()
        container.pendingRecheckTracker.close()
    }

    fun scanFiles(paths: List<String>) {
        scanJob?.cancel()
        scanJob = scope.launch {
            try {
                val allSuspicious = mutableSetOf<String>()
                for (path in paths) {
                    allSuspicious.addAll(
                        withContext(Dispatchers.IO) { scanner.getSuspiciousFiles(path) }
                    )
                }

                if (allSuspicious.isEmpty()) {
                    dispatch(AppIntent.LogMessage("No suspicious files found in dropped paths."))
                    dispatch(AppIntent.FilesScanned(emptyList(), "No files found."))
                    return@launch
                }

                // Load cache
                val cache = withContext(Dispatchers.IO) { container.quotaManager.loadData() }
                val api = container.virusTotalApi

                val files = mutableListOf<FileEntry>()
                var newCount = 0
                var cachedCount = 0
                var totalBytesHashed = 0L
                val startTime = System.currentTimeMillis()

                for ((index, filePath) in allSuspicious.withIndex()) {
                    val file = File(filePath)
                    if (!file.exists()) continue

                    val sizeBytes = file.length()
                    val sizeFormatted = formatFileSize(sizeBytes)

                    // Compute MD5
                    val md5 = try {
                        withContext(Dispatchers.IO) { api?.calculateMd5(filePath) }
                    } catch (e: Exception) {
                        logger.warn { "Failed to hash $filePath: ${e.message}" }
                        dispatch(AppIntent.LogMessage("  Warning: could not hash ${file.name}"))
                        FileEntry(
                            path = filePath,
                            fileName = file.name,
                            fileSizeBytes = sizeBytes,
                            fileSizeFormatted = sizeFormatted,
                            status = FileStatus.PENDING,
                            errorMessage = "Hash failed: ${e.message}"
                        ).also { files.add(it) }
                        continue
                    }

                    // Check cache
                    val cached = cache[md5]
                    if (cached != null) {
                        cachedCount++
                        container.telemetry.recordCacheHit()
                        files.add(FileEntry(
                            path = filePath,
                            fileName = file.name,
                            fileSizeBytes = sizeBytes,
                            fileSizeFormatted = sizeFormatted,
                            md5Hash = md5,
                            status = FileStatus.HASHED_FOUND,
                            analysisUrl = cached.url,
                            detectionRatio = cached.detections,
                            lastAnalysisDate = cached.lastAnalysisDate?.let { formatTimestamp(it) },
                            lastAnalysisStats = cached.lastAnalysisStats,
                            popularThreatLabel = cached.suggestedThreatLabel,
                            typeDescription = cached.typeDescription,
                            tags = cached.tags?.split(",")?.filter { it.isNotBlank() },
                            meaningfulName = cached.meaningfulName,
                            timesSubmitted = cached.timesSubmitted,
                            reputation = cached.reputation,
                            firstSubmissionDate = cached.firstSubmissionDate?.let { formatTimestamp(it) },
                            lastSubmissionDate = cached.lastSubmissionDate?.let { formatTimestamp(it) },
                            totalVotes = cached.totalVotesHarmless?.let { h ->
                                cached.totalVotesMalicious?.let { m -> Pair(h, m) }
                            }
                        ))
                    } else {
                        newCount++
                        container.telemetry.recordCacheMiss()
                        files.add(FileEntry(
                            path = filePath,
                            fileName = file.name,
                            fileSizeBytes = sizeBytes,
                            fileSizeFormatted = sizeFormatted,
                            md5Hash = md5,
                            status = FileStatus.PENDING
                        ))
                    }

                    // Progress
                    totalBytesHashed += sizeBytes
                    val pct = (index + 1).toFloat() / allSuspicious.size
                    val elapsed = (System.currentTimeMillis() - startTime).toFloat() / 1000f
                    val speedMbps = if (elapsed > 0) (totalBytesHashed / (1024.0 * 1024.0)) / elapsed else 0.0
                    dispatch(AppIntent.HashingProgress(
                        percent = pct,
                        speedMbps = speedMbps.toFloat(),
                        fileCount = index + 1,
                        elapsedFormatted = String.format("%.1fs", elapsed)
                    ))
                }

                val elapsed = ((System.currentTimeMillis() - startTime) / 1000.0)
                val summary = buildString {
                    append("Found ${files.size} file(s)")
                    if (cachedCount > 0) append(", $cachedCount cached")
                    if (newCount > 0) append(", $newCount new")
                    append(String.format(" (%.1fs)", elapsed))
                }

                container.telemetry.recordFilesScanned(files.size)
                dispatch(AppIntent.FilesScanned(files, summary))

                // Auto-refresh stale cache entries in background so scan returns immediately
                val stale = files.filter { it.detectionRatio == null && it.md5Hash != null }
                if (stale.isNotEmpty() && container.virusTotalApi != null) {
                    scope.launch { refreshStaleEntries(stale) }
                }

            } catch (e: Exception) {
                val msg = container.errorHandler.handle(e)
                dispatch(AppIntent.Error(msg))
            }
        }
    }

    /** Refresh stale cache entries one by one using FileProcessed (preserves the full file list). */
    private suspend fun refreshStaleEntries(stale: List<FileEntry>) {
        val api = container.virusTotalApi ?: return
        dispatch(AppIntent.LogMessage("Refreshing ${stale.size} stale cache entry(ies)..."))

        for (entry in stale) {
            val hash = entry.md5Hash ?: continue
            try {
                val result = withContext(Dispatchers.IO) {
                    api.checkFileOnVirusTotal(hash)
                }

                val updatedEntry = if (result != null) {
                    val stats = VTResponseParser.extractDetectionStats(result)
                    val sha256 = VTResponseParser.extractSha256(result)
                    val lastDate = VTResponseParser.extractLastAnalysisDate(result)
                    val details = VTResponseParser.extractFileDetails(result)

                    // Update cache
                    withContext(Dispatchers.IO) {
                        container.quotaManager.saveEntry(hash, buildCacheEntry(entry, sha256, lastDate, stats, details))
                    }

                    entry.copy(
                        status = FileStatus.HASHED_FOUND,
                        sha256Hash = sha256,
                        analysisUrl = sha256?.let { "${VT_FILE_URL}$it" } ?: entry.analysisUrl,
                        detectionRatio = stats?.ratio,
                        lastAnalysisDate = lastDate?.let { formatTimestamp(it) }
                    ).withDetails(details)
                } else {
                    entry.copy(status = FileStatus.HASHED_NOT_FOUND)
                }

                dispatch(AppIntent.FileProcessed(entry.path, updatedEntry))
            } catch (e: Exception) {
                logger.warn { "Auto-refresh failed for ${entry.fileName}: ${e.message}" }
            }
        }

        dispatch(AppIntent.LogMessage("Stale cache refresh complete."))
    }

    // ═══════════════════════════════════════════════════════════════════
    //  FILE PROCESSING (StartProcessing)
    // ═══════════════════════════════════════════════════════════════════

    fun processFiles(files: List<FileEntry>) {
        processJob?.cancel()
        processJob = scope.launch {
            val toProcess = files.filter {
                it.status == FileStatus.PENDING && it.md5Hash != null
            }

            // Mark files that failed hashing as ERROR (md5Hash == null)
            val hashFailed = files.filter {
                it.status == FileStatus.PENDING && it.md5Hash == null
            }
            if (hashFailed.isNotEmpty()) {
                val failedUpdates = hashFailed.map { it.copy(
                    status = FileStatus.HASH_FAILED,
                    errorMessage = "Failed to compute file hash"
                ) }
                dispatch(AppIntent.FilesUpdated(failedUpdates))
                logger.warn { "${hashFailed.size} files skipped due to failed hashing" }
            }

            if (toProcess.isEmpty()) {
                dispatch(AppIntent.ProcessingCompleted)
                return@launch
            }

            val api = container.virusTotalApi
            if (api == null) {
                dispatch(AppIntent.Error("No API key configured. Use the credential dialog or set VT_API_KEY."))
                dispatch(AppIntent.ProcessingCompleted)
                return@launch
            }

            var processed = 0
            var totalBytesProcessed = 0L
            val startTime = System.currentTimeMillis()

            for (entry in toProcess) {
                // Check pause
                container.pauseController.waitIfPaused()

                val fileName = File(entry.path).name
                dispatch(AppIntent.CurrentProcessingChanged(fileName, "Checking hash on VT..."))

                try {
                    val vtResult = withContext(Dispatchers.IO) {
                        api.checkFileOnVirusTotal(entry.md5Hash!!)
                    }

                    val updatedEntry = if (vtResult != null) {
                        // Found on VT — extract data
                        val stats = VTResponseParser.extractDetectionStats(vtResult)
                        val lastDate = VTResponseParser.extractLastAnalysisDate(vtResult)
                        val sha256 = VTResponseParser.extractSha256(vtResult)
                        val details = VTResponseParser.extractFileDetails(vtResult)

                        // Save to cache
                        withContext(Dispatchers.IO) {
                            container.quotaManager.saveEntry(entry.md5Hash!!, buildCacheEntry(entry, sha256, lastDate, stats, details))
                        }

                        entry.copy(
                            status = FileStatus.HASHED_FOUND,
                            sha256Hash = sha256,
                            analysisUrl = "${VT_FILE_URL}$sha256",
                            detectionRatio = stats?.ratio,
                            lastAnalysisDate = lastDate?.let { formatTimestamp(it) }
                        ).withDetails(details)
                    } else {
                        // Not found on VT
                        entry.copy(status = FileStatus.HASHED_NOT_FOUND)
                    }

                    dispatch(AppIntent.FileProcessed(entry.path, updatedEntry))

                } catch (e: VTBatchError) {
                    val msg = container.errorHandler.handle(e)
                    dispatch(AppIntent.FileProcessed(entry.path, entry.copy(
                        status = FileStatus.ERROR,
                        errorMessage = msg
                    )))
                    dispatch(AppIntent.LogMessage("  Error processing $fileName: $msg"))
                } catch (e: Exception) {
                    val msg = container.errorHandler.handle(e)
                    dispatch(AppIntent.FileProcessed(entry.path, entry.copy(
                        status = FileStatus.ERROR,
                        errorMessage = msg
                    )))
                }

                processed++
                totalBytesProcessed += entry.fileSizeBytes
                val pct = processed.toFloat() / toProcess.size
                val elapsed = (System.currentTimeMillis() - startTime) / 1000f
                val speedMbps = if (elapsed > 0) (totalBytesProcessed / (1024.0 * 1024.0)) / elapsed else 0.0
                dispatch(AppIntent.TotalProgress(
                    percent = pct,
                    speedFormatted = "%.1f MB/s".format(speedMbps),
                    fileCount = processed,
                    elapsedFormatted = String.format("%.1fs", elapsed)
                ))
                dispatch(AppIntent.HashingProgress(
                    percent = pct,
                    speedMbps = speedMbps.toFloat(),
                    fileCount = processed,
                    elapsedFormatted = String.format("%.1fs", elapsed)
                ))
            }

            dispatch(AppIntent.CurrentProcessingChanged(null, null))
            dispatch(AppIntent.ProcessingCompleted)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  FILE UPLOAD (UploadNewFiles)
    // ═══════════════════════════════════════════════════════════════════

    fun uploadFiles(files: List<FileEntry>) {
        uploadJob?.cancel()
        uploadJob = scope.launch {
            val toUpload = files.filter {
                it.status == FileStatus.HASHED_NOT_FOUND && it.md5Hash != null
            }

            if (toUpload.isEmpty()) {
                dispatch(AppIntent.UploadCompleted)
                return@launch
            }

            val api = container.virusTotalApi
            if (api == null) {
                dispatch(AppIntent.Error("No API key configured."))
                dispatch(AppIntent.UploadCompleted)
                return@launch
            }

            var uploaded = 0
            var totalBytesUploaded = 0L
            val uploadStartTime = System.currentTimeMillis()
            // Collect successfully uploaded files for phase 2 (polling)
            val pendingAnalysis = mutableListOf<Pair<FileEntry, String>>()

            // ═══════════════════════════════════════════════════════════
            //  PHASE 1: Upload all files back-to-back
            // ═══════════════════════════════════════════════════════════
            dispatch(AppIntent.LogMessage("Phase 1: Uploading ${toUpload.size} file(s)..."))

            for (entry in toUpload) {
                container.pauseController.waitIfPaused()

                val fileName = File(entry.path).name
                dispatch(AppIntent.CurrentProcessingChanged(fileName, "Uploading..."))

                try {
                    dispatch(AppIntent.UploadProgress(entry.path, 0f))

                    val fileStartTime = System.currentTimeMillis()
                    var lastProgressBytes = 0L
                    var lastProgressTime = fileStartTime

                    val result = withContext(Dispatchers.IO) {
                        api.uploadFileToVirusTotal(entry.path) { bytesSent, totalBytes ->
                            val percent = if (totalBytes > 0) (bytesSent.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
                            dispatch(AppIntent.UploadProgress(entry.path, percent))

                            val now = System.currentTimeMillis()
                            if (now - lastProgressTime >= 200) {
                                val deltaTime = (now - lastProgressTime) / 1000f
                                val deltaBytes = bytesSent - lastProgressBytes
                                val speedMbps = if (deltaTime > 0) (deltaBytes / (1024.0 * 1024.0)) / deltaTime else 0.0
                                val totalElapsed = (now - fileStartTime) / 1000f

                                dispatch(AppIntent.UploadSpeed(
                                    percent = percent,
                                    speedMbps = speedMbps.toFloat(),
                                    fileCount = uploaded + 1,
                                    elapsedFormatted = String.format("%.1fs", totalElapsed)
                                ))
                                lastProgressBytes = bytesSent
                                lastProgressTime = now
                            }
                        }
                    }

                    val analysisId = VTResponseParser.extractAnalysisId(result)

                    if (analysisId != null) {
                        val analysisUrl = "https://www.virustotal.com/gui/file-analysis/$analysisId"
                        dispatch(AppIntent.FileUploaded(entry.path, analysisId, analysisUrl))
                        dispatch(AppIntent.LogMessage("  Uploaded $fileName, analysis ID: $analysisId"))
                        pendingAnalysis.add(entry to analysisId)
                        container.telemetry.recordUploadSuccess()
                    } else {
                        dispatch(AppIntent.FileProcessed(entry.path, entry.copy(
                            status = FileStatus.ERROR,
                            errorMessage = "Upload succeeded but no analysis ID returned"
                        )))
                    }

                } catch (e: VTBatchError) {
                    val msg = container.errorHandler.handle(e)
                    dispatch(AppIntent.FileProcessed(entry.path, entry.copy(
                        status = FileStatus.ERROR,
                        errorMessage = msg
                    )))
                    container.telemetry.recordUploadFailure()
                } catch (e: Exception) {
                    val msg = container.errorHandler.handle(e)
                    dispatch(AppIntent.FileProcessed(entry.path, entry.copy(
                        status = FileStatus.ERROR,
                        errorMessage = msg
                    )))
                    container.telemetry.recordUploadFailure()
                }

                // Update batch progress after each file (success or failure)
                uploaded++
                totalBytesUploaded += entry.fileSizeBytes
                val elapsed = (System.currentTimeMillis() - uploadStartTime) / 1000f
                val speedMbps = if (elapsed > 0) (totalBytesUploaded / (1024.0 * 1024.0)) / elapsed else 0.0
                dispatch(AppIntent.TotalProgress(
                    percent = uploaded.toFloat() / toUpload.size,
                    speedFormatted = "%.1f MB/s".format(speedMbps),
                    fileCount = uploaded,
                    elapsedFormatted = String.format("%.1fs", elapsed)
                ))
                dispatch(AppIntent.UploadSpeed(
                    percent = uploaded.toFloat() / toUpload.size,
                    speedMbps = speedMbps.toFloat(),
                    fileCount = uploaded,
                    elapsedFormatted = String.format("%.1fs", elapsed)
                ))
            }

            dispatch(AppIntent.LogMessage("Upload phase complete. ${pendingAnalysis.size} file(s) queued for analysis."))

            // ═══════════════════════════════════════════════════════════
            //  PHASE 2: Poll all uploaded files for analysis results
            // ═══════════════════════════════════════════════════════════
            if (pendingAnalysis.isNotEmpty()) {
                dispatch(AppIntent.LogMessage("Phase 2: Polling analysis for ${pendingAnalysis.size} file(s)..."))
                var polled = 0
                for ((entry, analysisId) in pendingAnalysis) {
                    container.pauseController.waitIfPaused()
                    polled++
                    dispatch(AppIntent.CurrentProcessingChanged(
                        File(entry.path).name,
                        "Polling analysis ($polled/${pendingAnalysis.size})..."
                    ))
                    pollAnalysis(entry, analysisId, api)
                }
            }

            dispatch(AppIntent.CurrentProcessingChanged(null, null))
            dispatch(AppIntent.UploadCompleted)
        }
    }

    /** Poll VT for analysis results after upload */
    private suspend fun pollAnalysis(
        entry: FileEntry,
        analysisId: String,
        api: VirusTotalApi,
        maxRetries: Int = container.config.analysisMaxRetries
    ) {
        val fileName = File(entry.path).name
        dispatch(AppIntent.LogMessage("  Waiting for analysis of $fileName..."))
        delay(container.config.analysisInitialDelay * 1000L)

        for (attempt in 1..maxRetries) {
            container.pauseController.waitIfPaused()

            dispatch(AppIntent.CurrentProcessingChanged(fileName, "Polling analysis ($attempt/$maxRetries)..."))

            try {
                val result = withContext(Dispatchers.IO) {
                    api.getAnalysisResults(analysisId)
                }

                if (result != null) {
                    val status = VTResponseParser.extractAnalysisStatus(result)
                    if (status == "completed") {
                        val stats = VTResponseParser.extractDetectionStatsFromAnalysis(result)
                        val sha256 = VTResponseParser.extractSha256FromAnalysis(result)
                        val lastDate = System.currentTimeMillis() / 1000

                        val updatedEntry = entry.copy(
                            sha256Hash = sha256,
                            status = FileStatus.ANALYSIS_COMPLETE,
                            analysisUrl = sha256?.let { "${VT_FILE_URL}$it" },
                            detectionRatio = stats,
                            lastAnalysisDate = formatTimestamp(lastDate)
                        )

                        // Cache the analysis result for future scans
                        container.quotaManager.saveEntry(entry.md5Hash ?: "", QuotaManager.CacheEntry(
                            filename = fileName,
                            size = entry.fileSizeBytes,
                            path = entry.path,
                            url = updatedEntry.analysisUrl,
                            lastScan = java.time.LocalDateTime.now().toString(),
                            status = "completed",
                            lastAnalysisDate = lastDate,
                            detections = stats
                        ))

                        dispatch(AppIntent.AnalysisCompleted(entry.path, updatedEntry))
                        return
                    }
                }
            } catch (e: Exception) {
                logger.warn { "Analysis poll error for $fileName: ${e.message}" }
            }

            if (attempt < maxRetries) {
                dispatch(AppIntent.CurrentProcessingChanged(fileName, "Waiting for analysis..."))
                delay(container.config.analysisPollInterval * 1000L)
            }
        }

        dispatch(AppIntent.AnalysisTimeout(entry.path))
    }

    // ═══════════════════════════════════════════════════════════════════
    //  CREDENTIALS
    // ═══════════════════════════════════════════════════════════════════

    fun validateCredentials(apiKey: String, persist: Boolean = true) {
        scope.launch {
            try {
                val valid = VirusTotalApi(apiKey, config = container.config).use { tempApi ->
                    withContext(Dispatchers.IO) { tempApi.validateCredentials() }
                }

                if (valid) {
                    container.updateCredentials(apiKey)
                    if (persist) container.credentialStore.save(apiKey)
                    dispatch(AppIntent.CredentialsValidated(apiKey))
                    fetchQuota()
                } else {
                    dispatch(AppIntent.CredentialsInvalid("Invalid API key. Check your credentials."))
                }
            } catch (e: Exception) {
                dispatch(AppIntent.CredentialsInvalid("Validation failed: ${e.message}"))
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  QUOTA
    // ═══════════════════════════════════════════════════════════════════

    fun fetchQuota() {
        val apiKey = container.apiKey

        if (apiKey == null) {
            // Credentials not yet available — will be retried on validation
            return
        }

        scope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    getUserInfo(apiKey, apiKey, container.config, sharedClient = container.virusTotalApi?.client)
                }

                val quotas = info?.data?.attributes?.quotas
                if (quotas != null) {
                    val daily = quotas.apiRequestsDaily
                    val monthly = quotas.apiRequestsMonthly

                    if (daily != null) {
                        dispatch(AppIntent.QuotaUpdated(
                            daily = QuotaData(used = daily.used, total = daily.allowed),
                            monthly = monthly?.let { QuotaData(used = it.used, total = it.allowed) }
                                ?: QuotaData(0, 0)
                        ))
                    } else {
                        dispatch(AppIntent.QuotaError("No daily quota in response"))
                    }
                } else {
                    dispatch(AppIntent.QuotaError("Unable to fetch"))
                }
            } catch (e: Exception) {
                logger.warn { "Failed to fetch quota: ${e.message}" }
                dispatch(AppIntent.QuotaError("Error fetching"))
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  OPEN FILES IN BROWSER
    // ═══════════════════════════════════════════════════════════════════

    fun openHashedFiles(files: List<FileEntry>) {
        scope.launch {
            val withUrl = files.mapNotNull { it.analysisUrl }
            if (withUrl.isEmpty()) {
                dispatch(AppIntent.LogMessage("No files with analysis URLs to open."))
                return@launch
            }

            if (!Desktop.isDesktopSupported()) {
                dispatch(AppIntent.Error("Desktop browsing not supported on this platform."))
                return@launch
            }

            val desktop = Desktop.getDesktop()
            for (url in withUrl) {
                try {
                    withContext(Dispatchers.Main) { desktop.browse(URI(url)) }
                } catch (e: Exception) {
                    logger.warn { "Failed to open URL $url: ${e.message}" }
                }
            }
            dispatch(AppIntent.LogMessage("Opened ${withUrl.size} file(s) in browser."))
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  COMMANDS
    // ═══════════════════════════════════════════════════════════════════

    fun executeCommand(text: String, currentFiles: List<FileEntry>) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return

        scope.launch {
            container.telemetry.recordCommand(trimmed.split("\\s+".toRegex()).firstOrNull() ?: "unknown")

            when {
                trimmed.equals("help", ignoreCase = true) -> showHelp()
                trimmed.startsWith("check ", ignoreCase = true) -> checkHash(trimmed.removePrefix("check ").trim())
                trimmed.equals("update", ignoreCase = true) || trimmed.equals("u", ignoreCase = true) -> updateFiles(currentFiles)
                trimmed.equals("clear", ignoreCase = true) -> dispatch(AppIntent.ClearList)
                trimmed.startsWith("force", ignoreCase = true) -> forceRecheck(trimmed, currentFiles)
                trimmed.startsWith("remove-green", ignoreCase = true) -> removeGreen(currentFiles)
                trimmed.startsWith("find ", ignoreCase = true) -> {
                    val term = trimmed.removePrefix("find ").trim()
                    dispatch(AppIntent.FindFiles(term))
                }
                trimmed.equals("list", ignoreCase = true) -> listFiles(currentFiles, null)
                trimmed.startsWith("list ", ignoreCase = true) -> listFiles(currentFiles, trimmed.removePrefix("list ").trim())
                trimmed.startsWith("add-ext ", ignoreCase = true) -> addExtension(trimmed.removePrefix("add-ext ").trim())
                trimmed.startsWith("remove-ext ", ignoreCase = true) -> removeExtension(trimmed.removePrefix("remove-ext ").trim())
                trimmed.equals("api", ignoreCase = true) -> showApiKey()
                trimmed.equals("update-quota", ignoreCase = true) -> fetchQuota()
                trimmed.equals("open-red", ignoreCase = true) -> openRedFiles(currentFiles)
                trimmed.equals("stats", ignoreCase = true) -> showStats()
                trimmed.equals("api-swap", ignoreCase = true) -> dispatch(AppIntent.LogMessage("Unknown command. Type 'help' for available commands."))
                else -> dispatch(AppIntent.LogMessage("Unknown command: $trimmed. Type 'help' for available commands."))
            }
        }
    }

    private fun showHelp() {
        val help = buildString {
            appendLine("Available commands:")
            appendLine("  help              — Show this help message")
            appendLine("  check <hash>      — Check a hash on VirusTotal")
            appendLine("  update / u        — Refresh file list from VT")
            appendLine("  clear             — Clear the file list")
            appendLine("  force             — Force recheck all hashes")
            appendLine("  force <hash>      — Force recheck a single hash")
            appendLine("  force-older <date>— Force recheck hashes older than date (YYYY-MM-DD)")
            appendLine("  remove-green      — Remove clean files (0 detections)")
            appendLine("  find <term>       — Search files by name")
            appendLine("  list              — List files grouped by extension")
            appendLine("  list <ext>        — List files matching extension")
            appendLine("  add-ext <ext>     — Add extension to scan config")
            appendLine("  remove-ext <ext>  — Remove extension from scan config")
            appendLine("  api               — Show current API key info")
            appendLine("  update-quota      — Refresh API quota display")
            appendLine("  open-red          — Open malicious/suspicious files in browser")
            appendLine("  stats             — Show local usage statistics")
        }
        dispatch(AppIntent.LogMessage(help.trimEnd()))
    }

    private suspend fun checkHash(hashValue: String) {
        val api = container.virusTotalApi
        if (api == null) {
            dispatch(AppIntent.Error("No API key configured."))
            return
        }

        try {
            val validated = InputValidator.validateHash(hashValue, container.config)
            dispatch(AppIntent.LogMessage("Checking hash: $validated..."))

            val result = withContext(Dispatchers.IO) {
                api.checkFileOnVirusTotal(validated)
            }

            if (result != null) {
                val stats = VTResponseParser.extractDetectionStats(result)
                val sha256 = VTResponseParser.extractSha256(result)
                val lastDate = VTResponseParser.extractLastAnalysisDate(result)

                dispatch(AppIntent.LogMessage(buildString {
                    appendLine("  Hash found on VirusTotal:")
                    appendLine("  SHA256: ${sha256 ?: "N/A"}")
                    appendLine("  Detections: ${stats?.ratio ?: "N/A"}")
                    appendLine("  Last analysis: ${lastDate?.let { formatTimestamp(it) } ?: "N/A"}")
                    appendLine("  URL: https://www.virustotal.com/gui/file/$sha256")
                }.trimEnd()))
            } else {
                dispatch(AppIntent.LogMessage("  Hash not found on VirusTotal."))
            }
        } catch (e: Exception) {
            dispatch(AppIntent.Error(container.errorHandler.handle(e)))
        }
    }

    private suspend fun updateFiles(files: List<FileEntry>) {
        val api = container.virusTotalApi
        if (api == null) {
            dispatch(AppIntent.Error("No API key configured."))
            return
        }

        dispatch(AppIntent.LogMessage("Updating ${files.size} file(s)..."))
        val updated = mutableListOf<FileEntry>()

        for (entry in files) {
            if (entry.md5Hash == null) {
                updated.add(entry)
                continue
            }

            try {
                val hash = entry.md5Hash!!
                val result = withContext(Dispatchers.IO) {
                    api.checkFileOnVirusTotal(hash)
                }

                if (result != null) {
                    val stats = VTResponseParser.extractDetectionStats(result)
                    val sha256 = VTResponseParser.extractSha256(result)
                    val lastDate = VTResponseParser.extractLastAnalysisDate(result)
                    val details = VTResponseParser.extractFileDetails(result)

                    updated.add(entry.copy(
                        status = FileStatus.HASHED_FOUND,
                        sha256Hash = sha256,
                        analysisUrl = sha256?.let { "${VT_FILE_URL}$it" },
                        detectionRatio = stats?.ratio,
                        lastAnalysisDate = lastDate?.let { formatTimestamp(it) }
                    ).withDetails(details))

                    // Persist to cache so re-drops get the fresh data
                    withContext(Dispatchers.IO) {
                        container.quotaManager.saveEntry(hash, buildCacheEntry(entry, sha256, lastDate, stats, details))
                    }
                } else {
                    updated.add(entry.copy(status = FileStatus.HASHED_NOT_FOUND))
                }
            } catch (e: Exception) {
                updated.add(entry)
                dispatch(AppIntent.LogMessage("  Error updating ${entry.fileName}: ${e.message}"))
            }
        }

        dispatch(AppIntent.FilesUpdated(updated))
        dispatch(AppIntent.LogMessage("Update complete."))
    }

    private suspend fun forceRecheck(command: String, files: List<FileEntry>) {
        val api = container.virusTotalApi
        if (api == null) {
            dispatch(AppIntent.Error("No API key configured."))
            return
        }

        val args = command.removePrefix("force").trim()
        val targets = when {
            args.isBlank() -> files.filter { it.md5Hash != null }
            args.startsWith("older ") -> {
                val dateStr = args.removePrefix("older ").trim()
                files.filter { entry -> val date = entry.lastAnalysisDate; date != null && date < dateStr && entry.md5Hash != null }
            }
            else -> {
                val hash = args.trim()
                files.filter { it.md5Hash == hash || it.sha256Hash == hash }
            }
        }

        if (targets.isEmpty()) {
            dispatch(AppIntent.LogMessage("No files to recheck."))
            return
        }

        dispatch(AppIntent.LogMessage("Requesting re-analysis for ${targets.size} file(s)..."))

        for (entry in targets) {
            val hash = entry.sha256Hash ?: entry.md5Hash ?: continue
            try {
                withContext(Dispatchers.IO) {
                    api.requestReanalysis(hash)
                }
                container.pendingRecheckTracker.addPending(
                    entry.path,
                    entry.md5Hash ?: "",
                    entry.lastAnalysisDate?.let { parseDateToEpochSeconds(it) }
                )
                dispatch(AppIntent.FileProcessed(entry.path, entry.copy(
                    status = FileStatus.QUEUED_FOR_RECHECK
                )))
            } catch (e: Exception) {
                dispatch(AppIntent.LogMessage("  Error requesting recheck for ${entry.fileName}: ${e.message}"))
            }
        }

        container.pendingRecheckTracker.setOnPollCallback { pending ->
            pollRechecks(pending)
        }
        container.pendingRecheckTracker.setOnTimerUpdate { remaining, count ->
            dispatch(AppIntent.RecheckTimerTick(remaining, count))
        }
        container.pendingRecheckTracker.startTimer()

        dispatch(AppIntent.LogMessage("Recheck timer started. ${targets.size} file(s) queued."))
    }

    private suspend fun pollRechecks(pending: List<PendingRecheck>) {
        val api = container.virusTotalApi ?: return
        dispatch(AppIntent.LogMessage("Polling recheck results for ${pending.size} file(s)..."))

        for (recheck in pending) {
            // Mark as actively rechecking so UI shows the correct state
            val recheckFile = File(recheck.filePath)
            val recheckingEntry = FileEntry(
                path = recheck.filePath,
                fileName = recheckFile.name,
                fileSizeBytes = if (recheckFile.exists()) recheckFile.length() else 0L,
                fileSizeFormatted = if (recheckFile.exists()) formatFileSize(recheckFile.length()) else "?",
                md5Hash = recheck.md5Hash,
                status = FileStatus.RECHECKING
            )
            dispatch(AppIntent.FileProcessed(recheck.filePath, recheckingEntry))
            try {
                val result = withContext(Dispatchers.IO) {
                    api.checkFileOnVirusTotal(recheck.md5Hash)
                }

                if (result != null) {
                    val lastDate = VTResponseParser.extractLastAnalysisDate(result)
                    // Check if analysis date has changed
                    if (lastDate != recheck.originalAnalysisDate) {
                        val stats = VTResponseParser.extractDetectionStats(result)
                        val sha256 = VTResponseParser.extractSha256(result)
                        val details = VTResponseParser.extractFileDetails(result)
                        val recheckFile = File(recheck.filePath)

                        val updatedEntry = FileEntry(
                            path = recheck.filePath,
                            fileName = recheckFile.name,
                            fileSizeBytes = if (recheckFile.exists()) recheckFile.length() else 0L,
                            fileSizeFormatted = if (recheckFile.exists()) formatFileSize(recheckFile.length()) else "?",
                            md5Hash = recheck.md5Hash,
                            sha256Hash = sha256,
                            status = FileStatus.HASHED_FOUND,
                            analysisUrl = sha256?.let { "${VT_FILE_URL}$it" },
                            detectionRatio = stats?.ratio,
                            lastAnalysisDate = lastDate?.let { formatTimestamp(it) }
                        ).withDetails(details)

                        dispatch(AppIntent.FileProcessed(recheck.filePath, updatedEntry))
                        container.pendingRecheckTracker.clearPending(recheck.md5Hash)
                        dispatch(AppIntent.LogMessage("  Recheck complete: ${updatedEntry.fileName}"))
                    }
                }
            } catch (e: Exception) {
                logger.warn { "Recheck poll error for ${recheck.filePath}: ${e.message}" }
            }
        }

        // Restart timer if there are still pending rechecks
        if (container.pendingRecheckTracker.getPendingCount() > 0) {
            container.pendingRecheckTracker.startTimer()
        } else {
            dispatch(AppIntent.RecheckTimerTick(0, 0))
            dispatch(AppIntent.LogMessage("All rechecks complete."))
        }
    }

    private fun removeGreen(files: List<FileEntry>) {
        val kept = files.filterNot { entry ->
            val ratio = entry.detectionRatio ?: return@filterNot false
            val parts = ratio.split("/")
            val malicious = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return@filterNot false
            malicious == 0
        }
        val removed = files.size - kept.size
        dispatch(AppIntent.FilesUpdated(kept))
        dispatch(AppIntent.LogMessage("Removed $removed clean file(s). ${kept.size} remaining."))
    }

    private fun listFiles(files: List<FileEntry>, extension: String?) {
        if (extension != null) {
            val ext = if (extension.startsWith(".")) extension else ".$extension"
            val matching = files.filter { it.fileName.endsWith(ext, ignoreCase = true) }
            if (matching.isEmpty()) {
                dispatch(AppIntent.LogMessage("No files with extension $ext."))
            } else {
                dispatch(AppIntent.LogMessage(buildString {
                    appendLine("Files with extension $ext (${matching.size}):")
                    matching.forEach { appendLine("  ${it.fileName} — ${it.fileSizeFormatted} — ${it.status.name}") }
                }.trimEnd()))
            }
        } else {
            val byExt = files.groupBy { it.fileName.substringAfterLast('.', "unknown").lowercase() }
            dispatch(AppIntent.LogMessage(buildString {
                appendLine("Files by extension (${files.size} total):")
                byExt.entries.sortedByDescending { it.value.size }.forEach { (ext, entries) ->
                    appendLine("  .$ext: ${entries.size} file(s)")
                }
            }.trimEnd()))
        }
    }

    private fun addExtension(ext: String) {
        try {
            val validated = InputValidator.validateExtension(ext, container.config)
            ExtensionsConfig.addExtension(validated)
            dispatch(AppIntent.LogMessage("Added extension $validated to scan config."))
        } catch (e: Exception) {
            dispatch(AppIntent.Error(container.errorHandler.handle(e)))
        }
    }

    private fun removeExtension(ext: String) {
        try {
            val validated = InputValidator.validateExtension(ext, container.config)
            ExtensionsConfig.removeExtension(validated)
            dispatch(AppIntent.LogMessage("Removed extension $validated from scan config."))
        } catch (e: Exception) {
            dispatch(AppIntent.Error(container.errorHandler.handle(e)))
        }
    }

    private fun showApiKey() {
        val key = container.apiKey
        if (key != null) {
            val masked = key.take(4) + "****" + key.takeLast(4)
            dispatch(AppIntent.LogMessage("API Key: $masked"))
        } else {
            dispatch(AppIntent.LogMessage("No API key configured. Use the credential dialog or set VT_API_KEY."))
        }
    }

    private fun openRedFiles(files: List<FileEntry>) {
        scope.launch {
            val redFiles = files.filter { entry ->
                val ratio = entry.detectionRatio ?: return@filter false
                val parts = ratio.split("/")
                val malicious = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
                malicious > 0
            }

            if (redFiles.isEmpty()) {
                dispatch(AppIntent.LogMessage("No malicious/suspicious files to open."))
                return@launch
            }

            if (!Desktop.isDesktopSupported()) {
                dispatch(AppIntent.Error("Desktop browsing not supported."))
                return@launch
            }

            val desktop = Desktop.getDesktop()
            for (entry in redFiles) {
                entry.analysisUrl?.let { url ->
                    try { withContext(Dispatchers.Main) { desktop.browse(URI(url)) } }
                    catch (e: Exception) { logger.warn { "Failed to open $url" } }
                }
            }
            dispatch(AppIntent.LogMessage("Opened ${redFiles.size} malicious/suspicious file(s) in browser."))
        }
    }

    private fun showStats() {
        val stats = container.telemetry.getStats()
        dispatch(AppIntent.LogMessage(buildString {
            appendLine("Local Statistics:")
            appendLine("  Commands used: ${stats.commandsUsed.size} unique")
            appendLine("  Files scanned: ${stats.filesScanned}")
            appendLine("  Cache hit rate: ${String.format("%.1f%%", container.telemetry.cacheHitRate * 100)}")
            appendLine("  Upload success: ${stats.uploadSuccesses}")
            appendLine("  Upload failures: ${stats.uploadFailures}")
            appendLine("  Sessions: ${stats.sessionsCount}")
        }.trimEnd()))
    }

    // ═══════════════════════════════════════════════════════════════════
    //  HELPERS — Formatting
    // ═══════════════════════════════════════════════════════════════════

    /** Merge FileDetails into a FileEntry (only overwrites null fields). */
    private fun FileEntry.withDetails(details: VTResponseParser.FileDetails?): FileEntry {
        if (details == null) return this
        return copy(
            lastAnalysisStats = details.lastAnalysisStats,
            popularThreatLabel = details.popularThreatLabel,
            typeDescription = details.typeDescription,
            tags = details.tags,
            meaningfulName = details.meaningfulName,
            timesSubmitted = details.timesSubmitted,
            reputation = details.reputation,
            firstSubmissionDate = details.firstSubmissionDate?.let { formatTimestamp(it) },
            lastSubmissionDate = details.lastSubmissionDate?.let { formatTimestamp(it) },
            totalVotes = details.totalVotesHarmless?.let { h ->
                details.totalVotesMalicious?.let { m -> Pair(h, m) }
            }
        )
    }

    /** Build a CacheEntry from a FileEntry + FileDetails, merging in all persisted fields. */
    private fun buildCacheEntry(
        entry: FileEntry,
        sha256: String?,
        lastDate: Long?,
        stats: VTResponseParser.DetectionStats?,
        details: VTResponseParser.FileDetails?
    ): QuotaManager.CacheEntry = QuotaManager.CacheEntry(
        filename = entry.fileName,
        size = entry.fileSizeBytes,
        path = entry.path,
        url = sha256?.let { "${VT_FILE_URL}$it" } ?: entry.analysisUrl,
        lastScan = LocalDateTime.now().toString(),
        status = "found",
        lastAnalysisStats = stats?.description ?: details?.lastAnalysisStats,
        lastAnalysisDate = lastDate,
        detections = stats?.ratio,
        detectionCount = details?.detectionCount,
        suggestedThreatLabel = details?.suggestedThreatLabel,
        typeDescription = details?.typeDescription,
        tags = details?.tags?.joinToString(","),
        meaningfulName = details?.meaningfulName,
        timesSubmitted = details?.timesSubmitted,
        reputation = details?.reputation,
        firstSubmissionDate = details?.firstSubmissionDate,
        lastSubmissionDate = details?.lastSubmissionDate,
        totalVotesHarmless = details?.totalVotesHarmless,
        totalVotesMalicious = details?.totalVotesMalicious
    )


    private fun formatTimestamp(epochSeconds: Long): String {
        return try {
            LocalDateTime.ofInstant(
                Instant.ofEpochSecond(epochSeconds),
                ZoneId.systemDefault()
            ).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        } catch (e: Exception) {
            epochSeconds.toString()
        }
    }

    private fun parseDateToEpochSeconds(dateStr: String): Long? {
        return try {
            LocalDateTime.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                .atZone(ZoneId.systemDefault()).toEpochSecond()
        } catch (e: DateTimeParseException) {
            try {
                LocalDateTime.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                    .atZone(ZoneId.systemDefault()).toEpochSecond()
            } catch (e2: Exception) { null }
        }
    }
}
