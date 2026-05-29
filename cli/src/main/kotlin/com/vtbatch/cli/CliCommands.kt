package com.vtbatch.cli

import com.vtbatch.model.*
import kotlinx.coroutines.runBlocking
import picocli.CommandLine.*
import java.io.File
import java.util.concurrent.Callable

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
}

/** Map a VT exception to the correct exit code */
fun mapExceptionToExitCode(e: Throwable): Int = when (e) {
    is APIRateLimitError -> ExitCodes.RATE_LIMIT
    is APIConnectionError, is APITimeoutError -> ExitCodes.NETWORK_ERROR
    is ConfigurationError -> ExitCodes.AUTH_ERROR
    is VTBatchError -> ExitCodes.ERROR
    else -> ExitCodes.ERROR
}

/** Resolve API key from args → env vars */
fun resolveApiKey(cliKey: String?, cliUser: String?): Pair<String?, String?> {
    val key = cliKey
        ?: System.getenv("VT_API_KEY")
        ?: System.getenv("API_KEY")
    val user = cliUser
        ?: System.getenv("VT_USER")
        ?: System.getenv("USER")
    return Pair(key, user)
}

/** Create a VirusTotalApi instance or exit with auth error */
fun createApi(key: String?, user: String?, out: OutputFormatter, command: String): VirusTotalApi? {
    if (key == null) {
        out.error(command, "No API key provided. Use --api-key or set VT_API_KEY.", "ConfigurationError", ExitCodes.AUTH_ERROR)
        return null
    }
    return VirusTotalApi(key)
}

// ═══════════════════════════════════════════════════════════════════════
//  VALIDATE
// ═══════════════════════════════════════════════════════════════════════

@Command(name = "validate", description = ["Validate VirusTotal API credentials"])
class ValidateCommand : Callable<Int> {
    @ParentCommand lateinit var parent: RootCommand

