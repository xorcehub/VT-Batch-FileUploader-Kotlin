# VT-Batch-FileUploader-Kotlin — Best Practices for AI Agents

Kotlin + Compose Multiplatform desktop app for batch scanning/uploading files
to VirusTotal. Gradle multi-module: `shared` (model layer), `desktop` (MVI GUI),
`cli` (picocli). Windows 11 is the primary target; CI builds/tests on
Windows/macOS/Linux with JDK 21.

## Module Map and Seams

```
shared/   Core business logic: VirusTotalApi, FileScanner, RateLimiter,
          QuotaManager, FileStateManager, VTResponseParser, CredentialStore,
          SettingsStore, LocalTelemetry, PauseController
desktop/  Compose MVI GUI: AppIntent → AppReducer → AppState, AppStore,
          SideEffects (all suspend API/file work), ui/components
cli/      picocli commands: validate, scan, check, upload, reanalyze,
          cache, quota — must stay in PARITY with the GUI
```

The load-bearing seams (where changes break silently — see
[`docs/REVIEW_CONVENTIONS.md`](docs/REVIEW_CONVENTIONS.md)):

- **shared → desktop and shared → cli.** Anything added to a shared model
  class has TWO consumers. A cache-entry field added for the GUI but not
  surfaced in the CLI (or the e2e REQUIRED list in
  `scripts/test_cli_e2e.py`) is a parity bug — this exact bug was the
  subject of "feat(cli): full cache-entry parity with GUI + e2e test".
- **GUI ↔ CLI cache-entry parity** is a maintained invariant, enforced by
  the e2e script's `REQUIRED` field list, not by unit tests.
- **AppReducer → SideEffects.** Reducers are pure; all API/file I/O lives
  in `SideEffects` as suspend functions that dispatch result intents.
- **RateLimiter ↔ PauseController ↔ cancellation.** See the coroutines
  footgun below.
- **Settings chain:** hardcoded default → `settings.json` → env var
  (highest). A setting does not need every layer, but where an env var
  exists it must win.

## Build and Test

The project is developed on Windows; use `gradlew.bat` in PowerShell.
CI (`.github/workflows/build.yml`) is the reference: JDK 21 Temurin,
`./gradlew build` then `./gradlew test` on all three OSes.

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot"

