# VT-Batch-FileUploader-Kotlin

A Kotlin + Compose Multiplatform desktop app for batch scanning and uploading files to [VirusTotal](https://www.virustotal.com/). A rewrite of the private [Python/Tkinter version](https://github.com/xorcehub/VT-Batch-FileUploader) with a modern UI and identical functionality.

Checking a folder of downloads or attachments one file at a time on VirusTotal is tedious. This tool automates the workflow: drop a whole directory, and it hashes, checks, and uploads only the unknowns in one pass — with a local cache so re-scans of known files cost zero API calls.

![VT-Batch-FileUploader-Kotlin screenshot](media/Screenshot%202026-07-28%20152047.png)

## Disclaimer

- **Scores aren't verdicts.** A 0-detection file isn't guaranteed safe (it may simply be new or evasive), and a 1–2 detection file isn't automatically malicious (engines false-positive legitimate software). Use results for triage; sandbox anything suspicious before running it.
- **Respect the VirusTotal Terms of Service and public-API limits** (500 requests/day, 4 requests/minute). Multiple API keys are for switching between valid accounts — not for circumventing quotas.

## Features

- **Drag & drop** files or directories for scanning
- **Local cache** for fast re-scans
- **Recheck timer** - force re-analysis of known hashes
- **Filter bar** - chip filters by extension and verdict status; list, find, export, and open-red act on the filtered view
- **Per-row actions** - recheck or remove a single file without touching the rest
- **Find navigation** - `find` scrolls to and highlights matches; PageUp/PageDown cycles through them
- **15+ commands** via the text input (help, check, force, find, list, stats, export, etc.)
- **CLI mode** - same commands available from the terminal
- **Quota display** - tracks daily/monthly API usage
- **Pause/resume** - pause processing without losing state
- **Magic-byte detection** - catches extensionless executables
- **Expandable detail panel** - per-engine AV analysis for each file
- **JSON export** - export the file list with per-engine AV detections
- **Settings dialog** - configure poll intervals, cache TTL, and retries (saved to `settings.json`)

## Prerequisites

- **JDK 21+** (tested with Eclipse Temurin 25)
- **Windows 11** (primary target; macOS/Linux packaging config included)

## Build

```powershell
# Set JAVA_HOME (PowerShell)
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot"

# Build everything
.\gradlew.bat build

# Run tests
.\gradlew.bat test
```

## Usage

### Desktop GUI

```powershell
.\gradlew.bat :desktop:run
```

1. Set your VirusTotal API key via:
   - The credential dialog (keys are stored AES-encrypted at rest in `~/.vtbatch/credentials`)
   - Environment variable: `VT_API_KEY`
2. Drag files/folders onto the drop zone
3. Click **Start** to hash and check files against VT
4. Click **Upload** to send unknown files to VT
5. Click **Open Hashed** to open analysis results in your browser

### Commands (in-app or CLI)

Type commands in the text input field or use the CLI:

```
help               Show all commands
check <hash>       Check a hash on VirusTotal
update             Refresh file list from VT
clear              Clear the file list
force [hash]       Force recheck all hashes, or a single hash
force-older <date> Force recheck hashes older than a date (YYYY-MM-DD)
find <term>        Search files by name
list [ext]         List files by extension
remove-green       Remove clean files (0 detections)
open-red           Open malicious files in browser
add-ext <ext>      Add extension to scan config
remove-ext <ext>   Remove extension from scan config
api                Show current API key info
update-quota       Refresh API quota display
export             Export file list to JSON
stats              Show local usage statistics
```

### CLI

```powershell
# Validate credentials
.\gradlew.bat :cli:run --args="validate --api-key YOUR_KEY"

# Scan a directory
.\gradlew.bat :cli:run --args="scan --hash C:\Users\you\Downloads"

# Check a hash
.\gradlew.bat :cli:run --args="check --hash abc123def456..."

# Upload files
.\gradlew.bat :cli:run --args="upload --wait file1.exe file2.dll"

# Text output mode
.\gradlew.bat :cli:run --args="--output text cache stats"

# Cache management
.\gradlew.bat :cli:run --args="cache list"
.\gradlew.bat :cli:run --args="cache get <hash>"
.\gradlew.bat :cli:run --args="cache clear"
```

**Exit codes:** 0=success, 1=no results, 2=error, 3=auth error, 4=rate limit, 5=network error, 6=partial success (some files failed)

## Architecture

```
VT-Batch-FileUploader-Kotlin/
├── shared/          Core business logic (model layer)
│   └── model/       AppConfig, VirusTotalApi, FileScanner, RateLimiter,
│                    QuotaManager, FileStateManager, VTResponseParser, etc.
├── desktop/         Compose Multiplatform GUI
│   └── mvi/         MVI architecture: AppIntent → AppReducer → AppState
│                    AppStore (StateFlow), SideEffects (coroutines)
│   └── ui/          Compose components + Material3 theme
├── cli/             Command-line interface (picocli)
│   └── commands/    validate, scan, check, upload, reanalyze, cache, quota
└── build.gradle.kts
```

### MVI Pattern

The desktop app uses **Model-View-Intent (MVI)**:

```
User action → Intent → Reducer(oldState, intent) → newState → UI observes
                                    ↑
                              may trigger side effects (API, file I/O)
                              which emit further intents with results
```

- **AppIntent** - sealed-class intents (user actions + async results)
- **AppState** - Single immutable state snapshot
- **AppReducer** - Pure function, no side effects
- **AppStore** - StateFlow holder, coroutine-scoped side effect dispatch
- **SideEffects** - All suspend functions for API calls, file I/O, etc.

### Hash-first workflow

The core design: **never upload before checking the hash.** Hash lookups against VT are cheap and rate-friendly; uploads are the expensive, quota-burning operation. The flow is *hash → lookup → upload only if unknown*, and the local JSON cache extends this across sessions.

## Packaging

```powershell
# Windows MSI installer
.\gradlew.bat :desktop:packageMsi

# macOS DMG
.\gradlew.bat :desktop:packageDmg

# Linux DEB/RPM
.\gradlew.bat :desktop:packageDeb
.\gradlew.bat :desktop:packageRpm
```

## Tech Stack

| Concern | Library |
|---------|---------|
| Language | Kotlin 2.1 |
| UI | Compose Multiplatform 1.8 (Desktop) |
| Material | Material3 |
| HTTP | Ktor + OkHttp engine |
| JSON | kotlinx.serialization |
| Async | kotlinx.coroutines |
| CLI | Picocli |
| Build | Gradle 9.5 + Kotlin DSL |
| Logging | kotlin-logging + SLF4J + Logback |

## Configuration

Tunables follow a priority chain: **hardcoded default → `settings.json` → env var (highest)**. `settings.json` can be edited from the in-app Settings dialog.

`settings.json` fields (all optional, stored in `~/.vtbatch/`):

| Field | Description |
|-------|-------------|
| `analysisPollInterval` | Seconds between VT analysis polls |
| `analysisInitialDelay` | Seconds before first poll |
| `analysisMaxRetries` | Max polling attempts |
| `cacheDurationHours` | Local cache TTL in hours |
| `shortTimeout` | Short-request timeout in seconds |

Environment variables:

| Variable | Description |
|----------|-------------|
| `VT_API_KEY` | VirusTotal API key |
| `VT_ANALYSIS_POLL_INTERVAL` | Seconds between VT analysis polls |
| `VT_SUSPICIOUS_EXTENSIONS` | Comma-separated extensions to scan (e.g. `.exe,.dll,.ps1`) |

## License

MIT