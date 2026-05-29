# VT-Batch-FileUploader-Kotlin

A Kotlin + Compose Multiplatform desktop app for batch scanning and uploading files to [VirusTotal](https://www.virustotal.com/). A rewrite of the [Python/Tkinter version](https://github.com/user/VT-Batch-FileUploader) with a modern UI and identical functionality.

## Features

- **Drag & drop** files or directories for scanning
- **MD5 hashing** with local cache for fast re-scans
- **Batch upload** files not found on VirusTotal
- **Analysis polling** - waits for VT results after upload
- **Recheck timer** - force re-analysis of known hashes
- **15+ commands** via the command input (help, check, force, find, list, stats, etc.)
- **CLI mode** - same commands available from the terminal
- **Quota display** - tracks daily/monthly API usage
- **Pause/resume** - pause processing without losing state

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
   - The credential dialog (click the drop zone and type your key)
   - Environment variable: `VT_API_KEY` and `VT_USER`
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
force              Force recheck all hashes
find <term>        Search files by name
list [ext]         List files by extension
remove-green       Remove clean files (0 detections)
open-red           Open malicious files in browser
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

**Exit codes:** 0=success, 1=no results, 2=error, 3=auth error, 4=rate limit, 5=network error

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

- **AppIntent** - 30 sealed class intents (user actions + async results)
- **AppState** - Single immutable state snapshot
- **AppReducer** - Pure function, no side effects
- **AppStore** - StateFlow holder, coroutine-scoped side effect dispatch
- **SideEffects** - All suspend functions for API calls, file I/O, etc.

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

Environment variables:

| Variable | Description |
|----------|-------------|
| `VT_API_KEY` | VirusTotal API key |
| `VT_USER` | VirusTotal username |
| `VT_SUSPICIOUS_EXTENSIONS` | Comma-separated extensions to scan (e.g. `.exe,.dll,.ps1`) |

## License

MIT