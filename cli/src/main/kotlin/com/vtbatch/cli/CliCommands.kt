package com.vtbatch.cli

import com.vtbatch.model.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import java.io.File
import java.util.concurrent.Callable

private val logger = KotlinLogging.logger {}

// ═══════════════════════════════════════════════════════════════════════
//  EXIT CODES (matching Python)
// ═══════════════════════════════════════════════════════════════════════

object ExitCodes {
    const val SUCCESS = 0
    const val NO_RESULTS = 1
    const val ERROR = 2
    const val AUTH_ERROR = 3
    const val RATE_LIMIT = 4
    const val NETWORK_ERROR = 5
    const val PARTIAL_SUCCESS = 6
}

/** Map a VT exception to the correct exit code */
fun mapExceptionToExitCode(e: Throwable): Int = when (e) {
    is APIRateLimitError -> ExitCodes.RATE_LIMIT
    is APIConnectionError, is APITimeoutError -> ExitCodes.NETWORK_ERROR
    is java.net.SocketTimeoutException, is java.net.ConnectException, is java.net.UnknownHostException -> ExitCodes.NETWORK_ERROR
    is ConfigurationError -> ExitCodes.AUTH_ERROR
    is VTBatchError -> ExitCodes.ERROR
    else -> ExitCodes.ERROR
}

/** Structured result of API key resolution (Idiom #14) */
data class ApiKeyResolution(val key: String?)

/** Resolve API key from args and env vars */
fun resolveApiKey(cliKey: String?): ApiKeyResolution {
    val key = cliKey
        ?: System.getenv("VT_API_KEY")
        ?: System.getenv("API_KEY")
    return ApiKeyResolution(key)
}

/** Create a VirusTotalApi instance or exit with auth error */
fun createApi(key: String?, out: OutputFormatter, command: String): VirusTotalApi? {
    if (key == null) {
        out.error(command, "No API key provided. Use --api-key or set VT_API_KEY.", "ConfigurationError", ExitCodes.AUTH_ERROR)
        return null
    }
    // Wire the rate limiter so the batch paths (check/upload/scan --hash/reanalyze)
    // respect VT's per-minute cap. Without this the CLI fires unthrottled and
    // burns the free-tier 20 req/min limit -> 429s (which validate even
    // mis-reports as a bad key — see VirusTotalApi.validateCredentials).
    val config = AppConfig.default
    return VirusTotalApi(key, RateLimiter(config.rateLimitPerMinute), config)
}

// ═══════════════════════════════════════════════════════════════════════
//  VALIDATE
// ═══════════════════════════════════════════════════════════════════════

@Command(name = "validate", description = ["Validate VirusTotal API credentials"])
class ValidateCommand : Callable<Int> {
    @ParentCommand lateinit var parent: RootCommand

