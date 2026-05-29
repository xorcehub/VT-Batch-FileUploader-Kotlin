package com.vtbatch.cli

import picocli.CommandLine
import picocli.CommandLine.*
import java.util.concurrent.Callable

// ═══════════════════════════════════════════════════════════════════════
//  Root command with global options
// ═══════════════════════════════════════════════════════════════════════

@Command(
    name = "vtbatch",
    description = ["VirusTotal Batch File Uploader CLI"],
    version = ["vtbatch 1.0.0"],
    subcommands = [
        ValidateCommand::class,
        ScanCommand::class,
        CheckCommand::class,
        UploadCommand::class,
        ReanalyzeCommand::class,
        CacheCommand::class,
        QuotaCommand::class,
    ]
)
class RootCommand : Callable<Int> {
    @Option(names = ["--api-key"], description = ["VirusTotal API key (or set VT_API_KEY env var)"])
    var apiKey: String? = null

    @Option(names = ["--user"], description = ["VirusTotal username (or set VT_USER env var)"])
    var user: String? = null

    @Option(names = ["--output", "-o"], description = ["Output format: json (default) or text"], defaultValue = "json")
    var outputFormat: String = "json"

    @Option(names = ["--quiet", "-q"], description = ["Suppress progress messages"])
    var quiet: Boolean = false

    @Option(names = ["--verbose", "-v"], description = ["Enable extra debug output"])
    var verbose: Boolean = false

    @Option(names = ["--help", "-h"], description = ["Show help message"], usageHelp = true)
    var helpRequested: Boolean = false

    @Option(names = ["--version"], description = ["Show version"], versionHelp = true)
    var versionRequested: Boolean = false

    /** Lazy-initialized output formatter based on --output flag */
    val output: OutputFormatter by lazy {
        val format = when (outputFormat.lowercase()) {
            "text" -> OutputFormatter.OutputFormat.TEXT
            else -> OutputFormatter.OutputFormat.JSON
        }
        OutputFormatter(format)
    }

    override fun call(): Int {
        // No subcommand given — print help
        CommandLine(this).usage(System.out)
        return 0
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  Entry point
// ═══════════════════════════════════════════════════════════════════════

fun main(args: Array<String>) {
    val exitCode = CommandLine(RootCommand()).execute(*args)
    System.exit(exitCode)
}