    override fun call(): Int {
        val out = parent.output
        val (key, user) = resolveApiKey(parent.apiKey, parent.user)

        if (key == null) {
            out.error("validate", "No API key provided.", "ConfigurationError", ExitCodes.AUTH_ERROR)
            return ExitCodes.AUTH_ERROR
        }

        return try {
            val api = VirusTotalApi(key)
            val valid = runBlocking { api.validateCredentials() }
            api.close()

            if (valid) {
                out.success("validate", mapOf("valid" to true, "user" to (user ?: "unknown")))
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

    override fun call(): Int {
        val out = parent.output
        val scanner = FileScanner()

        val allFiles = mutableListOf<String>()
        for (path in paths) {
            val found = scanner.getSuspiciousFiles(path)
            allFiles.addAll(found)
        }

        if (allFiles.isEmpty()) {
            out.success("scan", mapOf("files" to emptyList<Any>()), mapOf("files_found" to 0))
            return ExitCodes.NO_RESULTS
        }

        val api = if (computeHashes) {
            val (key, _) = resolveApiKey(parent.apiKey, parent.user)
            createApi(key, null, out, "scan") ?: return ExitCodes.AUTH_ERROR
        } else null

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

        api?.close()

        out.success("scan",
            mapOf("files" to fileList),
            mapOf("files_found" to fileList.size, "total_size_bytes" to totalSize, "total_size_mb" to String.format("%.2f", totalSize / (1024.0 * 1024.0)))
        )
        return ExitCodes.SUCCESS
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  CHECK
// ═══════════════════════════════════════════════════════════════════════

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
        val (key, _) = resolveApiKey(parent.apiKey, parent.user)
        val api = createApi(key, null, out, "check") ?: return ExitCodes.AUTH_ERROR

        return try {
            // Determine hash
            val hash = if (filePath != null) {
                out.progress("Computing hash for $filePath...")
                api.calculateMd5(filePath!!)
            } else if (hashValue != null) {
                hashValue!!
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
                        "last_analysis_date" to cached.last_analysis_date
                    ))
                    api.close()
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

                val data = mutableMapOf<String, Any?>(
                    "hash" to hash, "found" to true, "source" to "virustotal",
                    "analysis_url" to sha256?.let { "https://www.virustotal.com/gui/file/$it" },
                    "detections" to stats?.second, "last_analysis_date" to lastDate
                )

                // Save to cache
                cache.saveEntry(hash, QuotaManager.CacheEntry(
                    filename = filePath?.let { File(it).name },
                    path = filePath,
                    url = data["analysis_url"] as? String,
                    last_scan = java.time.LocalDateTime.now().toString(),
                    status = "found",
                    last_analysis_date = lastDate,
                    detections = stats?.second
                ))

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

    override fun call(): Int {
        val out = parent.output
        val (key, _) = resolveApiKey(parent.apiKey, parent.user)
        val api = createApi(key, null, out, "upload") ?: return ExitCodes.AUTH_ERROR

        val results = mutableListOf<Map<String, Any?>>()
        var uploaded = 0
        var skipped = 0
        var failed = 0

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
                        val analysis = runBlocking { api.getAnalysisResults(analysisId) }
                        val status = analysis?.let { VTResponseParser.extractAnalysisStatus(it) }

                        if (status == "completed") {
                            val stats = analysis?.let { VTResponseParser.extractDetectionStatsFromAnalysis(it) }
                            entry["status"] = "completed"
                            entry["last_analysis_stats"] = stats
                            break
                        }
                        Thread.sleep(10000) // poll every 10s
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

        api.close()

        val exitCode = when {
            failed == files.size -> ExitCodes.ERROR
            failed > 0 -> ExitCodes.NO_RESULTS  // partial success
            else -> ExitCodes.SUCCESS
        }

        out.success("upload",
            mapOf("files" to results),
            mapOf("total" to files.size, "uploaded" to uploaded, "skipped" to skipped, "failed" to failed)
        )
        return exitCode
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
        val (key, _) = resolveApiKey(parent.apiKey, parent.user)
        val api = createApi(key, null, out, "reanalyze") ?: return ExitCodes.AUTH_ERROR

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
        val cache = QuotaManager()
        val entries = cache.loadData()

        val shown = entries.entries.take(limit).map { (hash, entry) ->
            mapOf("hash" to hash, "filename" to entry.filename, "status" to entry.status, "last_scan" to entry.last_scan)
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
        val cache = QuotaManager()
        val entries = cache.loadData()
        val entry = entries[hash]

        if (entry != null) {
            out.success("cache get", mapOf(
                "hash" to hash, "found" to true, "filename" to entry.filename,
                "path" to entry.path, "size" to entry.size, "status" to entry.status,
                "url" to entry.url, "last_scan" to entry.last_scan, "detections" to entry.detections
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

    override fun call(): Int {
        val out = parent.parent.output
        val cache = QuotaManager()
        cache.clearCache()
        out.success("cache clear", mapOf("cleared" to true))
        return ExitCodes.SUCCESS
    }
}

@Command(name = "stats", description = ["Show cache statistics"])
class CacheStatsCommand : Callable<Int> {
    @ParentCommand lateinit var parent: CacheCommand

    override fun call(): Int {
        val out = parent.parent.output
        val cache = QuotaManager()
        val entries = cache.loadData()

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
        val (key, user) = resolveApiKey(parent.apiKey, parent.user)

        if (key == null || user == null) {
            out.error("quota", "API key and username required.", "ConfigurationError", ExitCodes.AUTH_ERROR)
            return ExitCodes.AUTH_ERROR
        }

        return try {
            out.progress("Fetching quota info...")
            val info = runBlocking { getUserInfo(key, user) }

            val quotas = info?.data?.attributes?.quotas
            if (quotas != null) {
                val daily = quotas.api_requests_daily
                val monthly = quotas.api_requests_monthly

                val data = mutableMapOf<String, Any?>(
                    "user_id" to (info.data?.attributes?.let { "user" } ?: "unknown")
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