    override fun call(): Int {
        val out = parent.output
        val (key) = resolveApiKey(parent.apiKey)

        if (key == null) {
            out.error("validate", "No API key provided.", "ConfigurationError", ExitCodes.AUTH_ERROR)
            return ExitCodes.AUTH_ERROR
        }

        return try {
            val api = VirusTotalApi(key)
            val valid = runBlocking { api.validateCredentials() }
            api.close()

            if (valid) {
                out.success("validate", mapOf("valid" to true))
                ExitCodes.SUCCESS
            } else {
                out.error("validate", "Invalid API key or unauthorized.", "AuthenticationError", ExitCodes.AUTH_ERROR)
                ExitCodes.AUTH_ERROR
            }
        } catch (e: Exception) {
            out.error("validate", e.message ?: "Unknown error", "Error", mapExceptionToExitCode(e))
            mapExceptionToExitCode(e)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  SCAN
// ═══════════════════════════════════════════════════════════════════════

@Command(name = "scan", description = ["Scan files/directories for suspicious extensions"])
class ScanCommand : Callable<Int> {
    @ParentCommand lateinit var parent: RootCommand

    @Parameters(paramLabel = "PATH", description = ["Files or directories to scan"], arity = "1..*")
    lateinit var paths: List<String>

    @Option(names = ["--hash"], description = ["Calculate MD5 hashes for found files"])
    var computeHashes: Boolean = false

    @Option(names = ["--no-recursive"], description = ["Do not recurse into subdirectories"])
    var noRecursive: Boolean = false

    @Option(names = ["--extensions"], description = ["Comma-separated extensions to scan (e.g. .exe,.dll,.bat)"], split = ",")
    var extensions: List<String>? = null

    override fun call(): Int {
        val out = parent.output
        val scanner = if (!extensions.isNullOrEmpty()) {
            val extList = extensions
            val extSet = extList!!.map { if (it.startsWith(".")) it.lowercase() else ".${it.lowercase()}" }.toSet()
            FileScanner(extSet)
        } else {
            FileScanner()
        }

        val allFiles = mutableListOf<String>()
        for (path in paths) {
            // --no-recursive: only scan single files and top-level directory contents
            val found = if (noRecursive) {
                val file = File(path)
                when {
                    file.isFile -> scanner.getSuspiciousFiles(path)
                    file.isDirectory -> file.listFiles()?.filter { it.isFile }?.map { it.absolutePath } ?: emptyList()
                    else -> emptyList()
                }
            } else {
                scanner.getSuspiciousFiles(path)
            }
            allFiles.addAll(found)
        }

        if (allFiles.isEmpty()) {
            out.success("scan", mapOf("files" to emptyList<Any>()), mapOf("files_found" to 0))
            return ExitCodes.NO_RESULTS
        }

        val api = if (computeHashes) {
            val (key) = resolveApiKey(parent.apiKey)
            createApi(key, out, "scan") ?: return ExitCodes.AUTH_ERROR
        } else null

        try {
            val fileList = mutableListOf<Map<String, Any?>>()
            var totalSize = 0L

            for (filePath in allFiles) {
                val file = File(filePath)
                val size = if (file.exists()) file.length() else 0L
                totalSize += size

                val entry = mutableMapOf<String, Any?>(
                    "path" to filePath,
                    "size" to size,
                    "extension" to file.extension
                )

                if (computeHashes && api != null) {
                    try {
                        out.progress("Hashing ${file.name}...")
                        entry["md5_hash"] = api.calculateMd5(filePath)
                    } catch (e: Exception) {
                        entry["md5_hash"] = null
                        entry["error"] = e.message
                    }
                }

                fileList.add(entry)
            }

            out.success("scan",
                mapOf("files" to fileList),
                mapOf("files_found" to fileList.size, "total_size_bytes" to totalSize, "total_size_mb" to "%.2f".format(totalSize / (1024.0 * 1024.0)))
            )
            return ExitCodes.SUCCESS
        } finally {
            api?.close()
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  CHECK
// ═══════════════════════════════════════════════════════════════════════

/**
 * Build a fully-populated CacheEntry from a VT file object — same fields
 * as the GUI's buildCacheEntry, so CLI and GUI cache writes are identical
 * for both already-on-VT files and freshly uploaded ones.
 */
private fun fullCacheEntry(
    hash: String,
    filePath: String?,
    fileObj: JsonObject,
    status: String,
    engineHits: List<EngineHit>? = null
): QuotaManager.CacheEntry {
    val details = VTResponseParser.extractFileDetails(fileObj)
    val stats = VTResponseParser.extractDetectionStats(fileObj)
    return QuotaManager.CacheEntry(
        filename = filePath?.let { File(it).name } ?: details?.meaningfulName,
        size = filePath?.let { File(it).length() },
        path = filePath,
        url = VTResponseParser.extractSha256(fileObj)?.let { "https://www.virustotal.com/gui/file/$it" },
        lastScan = java.time.LocalDateTime.now().toString(),
        status = status,
        lastAnalysisStats = stats?.description ?: details?.lastAnalysisStats,
        lastAnalysisDate = VTResponseParser.extractLastAnalysisDate(fileObj),
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
        engineHits = engineHits ?: details?.engineHits
    )
}

@Command(name = "check", description = ["Check a file or hash against VirusTotal"])
class CheckCommand : Callable<Int> {
    @ParentCommand lateinit var parent: RootCommand

    @Option(names = ["--file"], description = ["File to hash and check"])
    var filePath: String? = null

    @Option(names = ["--hash"], description = ["MD5/SHA256 hash to check directly"])
    var hashValue: String? = null

    @Option(names = ["--no-cache"], description = ["Skip local cache"])
    var noCache: Boolean = false

    override fun call(): Int {
        val out = parent.output
        val (key) = resolveApiKey(parent.apiKey)
        val api = createApi(key, out, "check") ?: return ExitCodes.AUTH_ERROR

        return try {
            // Determine hash
            val hash = if (filePath != null) {
                out.progress("Computing hash for $filePath...")
                api.calculateMd5(filePath!!)
            } else if (hashValue != null) {
                InputValidator.validateHash(hashValue!!)
            } else {
                out.error("check", "Provide --file or --hash", "InputValidationError", ExitCodes.ERROR)
                return ExitCodes.ERROR
            }

            // Check cache first
            val cache = QuotaManager()
            if (!noCache) {
                val cached = cache.loadData()[hash]
                if (cached != null) {
                    out.success("check", mapOf(
                        "hash" to hash, "found" to true, "source" to "cache",
                        "filename" to cached.filename, "status" to cached.status,
                        "analysis_url" to cached.url, "detections" to cached.detections,
                        "last_analysis_date" to cached.lastAnalysisDate
                    ))
                    return ExitCodes.SUCCESS
                }
            }

            // Query VT
            out.progress("Querying VirusTotal for $hash...")
            val result = runBlocking { api.checkFileOnVirusTotal(hash) }

            if (result != null) {
                val stats = VTResponseParser.extractDetectionStats(result)
                val sha256 = VTResponseParser.extractSha256(result)
                val lastDate = VTResponseParser.extractLastAnalysisDate(result)

                val data = mapOf<String, Any?>(
                    "hash" to hash, "found" to true, "source" to "virustotal",
                    "analysis_url" to sha256?.let { "https://www.virustotal.com/gui/file/$it" },
                    "detections" to stats?.ratio, "last_analysis_date" to lastDate
                )

                // Save to cache — full field set, same as GUI
                runBlocking {
                    cache.saveEntry(hash, fullCacheEntry(hash, filePath, result, status = "found"))
                }

                out.success("check", data)
                ExitCodes.SUCCESS
            } else {
                out.success("check", mapOf("hash" to hash, "found" to false, "source" to "virustotal"))
                ExitCodes.NO_RESULTS
            }
        } catch (e: Exception) {
            out.error("check", e.message ?: "Unknown error", "Error", mapExceptionToExitCode(e))
            mapExceptionToExitCode(e)
        } finally {
            api.close()
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  UPLOAD
// ═══════════════════════════════════════════════════════════════════════

@Command(name = "upload", description = ["Upload files to VirusTotal"])
class UploadCommand : Callable<Int> {
    @ParentCommand lateinit var parent: RootCommand

    @Parameters(paramLabel = "FILE", description = ["Files to upload"], arity = "1..*")
    lateinit var files: List<String>

    @Option(names = ["--wait"], description = ["Wait for analysis to complete"])
    var wait: Boolean = false

    @Option(names = ["--timeout"], description = ["Max wait seconds for analysis"], defaultValue = "300")
    var timeout: Int = 300

    @Option(names = ["--force", "-f"], description = ["Upload even if file already exists on VT"])
    var force: Boolean = false

    @Option(names = ["--yes", "-y"], description = ["Skip confirmation prompts"])
    var yes: Boolean = false

    override fun call(): Int {
        val out = parent.output
        val (key) = resolveApiKey(parent.apiKey)
        val api = createApi(key, out, "upload") ?: return ExitCodes.AUTH_ERROR

        try {
            // Confirmation prompt for destructive operations
            if (!yes && force) {
                print("Force-upload ${files.size} file(s) without checking VT first? [y/N] ")
                if (readlnOrNull()?.lowercase()?.trim() != "y") {
                    parent.output.error("upload", "Aborted.")
                    return 1
                }
            }

            val results = mutableListOf<Map<String, Any?>>()
            var uploaded = 0
            var skipped = 0
            var failed = 0
            val cache = QuotaManager()

            for (filePath in files) {
                val file = File(filePath)
                if (!file.exists()) {
                    results.add(mapOf("path" to filePath, "uploaded" to false, "error" to "File not found"))
                    failed++
                    continue
                }

                try {
                    // Compute hash
                    out.progress("Hashing ${file.name}...")
                    val md5 = api.calculateMd5(filePath)

                    // Check if already on VT (unless --force)
                    if (!force) {
                        val existing = runBlocking { api.checkFileOnVirusTotal(md5) }
                        if (existing != null) {
                            val sha256 = VTResponseParser.extractSha256(existing)
                            results.add(mapOf(
                                "path" to filePath, "uploaded" to false, "skipped" to true,
                                "reason" to "already_exists", "md5_hash" to md5,
                                "analysis_url" to sha256?.let { "https://www.virustotal.com/gui/file/$it" }
                            ))
                            // Already-fetched file object — cache the full field set,
                            // same as the GUI's hash-found path (no extra API call).
                            runBlocking {
                                cache.saveEntry(md5, fullCacheEntry(md5, filePath, existing, status = "found"))
                            }
                            skipped++
                            continue
                        }
                    }

                    // Upload
                    out.progress("Uploading ${file.name}...")
                    val uploadResult = runBlocking { api.uploadFileToVirusTotal(filePath) }
                    val analysisId = VTResponseParser.extractAnalysisId(uploadResult)
                    val analysisUrl = "https://www.virustotal.com/gui/file-analysis/$analysisId"

                    val entry = mutableMapOf<String, Any?>(
                        "path" to filePath, "uploaded" to true,
                        "status" to "uploaded", "md5_hash" to md5, "analysis_url" to analysisUrl
                    )

                    // Wait for analysis if requested
                    if (wait && analysisId != null) {
                        out.progress("Waiting for analysis of ${file.name}...")
                        val startTime = System.currentTimeMillis()
                        val maxWaitMs = timeout * 1000L

                        while (System.currentTimeMillis() - startTime < maxWaitMs) {
                            val elapsed = (System.currentTimeMillis() - startTime) / 1000
                            print("\r[K  Waiting for analysis... ${elapsed}s elapsed")
                            System.out.flush()

                            val analysis = runBlocking { api.getAnalysisResults(analysisId) }
                            val status = analysis?.let { VTResponseParser.extractAnalysisStatus(it) }

                            if (status == "completed") {
                                println(" done.")
                                val stats = analysis?.let { VTResponseParser.extractDetectionStatsFromAnalysis(it) }
                                entry["status"] = "completed"
                                entry["last_analysis_stats"] = stats

                                // The analysis object is sparse — one GET /files/{sha256}
                                // fetches the full file object so uploaded files cache
                                // the same fields as already-known ones (GUI parity).
                                // ponytail: +1 request per uploaded file; skip on failure.
                                val sha256 = analysis?.let { VTResponseParser.extractSha256FromAnalysis(it) }
                                val fileObj = sha256?.let {
                                    try {
                                        runBlocking { api.checkFileOnVirusTotal(it) }
                                    } catch (_: Exception) { null }
                                }
                                if (fileObj != null) {
                                    val engineHits = analysis?.let { VTResponseParser.extractEngineHitsFromAnalysis(it) }
                                    runBlocking {
                                        cache.saveEntry(md5, fullCacheEntry(md5, filePath, fileObj, "completed", engineHits))
                                    }
                                }
                                break
                            }
                            runBlocking { delay(10000L) } // poll every 10s
                        }

                        if (entry["status"] == "uploaded") {
                            entry["status"] = "timeout"
                        }
                    }

                    results.add(entry)
                    uploaded++
                } catch (e: Exception) {
                    results.add(mapOf("path" to filePath, "uploaded" to false, "error" to (e.message ?: "Unknown")))
                    failed++
                }
            }

            val exitCode = when {
                failed == files.size -> ExitCodes.ERROR
                failed > 0 -> ExitCodes.PARTIAL_SUCCESS
                else -> ExitCodes.SUCCESS
            }

            out.success("upload",
                mapOf("files" to results),
                mapOf("total" to files.size, "uploaded" to uploaded, "skipped" to skipped, "failed" to failed)
            )
            return exitCode
        } finally {
            api.close()
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  REANALYZE
// ═══════════════════════════════════════════════════════════════════════

@Command(name = "reanalyze", description = ["Request re-analysis of a file/hash on VirusTotal"])
class ReanalyzeCommand : Callable<Int> {
    @ParentCommand lateinit var parent: RootCommand

    @Option(names = ["--file"], description = ["File to reanalyze"])
    var filePath: String? = null

    @Option(names = ["--hash"], description = ["Hash to reanalyze"])
    var hashValue: String? = null

    @Option(names = ["--wait"], description = ["Wait for re-analysis to complete"])
    var wait: Boolean = false

    @Option(names = ["--timeout"], description = ["Max wait seconds"], defaultValue = "300")
    var timeout: Int = 300

    override fun call(): Int {
        val out = parent.output
        val (key) = resolveApiKey(parent.apiKey)
        val api = createApi(key, out, "reanalyze") ?: return ExitCodes.AUTH_ERROR

        return try {
            val hash = if (filePath != null) {
                out.progress("Computing hash for $filePath...")
                api.calculateMd5(filePath!!)
            } else if (hashValue != null) {
                hashValue!!
            } else {
                out.error("reanalyze", "Provide --file or --hash", "InputValidationError", ExitCodes.ERROR)
                return ExitCodes.ERROR
            }

            out.progress("Requesting re-analysis for $hash...")
            val result = runBlocking { api.requestReanalysis(hash) }

            if (result == null) {
                out.success("reanalyze", mapOf("hash" to hash, "status" to "not_found"))
                return ExitCodes.NO_RESULTS
            }

            val analysisId = VTResponseParser.extractAnalysisId(result)
            val status = "requested"

            val data = mutableMapOf<String, Any?>(
                "hash" to hash, "status" to status, "analysis_id" to analysisId,
                "analysis_url" to analysisId?.let { "https://www.virustotal.com/gui/file-analysis/$it" }
            )

            // Wait for completion if requested
            if (wait && analysisId != null) {
                out.progress("Waiting for re-analysis...")
                val startTime = System.currentTimeMillis()
                val maxWaitMs = timeout * 1000L

                while (System.currentTimeMillis() - startTime < maxWaitMs) {
                    val analysis = runBlocking { api.getAnalysisResults(analysisId) }
                    val analysisStatus = analysis?.let { VTResponseParser.extractAnalysisStatus(it) }

                    if (analysisStatus == "completed") {
                        data["analysis_status"] = "completed"
                        val stats = analysis?.let { VTResponseParser.extractDetectionStatsFromAnalysis(it) }
                        data["last_analysis_stats"] = stats
                        break
                    }
                    Thread.sleep(10000)
                }

                if (!data.containsKey("analysis_status")) {
                    data["analysis_status"] = "timeout"
                }
            }

            out.success("reanalyze", data)
            ExitCodes.SUCCESS
        } catch (e: Exception) {
            out.error("reanalyze", e.message ?: "Unknown error", "Error", mapExceptionToExitCode(e))
            mapExceptionToExitCode(e)
        } finally {
            api.close()
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  CACHE
// ═══════════════════════════════════════════════════════════════════════

@Command(name = "cache", description = ["Manage local cache"],
    subcommands = [CacheListCommand::class, CacheGetCommand::class, CacheClearCommand::class, CacheStatsCommand::class])
class CacheCommand : Callable<Int> {
    @ParentCommand lateinit var parent: RootCommand

    /** Shared QuotaManager instance for cache subcommands (Idiom #37) */
    val cache = QuotaManager()

    override fun call(): Int {
        parent.output.error("cache", "Specify a cache subcommand: list, get, clear, stats", "InputValidationError", ExitCodes.ERROR)
        return ExitCodes.ERROR
    }
}

@Command(name = "list", description = ["List cached entries"])
class CacheListCommand : Callable<Int> {
    @ParentCommand lateinit var parent: CacheCommand

    @Option(names = ["--limit", "-n"], description = ["Max entries to show"], defaultValue = "50")
    var limit: Int = 50

    override fun call(): Int {
        val out = parent.parent.output
        val entries = parent.cache.loadData()

        val shown = entries.entries.take(limit).map { (hash, entry) ->
            mapOf("hash" to hash, "filename" to entry.filename, "status" to entry.status, "last_scan" to entry.lastScan)
        }

        out.success("cache list",
            mapOf("entries" to shown),
            mapOf("total_entries" to entries.size, "showing" to shown.size)
        )
        return ExitCodes.SUCCESS
    }
}

@Command(name = "get", description = ["Get a cached entry by hash"])
class CacheGetCommand : Callable<Int> {
    @ParentCommand lateinit var parent: CacheCommand

    @Parameters(paramLabel = "HASH", description = ["MD5 hash to look up"])
    lateinit var hash: String

    override fun call(): Int {
        val out = parent.parent.output
        val entries = parent.cache.loadData()
        val entry = entries[hash]

        if (entry != null) {
            out.success("cache get", mapOf(
                "hash" to hash, "found" to true, "filename" to entry.filename,
                "path" to entry.path, "size" to entry.size, "status" to entry.status,
                "url" to entry.url, "last_scan" to entry.lastScan, "detections" to entry.detections
            ))
            return ExitCodes.SUCCESS
        } else {
            out.success("cache get", mapOf("hash" to hash, "found" to false))
            return ExitCodes.NO_RESULTS
        }
    }
}

@Command(name = "clear", description = ["Clear the cache"])
class CacheClearCommand : Callable<Int> {
    @ParentCommand lateinit var parent: CacheCommand

    @Option(names = ["--yes", "-y"], description = ["Skip confirmation prompt"])
    var yes: Boolean = false

    override fun call(): Int {
        val out = parent.parent.output
        if (!yes) {
            print("Clear all cached scan results? [y/N] ")
            if (readlnOrNull()?.lowercase()?.trim() != "y") {
                out.error("cache clear", "Aborted.")
                return 1
            }
        }
        try {
            runBlocking { parent.cache.clearCache() }
        } catch (e: CacheError) {
            out.error("cache clear", e.message ?: "Cache write failed")
            return ExitCodes.ERROR
        }
        out.success("cache clear", mapOf("cleared" to true))
        return ExitCodes.SUCCESS
    }
}

@Command(name = "stats", description = ["Show cache statistics"])
class CacheStatsCommand : Callable<Int> {
    @ParentCommand lateinit var parent: CacheCommand

    override fun call(): Int {
        val out = parent.parent.output
        val entries = parent.cache.loadData()

        val byStatus = entries.values.groupingBy { it.status ?: "unknown" }.eachCount()

        out.success("cache stats",
            mapOf("total_entries" to entries.size, "status_breakdown" to byStatus)
        )
        return ExitCodes.SUCCESS
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  QUOTA
// ═══════════════════════════════════════════════════════════════════════

@Command(name = "quota", description = ["Check VirusTotal API quota"])
class QuotaCommand : Callable<Int> {
    @ParentCommand lateinit var parent: RootCommand

    override fun call(): Int {
        val out = parent.output
        val (key) = resolveApiKey(parent.apiKey)

        if (key == null) {
            out.error("quota", "API key required.", "ConfigurationError", ExitCodes.AUTH_ERROR)
            return ExitCodes.AUTH_ERROR
        }

        return try {
            out.progress("Fetching quota info...")
            // VT API uses the API key itself as the user identifier for quota info
            val info = runBlocking { getUserInfo(key, key) }

            val quotas = info?.data?.attributes?.quotas
            if (quotas != null) {
                val daily = quotas.apiRequestsDaily
                val monthly = quotas.apiRequestsMonthly

                val data = mutableMapOf<String, Any?>(
                    "user_id" to (info.data?.attributes?.let { attrs ->
                        // Extract a meaningful identifier from the API response
                        attrs.toString().take(50)
                    } ?: "unknown")
                )

                val quotasMap = mutableMapOf<String, Any?>()
                if (daily != null) {
                    quotasMap["daily"] = mapOf("used" to daily.used, "allowed" to daily.allowed, "remaining" to (daily.allowed - daily.used))
                }
                if (monthly != null) {
                    quotasMap["monthly"] = mapOf("used" to monthly.used, "allowed" to monthly.allowed, "remaining" to (monthly.allowed - monthly.used))
                }
                data["quotas"] = quotasMap

                out.success("quota", data)
                ExitCodes.SUCCESS
            } else {
                out.error("quota", "Could not retrieve quota information.", "APIResponseError", ExitCodes.ERROR)
                ExitCodes.ERROR
            }
        } catch (e: Exception) {
            out.error("quota", e.message ?: "Unknown error", "Error", mapExceptionToExitCode(e))
            mapExceptionToExitCode(e)
        }
    }
}
