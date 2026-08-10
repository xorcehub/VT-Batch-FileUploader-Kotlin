package com.vtbatch.desktop.mvi

import com.vtbatch.model.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI
import java.time.Instant
import java.time.LocalDate
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

/**
 * Selects which files a `force` recheck command targets. Pure + testable
 * (the live VT API never runs here). [error] is non-null when the cutoff date
 * can't be parsed — the caller surfaces it instead of silently re-checking everything.
 *
 * ponytail: two failure modes fixed here.
 *  1. "force-older <date>" and "force older <date>" normalize to the same args
 *     (removePrefix("force") on the hyphen form leaves a leading '-', stripped here).
 *  2. Dates are compared as EPOCHS, not strings. The old `date < dateStr` made
 *     every "20xx-..." stored date sort before "28-07-26" (char '0' < '8') and
 *     re-checked everything. Accepts the documented YYYY-MM-DD (and YYYY-MM-DD HH:mm);
 *     anything else returns an error so it can't misfire silently.
 */
internal data class ForceRecheckSelection(val targets: List<FileEntry>, val error: String?)

internal fun parseForceRecheckTargets(command: String, files: List<FileEntry>): ForceRecheckSelection {
    val args = command.removePrefix("force").trim().removePrefix("-").trim()
    return when {
        args.isBlank() -> ForceRecheckSelection(files.filter { it.md5Hash != null }, null)
        args.startsWith("older ") -> {
            val dateStr = args.removePrefix("older ").trim()
            val cutoff = parseDateToEpoch(dateStr)
            if (cutoff == null) {
                ForceRecheckSelection(emptyList(), "Could not parse date '$dateStr'. Use YYYY-MM-DD (e.g. 2026-07-28).")
            } else {
                ForceRecheckSelection(
                    files.filter { e ->
                        e.md5Hash != null &&
                        e.lastAnalysisDate != null &&
                        (parseDateToEpoch(e.lastAnalysisDate!!)?.let { it < cutoff } ?: false)
                    },
                    null
                )
            }
        }
        else -> {
            val hash = args.trim()
            ForceRecheckSelection(files.filter { it.md5Hash == hash || it.sha256Hash == hash }, null)
        }
    }
}

/** Parses the stored/entered date formats to epoch seconds, or null if unparseable. */
internal fun parseDateToEpoch(dateStr: String): Long? {
    return try {
        LocalDateTime.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            .atZone(ZoneId.systemDefault()).toEpochSecond()
    } catch (e: DateTimeParseException) {
        try {
            // Date-only: LocalDateTime.parse needs time fields, so parse as LocalDate
            // and anchor at start of day. ("older than 2025-01-15" = before 2025-01-15 00:00.)
            LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                .atStartOfDay(ZoneId.systemDefault())
                .toEpochSecond()
        } catch (e2: Exception) { null }
    }
}