.\gradlew.bat build          # compile everything
.\gradlew.bat test           # unit tests (kotlin-test, JVM)
.\gradlew.bat :desktop:run   # run the GUI
.\gradlew.bat :cli:run --args="--help"
.\gradlew.bat :cli:installDist   # builds the CLI the e2e script invokes
```

- **Run the full test suite before handing over.** Behavior changes in
  `shared/` must ship with tests next to the change
  (`shared/src/test/...`), and reducer changes belong in `AppReducerTest`
  — reducer purity is what makes it unit-testable; do not move logic
  out of reach of those tests.
- A green build on Windows is not proof: CI also builds macOS/Linux.
  Code touching files, paths, or processes must work on all three
  (CI will catch it — but check before pushing).

## Releases

Pushes to `main` run build + test only. Installers are published two ways:
the `msi` job (manual "Run workflow" on `main`) rebuilds the rolling
`latest` release, and pushing a `v*` tag publishes a permanent versioned
release.

- Land work via **feature branches + PRs** (that is the repo's pattern:
  `ui/polish-ui`, `fix/ratelimit-credentials-scanner-recheck`, …).
- Never push to `main`, push a `v*` tag, or trigger the `msi` workflow
  without an explicit ask. Merging the PR is the maintainer's call.
- The installer version is `0.0.<run_number>` — monotonic so installs
  upgrade cleanly. Do not fight this scheme in a PR.

## VirusTotal Quota and API-Key Discipline

Free tier: **500 requests/day shared between lookups and uploads,
4 requests/minute**, counted per REQUEST (file size is irrelevant).
The whole app is designed around not wasting them — the hash-first
flow: *hash → cache lookup → upload only if unknown*.

- **Never break the hash-first flow.** Any change that adds an API call
  must say where its requests come from and respect the RateLimiter.
- **Never log, echo, commit, or paste an API key.** Keys are
  AES-encrypted at `~/.vtbatch/credentials` or come from `VT_API_KEY`.
  Redact keys from issues, docs, logs, and test fixtures — use the
  masked form from `SecureApiKey` (`abc***...***xyz`), never the secret.
- **The e2e test burns real quota**: `scripts/test_cli_e2e.py` uses the
  REAL VirusTotal API (~6 requests + 2 uploads), needs `VT_API_KEY` and
  `:cli:installDist` first. Run it only when the change warrants it, and
  say in the handover that it ran and how many requests it cost.
  Unit tests use `ktor-client-mock` — prefer them; they cost nothing.
- Changes to `RateLimiter`/`QuotaManager` are high-stakes: a regression
  here gets the user's key rate-limited or banned. Test the sliding
  window math in `RateLimiterTest` before and after.

## Coroutines Pitfalls

**Never acquire a suspending Mutex inside `finally`.** If the coroutine
is cancelled during a `delay`, a `mutex.lock()` in `finally` throws
`CancellationException`, and `withLock`'s cleanup then unlocks a mutex
no longer held → `IllegalStateException("Mutex is not locked")`. Compute
under the lock, suspend outside it — `RateLimiter.acquire` is the
worked example (fix commit `4051afd`).

General rules:

- Suspend, don't block: no `Thread.sleep` / blocking I/O inside
  coroutines; use `delay` and suspend APIs.
- Cancellation is part of every suspend path. Pause loops
  (`PauseController`) must remain cancellation-responsive.
- Shared mutable state across coroutines needs `Mutex`, `@Volatile`, or
  confinement — `LocalTelemetry.data @Volatile` was a real fix (`2a30e9c`).

## Cache and Persistence

- The cache JSON (default `~/.vtbatch/vt_scan_data.json`, override via
  `VT_CACHE_FILENAME`) persists BOTH MD5 and SHA256, plus the full
  entry field set. Do not narrow the field set — GUI, CLI, and the e2e
  `REQUIRED` list all depend on it.
- **Cache/telemetry writes stay atomic and batched** (writeAtomically,
  batched saveEntry loops — fix `7fa764b`). A per-entry rewrite loop
  that crashes mid-run loses data; do not regress this.
- Never upload before checking the hash — that ordering IS the product.

## CLI Contract

Exit codes are an API for scripts: 0=success, 1=no results, 2=error,
3=auth error, 4=rate limit, 5=network error, 6=partial success.
Changes to exit codes, command names, or flags are breaking changes —
call them out explicitly.

## Blind-Spot Probing

Systems are specified by what they DO; bugs cluster in what they
silently DON'T do. When finishing work or reviewing:

1. List what the change EXCLUDES. For each excluded case: loud error, or
   silence? Silence is a finding — record it with a repro.
2. Grep for silent fallbacks (`catch (e: Exception)`, `?: default`,
   `runCatching`). Every default swallows some input forever — make sure
   it is an input you chose. (Worked example: `validateCredentials`
   catching everything into "invalid API key" hid real 429s — fix `fd76f86`.)
3. What do all your tests have in common — one happy path, one file
   type, one small file? Probe one case without that property
   (empty dir, >32 MB file, extensionless executable, duplicate hash).
4. Suspect handoffs: GUI state ↔ CLI output, cache file ↔ parser,
   settings.json ↔ env var precedence, scanner → uploader.

## Debugging

- `docs/TROUBLESHOOTING.md` is the root-cause record — check it first,
  and ADD your diagnosis after fixing (symptom → root cause → fix).
- CrashLogger captures startup crashes; logs go through
  kotlin-logging/SLF4J, not println.
- Repro against the real API is slow and quota-limited; reproduce with
  `ktor-client-mock` responses or saved JSON exports first.

## Git Workflow

Short version in [`docs/GIT_WORKFLOW_CONVENTIONS.md`](docs/GIT_WORKFLOW_CONVENTIONS.md);
that file is the reference. Load-bearing rules:

- The branch's history is the maintainer's — never `merge`, `rebase`,
  `reset --hard`, `push --force`, or ref deletion without an explicit
  ask that NAMES the operation. Prepare alternatives on a scratch
  worktree; keep the current state reachable via a `backup/...` ref.
- Land work as feature branches + PRs merged via GitHub. Avoid rebasing
  a pushed branch: docs and review findings cite commit hashes
  (e.g. TROUBLESHOOTING cites `f00e6a0`), and a rebase renames them.
- `docs/` is a **local knowledge base**: everything there is gitignored
  except the convention docs (`docs/*CONVENTIONS.md`). Do not
  `git add -f` the rest.
- **Do not stage your own edits.** If a file already has staged changes,
  your new changes to it stay unstaged, so the maintainer can review
  `git diff --cached` (earlier work) and `git diff` (your newest change)
  separately.
- Convention docs (this file, `docs/*CONVENTIONS.md`) are drafted by
  the agent but **landed by the maintainer**: leave edits in the tree
  and ask — never commit or push them on your own initiative.
- Commit subjects: `type(scope): subject` (`fix(desktop):`, `feat(cli):`,
  `docs:`) or a plain specific sentence; no shorthand codes.

## GitHub Issues

Issues are the TRACKER; `docs/` is the CONTRACT — root-cause analyses,
measurements, and repro recipes live in docs and never only in an issue
body. File per [`docs/ISSUE_CONVENTIONS.md`](docs/ISSUE_CONVENTIONS.md).
The load-bearing rules:

- Structure: What happened / Diagnosis / Steps to reproduce / Version /
  Environment / Evidence / Related issues / Fix applied or workaround.
- One problem per issue; plain, specific title, no prefix.
- Name the problem in words — do NOT coin shorthand codes. The C#/W#/S#
  codes in `docs/review/*` are LEGACY from those review docs; don't
  imitate them in new issues or commit messages.
- Search before filing — titles AND bodies, open AND closed
  (`gh search issues --repo xorcehub/VT-Batch-FileUploader-Kotlin
  --match title,body`).
- Labels: `area:{shared,desktop,cli,ci,docs}` ×
  `kind:{correctness,performance,ux,process}`. Priority lives in
  `priority:{high,low}` labels, never in titles. No label = normal
  backlog. An agent may PROPOSE a priority in the body; the maintainer
  sets the label.
- Environment carries version NUMBERS (commit, JDK, OS, Gradle) and
  states whether the repro hit the real API (quota burned) or mocks.
- NO attribution lines (no "Filed by:" / agent signatures).
- The landing commit cites the issue (`Fixes #N` in the commit that
  reaches `main` closes it).
