# GitHub issue conventions

How issues are filed and tracked in this repo. `AGENTS.md` §"GitHub
Issues" carries the short version; this file is the reference.

## The division of labor

- **Issues are the tracker**: visible, labeled, searchable, one line of
  status each.
- **The files under `docs/` are the contract**: root-cause analyses,
  measured numbers, repro recipes live there (TROUBLESHOOTING.md is the
  established pattern: symptom → root cause → fix, with commit hashes).
  They never live only in an issue body — bodies are snapshots that rot.
  Note `docs/` is gitignored in this repo except the convention docs
  (`docs/*CONVENTIONS.md`): the contract is a local knowledge base, not
  published files.
- Every issue cites its source doc + section; every doc section that has
  an issue carries a `Tracked in [#N]` link back. Update both.

Before filing anything: search the tracker — titles AND bodies, open AND
closed. `gh issue list` only covers open issues by title:

```
gh search issues --repo xorcehub/VT-Batch-FileUploader-Kotlin --match title,body --state open
gh search issues --repo xorcehub/VT-Batch-FileUploader-Kotlin --match title,body --state closed
```

An existing issue with fresh evidence in a comment beats a second
thread — including a CLOSED one: "already filed and closed" is a
different answer from "never filed", and only the search tells you
which.

## Issue structure (fixed, in this order)

```markdown
## What happened
<the problem or gap, standalone — no "see doc" hand-waving>

## Diagnosis
<what the investigation found, grounded in source/docs, with the
source doc named explicitly>

## Steps to reproduce
<minimal, deterministic repro; write "N/A — <reason>" if truly none>

## Version
<commit/branch the problem is present on, or "unknown">

## Environment
<OS, JDK version, Gradle version (`gradlew --version`), and whether the
repro ran against the REAL VirusTotal API (say how many requests/uploads
it burned) or against mocks (`ktor-client-mock`) / saved JSON exports.
Version numbers, not adjectives — "current build" is not a version. If a
field does not apply, write `N/A — <reason>`; never leave it implicit.>

## Evidence
<the two or three lines that make the claim checkable, each paired with
the COMMAND that produced it; the full record lives in the source doc.
Redact API keys — masked form only — and absolute home paths>

## Related issues
<supersedes/duplicates/relates + why this is not a redundant thread>

## Fix applied or workaround
<what was run, WHERE it landed (branch + commit) and WHEN the issue
closes, or "none — this issue is the work item">
```

Field notes:

- **One problem per issue.** Two coupled problems get two issues that
  reference each other, not one bundled body.
- **Titles**: plain and specific, no prefix (`Small:`, `RFC:`, …).
  The labels carry the categorization.
- **Name problems in words, not codes.** No new C1/W6/S13-style
  shorthand for findings, issues, or commits — those codes are LEGACY
  from `docs/review/` and stay there. A title like "cache write loses
  entries when scan is cancelled" outlives its fix; a code needs a
  glossary nobody should have to maintain.
- **`#N` in a body is a cross-reference, always.** GitHub auto-links
  every `#N` to issue/PR #N — write a bare `#N` only for issues/PRs you
  actually mean. Internal numbering (review finding numbers, list
  items) goes in code spans (`` `#1` ``) or plain words.
- **Version** is where the problem is present, not where you noticed it.
  It names the SOURCE state (commit/branch); **Environment** names the
  BITS that ran (JDK, OS, Gradle, real API vs mock).
- **Evidence pairs a measurement with the command that produced it.** A
  line without its command is a claim, not evidence. Keep the body to
  what makes the claim checkable; the full record belongs in the source
  doc, which the body cites.
- **"Fix applied" names the BRANCH and the landing condition.** `Fixes
  #N` closes the issue when the commit reaches `main` (the default
  branch), not when a feature branch is pushed.
- **No attribution lines** anywhere in the body (no "Filed by:",
  agent/model signatures, session IDs).
- **Bodies are per-issue.** A body must restate its own title and carry
  its own repro — never copy a neighbour's body when batch-filing
  findings.

## Labels

- `area:{shared, desktop, cli, ci, docs}` — where the work lives
  (module-based: `shared` = model layer, `desktop` = Compose GUI,
  `cli` = picocli commands, `ci` = workflows/packaging/release)
- `kind:{correctness, performance, ux, process}` — why it matters
  (correctness = wrong behavior/data, incl. cache corruption and quota
  waste; performance = speed/memory, NOT quota — quota waste is
  correctness; ux = GUI/CLI behavior users feel; process =
  build/packaging/bookkeeping)
- `priority:{high,low}` — mutable state, deliberately kept out of titles
  (titles name the problem forever; priority changes weekly) and out of
  hand-maintained tables (they duplicate label state and rot). No
  priority label = normal backlog. The live view is the filter:
  `gh issue list --label priority:high`. Keep `priority:high` to a few
  open issues or it stops meaning anything.
- Legacy `C#/W#/S#` codes live only in `docs/review/*`. Do not create
  labels or titles that continue that numbering.

## Workflow

1. Search the tracker for a match (titles AND bodies, open AND closed);
   comment there instead if one exists and the evidence fits.
2. File with the structure above. Put the body in a file and pass
   `--body-file` — pipes and quoting eat newlines otherwise:

   PowerShell — write the body to `$env:TEMP\body.md` with an editor
   or the Write tool (a here-string's closing `'@` must sit at column 0,
   which does not survive indentation), then:

   ```powershell
   gh issue create --title "..." --label area:shared --label kind:correctness `
     --body-file "$env:TEMP\body.md"
   ```

   Bash:

   ```bash
   cat > /tmp/body.md <<'EOF'
   ## What happened
   ...
   EOF
   gh issue create --title "..." --label area:shared --label kind:correctness \
     --body-file /tmp/body.md
   ```

3. The landing commit cites the issue (`Fixes #N` closes it
   automatically); the source doc gets the full record and a
   `Tracked in [#N]` link in the same commit.
4. A closed issue with new evidence gets reopened or continued in a new
   issue that links back — never a silent comment on a month-old thread.

## Keeping a filed issue true

Bodies rot in one specific way worth policing: they carry a **Version**
that names a commit, and a squash or rebase renames commits. When a
rewrite invalidates the commit a body cites, update the body's Version
field to the commit that exists now, and put the old→new mapping in the
source doc, not in the body. A body edit is normal and expected: the
thread's *claims* stay, their *pointers* get refreshed.
