# Review conventions: three passes, and the seams between them

How changes are reviewed in this repo. `AGENTS.md` §"Blind-Spot
Probing" carries the short version; this file is the reference.
This repo already has a review culture — the dated reports under 
`docs/review/` (lead pass + independent reviewer verification) — 
and this file extends it with the pass structure the reports were
missing.

## The division of labor

- The **author** runs the three passes below and hands over the EVIDENCE
  (the commands and their output), not a summary.
- The **reviewer** re-runs the passes — and adds the seam pass with
  fresh eyes, because the author's seam pass is the one most likely to
  share the author's blind spot.
- The **maintainer** decides what lands.

## Why three passes and not one

A review that walks the happy path answers "does it work". A review that
walks the feature's own dark-spot list answers "does it fail loudly
where it should". **Neither finds the bugs that live in the SEAMS** —
the handoffs where one component's output becomes another component's
input, and the two sides quietly disagree about what that output MEANS.

This repo's own history is the worked example: the CLI shipped cache
entries missing fields the GUI wrote ("full cache-entry parity with GUI"
landed as a fix, not as a design), and `validateCredentials` collapsed
rate-limits into "invalid API key" — both were handoff bugs, no single
component was wrong.

## Pass 1 — the happy path

Build and run the feature's own acceptance gates and READ THE OUTPUT,
not the exit code: `gradlew.bat build`, `gradlew.bat test`, then
exercise the changed path in the GUI (`:desktop:run`) or CLI
(`:cli:run`). If the change touches cache shape, CLI output, or quota
behavior, the e2e script is the gate — say whether it ran and what
quota it burned.

**What this pass cannot see:** anything that only misbehaves when two
parts meet. A happy path exercises one side of every seam.

## Pass 2 — the dark spots

The change's own list of shapes it might get wrong. For each one, two
questions, and the second is the one usually skipped:

1. Is the failure loud?
2. Does the message name the RIGHT cause? A failure that says "invalid
   API key" when the real cause is a 429 (fix `fd76f86`), or "validation
   failed" when the real cause is a network timeout (TROUBLESHOOTING.md
   §credential dialog), sends the next reader to file against the wrong
   component.

Then the list-completeness check: for every case the change makes
REACHABLE, is there a test? A reachable case with no gate is a gap found
by reading, not by running — write it down.

**What this pass cannot see:** a shape the list does not mention — which
is every shape that lives in a seam.

## Pass 3 — the seams

A seam is a handoff: one component's output becomes another component's
input. The seams of this codebase, and the question each one asks:

| the seam | the two sides | the question to ask |
|---|---|---|
| shared model → GUI and CLI | `shared/` classes → `desktop/mvi` + `cli/commands` | does the change surface in BOTH consumers (and the e2e `REQUIRED` list), or only the one the author was testing? |
| reducer → side effects | `AppReducer` (pure) → `SideEffects` (suspend) | did state-bearing logic leak into a side effect, or I/O leak into the reducer? Can the reducer test still pin the behavior? |
| RateLimiter → cancellation | `RateLimiter.acquire` → coroutine cancellation / `PauseController` | cancel mid-wait: is every lock released, every status consistent, no `Mutex` acquired in a `finally`? |
| API response → parser → cache | `VirusTotalApi` → `VTResponseParser` → `FileStateManager` → cache JSON | does a field the parser accepts survive the round-trip to the cache file and back? Do MD5 AND SHA256 persist? |
| cache file → re-scan | cache JSON → `FileStateManager` lookup | is a stale entry (TTL expired) treated differently from a missing one, loudly? |
| settings chain | hardcoded default → `settings.json` → env var | where the setting has an env var, does the env var actually win over `settings.json`? |
| scanner → uploader | `FileScanner` (magic bytes, extensions) → upload path | can the scanner admit a file the uploader then mishandles (>32 MB, extensionless executable, duplicate hash, hash failure)? |
| exit codes → scripts | CLI return values → shell scripts | does the new failure mode reuse an exit code a script already branches on? |

## Handing over a review or a fix

- Evidence, not summary: the commands run and their output, including
  the ones that did NOT pass.
- New findings are named in WORDS, not codes — the C#/W#/S# numbering in
  the older `docs/review/` files is legacy; do not continue it.
- Findings that are real but inert (no user-visible behavior change) are
  still recorded — say they are inert and why, instead of dropping them
  or overstating them.
- The dated report pattern (`docs/review/CODE_REVIEW_<date>.md`) stays:
  verdict table up front, per-finding sections with file:line citations
  and provenance tags.