class SideEffects(
    private val container: AppContainer,
    private val dispatch: (AppIntent) -> Unit,
    private val scope: CoroutineScope
) {
    private var scanner = FileScanner()
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
                        val cachedStatus = if (cached.status == "HASHED_NOT_FOUND")
                            FileStatus.HASHED_NOT_FOUND else FileStatus.HASHED_FOUND
                        files.add(FileEntry(
                            path = filePath,
                            fileName = file.name,
                            fileSizeBytes = sizeBytes,
                            fileSizeFormatted = sizeFormatted,
                            md5Hash = md5,
                            status = cachedStatus,
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
                                cached.totalVotesMalicious?.let { m -> Votes(h, m) }
                            },
                            engineHits = cached.engineHits
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
                    val speedMBps = if (elapsed > 0) (totalBytesHashed / (1024.0 * 1024.0)) / elapsed else 0.0
                    dispatch(AppIntent.HashingProgress(
                        percent = pct,
                        speedMBps = speedMBps.toFloat(),
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

                // Auto-refresh stale cache entries in background so scan returns immediately.
                // Only refresh entries older than the configured cache TTL.
                // Recent entries without detection ratio were likely from uploads still
                // being analyzed — no point re-checking them immediately and burning quota.
                val staleThreshold = java.time.LocalDateTime.now().minusHours(container.config.cacheDurationHours.toLong())
                val stale = files.filter { entry ->
                    entry.detectionRatio == null && entry.md5Hash != null
                }.filter { entry ->
                    val cached = cache[entry.md5Hash]
                    // Only refresh if cache entry is older than the configured TTL
                    cached != null && try {
                        java.time.LocalDateTime.parse(cached.lastScan) < staleThreshold
                    } catch (_: Exception) {
                        true // unparseable timestamp → treat as stale
                    }
                }
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
                        // Not found on VT — cache this so we don't burn quota re-checking
                        withContext(Dispatchers.IO) {
                            container.quotaManager.saveEntry(entry.md5Hash!!, QuotaManager.CacheEntry(
                                filename = entry.fileName,
                                size = entry.fileSizeBytes,
                                path = entry.path,
                                lastScan = java.time.LocalDateTime.now().toString(),
                                status = "HASHED_NOT_FOUND"
                            ))
                        }
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
                val speedMBps = if (elapsed > 0) (totalBytesProcessed / (1024.0 * 1024.0)) / elapsed else 0.0
                dispatch(AppIntent.TotalProgress(
                    percent = pct,
                    speedFormatted = "%.1f MB/s".format(speedMBps),
                    fileCount = processed,
                    elapsedFormatted = String.format("%.1fs", elapsed)
                ))
                dispatch(AppIntent.HashingProgress(
                    percent = pct,
                    speedMBps = speedMBps.toFloat(),
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
                                val speedMBps = if (deltaTime > 0) (deltaBytes / (1024.0 * 1024.0)) / deltaTime else 0.0
                                val totalElapsed = (now - fileStartTime) / 1000f

                                dispatch(AppIntent.UploadSpeed(
                                    percent = percent,
                                    speedMBps = speedMBps.toFloat(),
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
                val speedMBps = if (elapsed > 0) (totalBytesUploaded / (1024.0 * 1024.0)) / elapsed else 0.0
                dispatch(AppIntent.TotalProgress(
                    percent = uploaded.toFloat() / toUpload.size,
                    speedFormatted = "%.1f MB/s".format(speedMBps),
                    fileCount = uploaded,
                    elapsedFormatted = String.format("%.1fs", elapsed)
                ))
                dispatch(AppIntent.UploadSpeed(
                    percent = uploaded.toFloat() / toUpload.size,
                    speedMBps = speedMBps.toFloat(),
                    fileCount = uploaded,
                    elapsedFormatted = String.format("%.1fs", elapsed)
                ))
            }

            dispatch(AppIntent.LogMessage("Upload phase complete. ${pendingAnalysis.size} file(s) queued for analysis."))

            // ═══════════════════════════════════════════════════════════
            //  PHASE 2: Poll all uploaded files for analysis results
            // ═══════════════════════════════════════════════════════════
            if (pendingAnalysis.isNotEmpty()) {
                dispatch(AppIntent.LogMessage("Phase 2: Waiting ${container.config.analysisInitialDelay}s for VT to process ${pendingAnalysis.size} file(s)..."))
                delay(container.config.analysisInitialDelay * 1000L)

                dispatch(AppIntent.LogMessage("Phase 2: Polling analysis for ${pendingAnalysis.size} file(s)..."))

                // Round-robin polling: poll all files, then re-poll unfinished ones
                val remaining = pendingAnalysis.toMutableList()
                var round = 0
                val maxRounds = container.config.analysisMaxRetries

                while (remaining.isNotEmpty() && round < maxRounds) {
                    round++
                    val iterator = remaining.iterator()
                    while (iterator.hasNext()) {
                        container.pauseController.waitIfPaused()
                        val (entry, analysisId) = iterator.next()
                        val fileName = File(entry.path).name
                        dispatch(AppIntent.CurrentProcessingChanged(
                            fileName,
                            "Polling round $round ($maxRounds max)..."
                        ))

                        try {
                            val result = withContext(Dispatchers.IO) {
                                api.getAnalysisResults(analysisId)
                            }

                            if (result != null) {
                                val status = VTResponseParser.extractAnalysisStatus(result)
                                if (status == "completed") {
                                    val ratio = VTResponseParser.extractDetectionStatsFromAnalysis(result)
                                    val sha256 = VTResponseParser.extractSha256FromAnalysis(result)
                                    // Per-engine hits come straight from the analysis
                                    // response's `results` map — no extra call needed.
                                    val engineHits = VTResponseParser.extractEngineHitsFromAnalysis(result)
                                    val lastDate = System.currentTimeMillis() / 1000
                                    val detectionStats = ratio?.let { VTResponseParser.DetectionStats(it, it) }

                                    val updatedEntry = entry.copy(
                                        sha256Hash = sha256,
                                        status = FileStatus.ANALYSIS_COMPLETE,
                                        analysisUrl = sha256?.let { "${VT_FILE_URL}$it" },
                                        detectionRatio = ratio,
                                        lastAnalysisDate = formatTimestamp(lastDate),
                                        engineHits = engineHits
                                    )

                                    // status="completed" marks freshly-uploaded files;
                                    // engineHits persist so a re-drop restores them.
                                    container.quotaManager.saveEntry(
                                        entry.md5Hash ?: "",
                                        buildCacheEntry(entry, sha256, lastDate, detectionStats, null)
                                            .copy(status = "completed", engineHits = engineHits)
                                    )

                                    dispatch(AppIntent.AnalysisCompleted(entry.path, updatedEntry))
                                    iterator.remove()
                                }
                            }
                        } catch (e: Exception) {
                            logger.warn { "Analysis poll error for $fileName: ${e.message}" }
                        }
                    }

                    // Wait between rounds (not after the last one)
                    if (remaining.isNotEmpty() && round < maxRounds) {
                        dispatch(AppIntent.CurrentProcessingChanged(null, "Waiting ${container.config.analysisPollInterval}s for next round..."))
                        delay(container.config.analysisPollInterval * 1000L)
                    }
                }

                // Timeout any files that didn't complete
                for ((entry, _) in remaining) {
                    dispatch(AppIntent.AnalysisTimeout(entry.path))
                }
            }

            dispatch(AppIntent.CurrentProcessingChanged(null, null))
            dispatch(AppIntent.UploadCompleted)
        }
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
            } catch (e: java.net.ConnectException) {
                dispatch(AppIntent.CredentialsInvalid("Cannot reach VirusTotal servers. Check your internet connection."))
            } catch (e: java.net.SocketTimeoutException) {
                dispatch(AppIntent.CredentialsInvalid("Connection timed out. VirusTotal servers may be down or your network is unreachable."))
            } catch (e: java.net.UnknownHostException) {
                dispatch(AppIntent.CredentialsInvalid("Cannot resolve VirusTotal hostname. Check your internet connection."))
            } catch (e: APIRateLimitError) {
                // Transient: VT throttled the check — the key may still be valid. Do not
                // report it as invalid (the C1 bug: a 429 was landing in the generic arm
                // below and dispatching CredentialsInvalid, clearing hasCredentials and
                // popping the credential dialog for a working key — worst on startup,
                // which auto-validates a saved key via Main.kt).
                val wait = e.retryAfter?.let { " Retry in ${it.toInt()}s." } ?: ""
                dispatch(AppIntent.CredentialsValidationTransientError(
                    "VirusTotal rate-limited the credential check.$wait Your key may still be valid — please retry."
                ))
            } catch (e: APIResponseError) {
                // Transient server/HTTP error during validation — not evidence of a bad key.
                dispatch(AppIntent.CredentialsValidationTransientError(
                    "Could not validate key (HTTP ${e.statusCode ?: 0}). VirusTotal may be unavailable — please retry."
                ))
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
                    getUserInfo(apiKey, apiKey, container.config, sharedClient = container.virusTotalApi?.sharedClient)
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
    //  EXPORT TO JSON
    // ═══════════════════════════════════════════════════════════════════

    /** Export the current file list (with all VT data + per-engine detections) to JSON. */
    fun exportFiles(files: List<FileEntry>) {
        scope.launch {
            if (files.isEmpty()) {
                dispatch(AppIntent.LogMessage("No files to export."))
                return@launch
            }

            val target = withContext(Dispatchers.IO) { pickExportFile() }
            if (target == null) {
                dispatch(AppIntent.LogMessage("Export cancelled."))
                return@launch
            }

            try {
                val content = withContext(Dispatchers.IO) { FileExporter.toJson(files) }
                withContext(Dispatchers.IO) { target.writeText(content) }
                dispatch(AppIntent.LogMessage("Exported ${files.size} file(s) to ${target.absolutePath}."))
            } catch (e: Exception) {
                val msg = container.errorHandler.handle(e)
                dispatch(AppIntent.Error("Export failed: $msg"))
            }
        }
    }

    /**
     * Show a native "Save As" dialog and return the chosen file.
     * Returns null if the user cancelled. Ensures a .json extension.
     */
    // Compose Desktop renders on an AWT window we don't have a direct handle to
    // from here, so we grab the first heavyweight Frame as the dialog parent.
    private fun pickExportFile(): File? {
        val frame = Frame.getWindows().filterIsInstance<Frame>().firstOrNull()
        val dialog = FileDialog(frame, "Export to JSON", FileDialog.SAVE)
        dialog.file = "vtbatch-export.json"
        dialog.isVisible = true   // modal — blocks until the user picks or cancels

        val dir = dialog.directory ?: return null
        val name = dialog.file ?: return null
        val finalName = if (name.endsWith(".json", ignoreCase = true)) name else "$name.json"
        return File(dir, finalName)
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
                trimmed.equals("export", ignoreCase = true) -> exportFiles(currentFiles)
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
            appendLine("  export            — Export the current file list to JSON")
            appendLine()
            appendLine("Tip: API keys set via VT_API_KEY env var are visible to other users")
            appendLine("     on shared systems. Prefer the credential dialog (AES-encrypted)")
            appendLine("     for better security.")
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

        val selection = parseForceRecheckTargets(command, files)
        if (selection.error != null) {
            dispatch(AppIntent.LogMessage(selection.error))
            return
        }
        val targets = selection.targets

        if (targets.isEmpty()) {
            dispatch(AppIntent.LogMessage("No files to recheck."))
            return
        }

        requestReanalysisFor(targets)
    }

    /**
     * Recheck a single file from its row button: request fresh re-analysis on VT
     * and queue it for polling. Reuses the same path as the `force` command.
     */
    fun recheckFile(entry: FileEntry) {
        scope.launch {
            if (container.virusTotalApi == null) {
                dispatch(AppIntent.Error("No API key configured."))
                return@launch
            }
            if (entry.md5Hash == null && entry.sha256Hash == null) {
                dispatch(AppIntent.LogMessage("Cannot recheck ${entry.fileName}: no hash available."))
                return@launch
            }
            requestReanalysisFor(listOf(entry))
        }
    }

    /** Shared core of `force` and the per-row Recheck button: request re-analysis,
     *  track pending, and start the poll timer. Caller pre-validates API key. */
    private suspend fun requestReanalysisFor(targets: List<FileEntry>) {
        val api = container.virusTotalApi ?: return
        dispatch(AppIntent.LogMessage("Requesting re-analysis for ${targets.size} file(s)..."))

        for (entry in targets) {
            // Flip to QUEUED before any network call so the row's Recheck button
            // disables (spinner) immediately. Otherwise the user can re-fire during
            // the SHA-256 lookup / requestReanalysis round-trip and VT returns 409
            // for the duplicate reanalyse request. entry.copy keeps all existing VT
            // data (detection ratio, URL, engine hits) intact during recheck.
            dispatch(AppIntent.FileProcessed(entry.path, entry.copy(
                status = FileStatus.QUEUED_FOR_RECHECK
            )))

            val md5 = entry.md5Hash
            var sha256 = entry.sha256Hash
            try {
                // VT's reanalyse endpoint requires SHA-256. If we only have MD5 (e.g. from cache),
                // look up the file first to get its SHA-256, then request reanalysis.
                if (sha256 == null && md5 != null) {
                    withContext(Dispatchers.IO) {
                        val report = api.checkFileOnVirusTotal(md5)
                        sha256 = report?.let { VTResponseParser.extractSha256(it) }
                    }
                }
                val hash = sha256 ?: md5 ?: continue
                withContext(Dispatchers.IO) {
                    api.requestReanalysis(hash)
                }
                container.pendingRecheckTracker.addPending(
                    entry.path,
                    md5 ?: "",
                    entry.lastAnalysisDate?.let { parseDateToEpoch(it) }
                )
            } catch (e: Exception) {
                // Revert the optimistic QUEUED state so the row isn't stuck spinning.
                // entry.copy(status = entry.status) restores the original status AND data.
                dispatch(AppIntent.FileProcessed(entry.path, entry.copy(status = entry.status)))
                dispatch(AppIntent.LogMessage("  Error requesting recheck for ${entry.fileName} (hash=${sha256 ?: md5}): ${e.message}"))
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
            // Flip to RECHECKING WITHOUT discarding the row's existing VT data
            // (detection ratio, SHA-256, URL, tags, engine hits). The old code built
            // a bare FileEntry and dispatched FileProcessed, whose reducer does a full
            // replace — wiping everything for the whole recheck window, and permanently
            // if the recheck never completes (this loop has no max-rounds). SetFileStatus
            // merges via copy(), matching requestReanalysisFor's QUEUED_FOR_RECHECK flip.
            dispatch(AppIntent.SetFileStatus(recheck.filePath, FileStatus.RECHECKING))
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
            scanner = scanner.reloadExtensions() // pick up the change without a restart
            dispatch(AppIntent.LogMessage("Added extension $validated to scan config (live)."))
        } catch (e: Exception) {
            dispatch(AppIntent.Error(container.errorHandler.handle(e)))
        }
    }

    private fun removeExtension(ext: String) {
        try {
            val validated = InputValidator.validateExtension(ext, container.config)
            ExtensionsConfig.removeExtension(validated)
            scanner = scanner.reloadExtensions() // pick up the change without a restart
            dispatch(AppIntent.LogMessage("Removed extension $validated from scan config (live)."))
        } catch (e: Exception) {
            dispatch(AppIntent.Error(container.errorHandler.handle(e)))
        }
    }

    private fun showApiKey() {
        val key = container.apiKey
        if (key != null) {
            val masked = key.take(4) + "****" + key.takeLast(4)
            val source = if (System.getenv("VT_API_KEY") != null) "environment variable" else "encrypted file"
            dispatch(AppIntent.LogMessage("API Key: $masked (source: $source)"))
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
                details.totalVotesMalicious?.let { m -> Votes(h, m) }
            },
            engineHits = details.engineHits
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
        totalVotesMalicious = details?.totalVotesMalicious,
        engineHits = details?.engineHits
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

    // ═══════════════════════════════════════════════════════════════════
    //  SETTINGS
    // ═══════════════════════════════════════════════════════════════════

    fun saveSettings(settings: UserSettings) {
        scope.launch {
            try {
                container.settingsStore.save(settings)
                val (newConfig, overridden) = AppConfig.resolve(settings)
                container.updateConfig(newConfig)
                dispatch(AppIntent.SettingsSaved(overridden))
            } catch (e: Exception) {
                val msg = container.errorHandler.handle(e)
                dispatch(AppIntent.SettingsError(msg))
            }
        }
    }
}
