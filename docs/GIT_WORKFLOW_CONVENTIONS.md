# Git workflow conventions

How branches, history and pushes are handled in this repo. `AGENTS.md`
§"Git Workflow" carries the short version; this file is the reference.
Companion to [`ISSUE_CONVENTIONS.md`](ISSUE_CONVENTIONS.md) (that file
covers the tracker; this one covers the branch).

## The division of labor

- **The branch's history is the maintainer's.** Rebase, merge, `reset`,
  force-push and ref deletion change what the maintainer's PR looks like
  and what can be reverted — they are the maintainer's calls, not the
  agent's.
- **The agent prepares and measures.** Draft the change on a scratch
  branch or worktree, test it there, and present the options. The
  maintainer picks.
- **Ordinary commits are the agent's.** Commits on the working branch,
  test files, and evidence-bearing docs land with the work, as usual —
  EXCEPT convention documents (see below) and the rest of `docs/`,
  which is gitignored (only `docs/*CONVENTIONS.md` is tracked).
- **The agent does not stage its own edits.** If a file already has
  staged changes, the agent's new changes to it stay unstaged. The
  maintainer reads the two layers separately: `git diff --cached` is the
  earlier work, `git diff` is the agent's newest change on top of it.
  Staging happens only as part of a commit the maintainer asked for.

## Releases are explicit

Pushes to `main` run the build+test matrix only. The CI `msi` job runs
only on manual dispatch from `main`: it builds the Windows MSI,
force-moves the rolling `latest` tag, and republishes the "latest"
release. Pushing a `v*` tag publishes a permanent versioned release.
Consequences:

- Never push to `main`, push a `v*` tag, or dispatch the `msi`
  workflow without an explicit ask.
- Work lands as **feature branches + PRs merged via GitHub** — that is
  this repo's existing pattern (`fix/ratelimit-credentials-scanner-recheck`,
  `ui/polish-ui`, …).
- CI also runs the 3-OS build+test matrix on every PR. A green local
  Windows run is necessary but not sufficient; let CI arbitrate the
  macOS/Linux legs.

## Never rewrite history without the maintainer's go-ahead

**Do NOT** — without an explicit ask in the conversation:

- merge `main` (or any other branch) into the working branch "to pick up
  a fix" or "to resolve an overlap";
- `rebase`, `reset --hard`, or amend commits already on the working
  branch;
- `push --force`, or delete/move a branch or tag that exists on the
  remote (`latest` is force-moved by CI only);
- push at all, unless the maintainer asked for it in this conversation.

**DO:**

- prepare the alternative on a scratch branch or worktree
  (`git worktree add /tmp/x --detach <base>`), test it there, and
  present the options;
- keep the maintainer's current state reachable while you prepare an
  alternative — a `backup/<what-it-is>` branch — so every option stays
  one command away (`git reset --hard backup/<name>`);
- ask before the irreversible step, and name the exact command you would
  run and what it costs.

### What counts as an "explicit ask"

An ask names the operation or its shape. Consent for one operation is
not consent for another.

| Counts as an ask | Does NOT count |
|---|---|
| "merge main into the branch" | "resolve the overlap" |
| "rebase it onto main" | "make the branch consistent with main" |
| "force-push the branch" | "push the branch" |
| "merge the PR" (implies landing on main + release) | "make it work" |
| "yes, do option C" (an option that names the operation) | silence, or a general "go ahead" |

Silence is not consent, and "go ahead" is not a shape. When the ask is
ambiguous, ask again — the cost of a second question is a minute; the
cost of an unrequested rewrite or release is the maintainer's review
surface and, on `main`, a shipped installer.

## Prefer merge over rebase

This repo's docs cite commits by hash: TROUBLESHOOTING.md anchors a
latent bug to `f00e6a0`, and review docs reference fix commits. A
rebase renames every commit in the replayed range and silently kills
those citations.

- Integrate `main` into a feature branch by **merge**, not rebase, when
  the branch needs fresh commits.
- When the same fix landed on both sides, resolve it in the merge and
  keep ONE copy of the code — main's, if main has it.
- PRs are landed with GitHub's merge (not squash, not rebase) so the
  branch's commits keep their hashes and the citations keep resolving.
- After any unavoidable rewrite, sweep the docs for now-dead hashes
  (`git grep -E '[0-9a-f]{7,40}' docs/`) and update the bodies of any
  issues whose Version field cited a renamed commit.

## Before any force-push (explicit go-ahead required)

A force-push discards the remote's view of the branch. Before running
one:

1. `git fetch origin <branch>` first — never push against a stale
   remote-tracking ref.
2. `git merge-base --is-ancestor origin/<branch> backup/<old-state>` —
   the remote's tip must be inside the backup. If it is not, the remote
   holds commits you do not have: **stop**, do not force.
3. `git diff --stat origin/<branch> backup/<old-state>` — non-empty
   means the remote has work you would be dropping.
4. Push with an explicit lease:
   `git push --force-with-lease=refs/heads/<branch>:<expected-remote-sha> origin <branch>`.
5. **Check the PR for review comments first.** A force-push orphans
   per-commit review comments on the old SHAs.
6. Keep the backup ref until the maintainer accepts the new shape.

## Backup refs

- Name them `backup/<what-it-is>`, so their purpose is obvious in
  `git branch`.
- They are **local**; do not push them unless the maintainer asks.
- **Never base new work on a backup ref.** It holds the OLD shape.
- Report backup refs when you hand over a result, and prune them once
  the maintainer has accepted the new shape — an unpruned `backup/`
  graveyard is how the next agent ends up re-landing the old code.

## Measuring a change before you propose it

- Run the **full** test suite (`gradlew.bat test`), not just the tests
  next to your change — shared-model changes reach the desktop MVI
  tests, the CLI tests, and the reducer tests at once.
- Behavior changes in `shared/` ship with tests; hand over the commands
  and their output, not a summary.
- If the change touches cache-entry shape, CLI output, or quota
  behavior, say explicitly whether the e2e script
  (`scripts/test_cli_e2e.py`, burns ~6 requests + 2 uploads of REAL
  quota) ran, and whether that was warranted.

## Convention documents need human review before they land

Documents that state **rules** for humans and agents — `AGENTS.md`, this
file, `ISSUE_CONVENTIONS.md`, `REVIEW_CONVENTIONS.md` — are drafted by
the agent but **landed by the maintainer**:

- an agent may write or edit such a doc in the tree, and say what it
  changed and why;
- the agent does **not** commit or push it on its own initiative, even
  when the change is small, mechanical, or "obviously right" — the text
  encodes policy, and policy is the maintainer's to approve;
- the staging rule above applies here too: a new edit to a convention
  doc stays unstaged on top of whatever the maintainer has staged.
