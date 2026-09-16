---
name: merge-to-main
description: Take the working-tree changes of MegaPodcastPlayer all the way to the default branch — commit on a branch, open a PR, review it and post the review, commit the fixes, merge, then install the merged build on the phone and/or watch if their code changed. Use when asked to merge to main, ship these changes, land this, or "commit, PR, review, merge".
---

# Merge to main

Six steps, in order, each one gated on the previous. Stop and report at the first step that fails —
never merge past a red check or install a build that did not come from the merged branch.

**"main" means the repository's default branch, which is `master`** (`gh repo view --json
defaultBranchRef`). There is no `main` branch; do not create one. Read the default branch name
rather than hardcoding it, in case it is ever renamed.

Invoking this skill **is** the user's request to commit, push, open a PR and merge — no need to ask
again for each of those. Still ask when something below says so.

## 0 — Preflight

```bash
git rev-parse --abbrev-ref HEAD
git status --porcelain
git fetch origin
gh auth status
```

- Nothing to commit and no unpushed commits → stop, there is nothing to merge.
- Look at **every** changed and untracked file before staging. Do not commit `keystore.properties`,
  `local.properties`, `google-services.json`, `dist/`, build outputs or anything that looks like a
  secret — if one shows up as untracked, stop and ask.
- If the tree mixes unrelated changes, say so and ask whether they belong in one PR.
- Screenshot goldens (`src/test/screenshots/*.png`) in the diff must be **read** (open the images)
  before committing, per `docs/SCREENSHOT_TESTS.md`.

## 1 — Commit on a branch

If on `master`, branch first — never commit straight to it:

```bash
git switch -c <short-kebab-name-describing-the-change>
```

If already on a feature branch, stay on it. Then verify before committing, the same signal CI gives:

```powershell
.\gradlew.bat detekt --continue
.\gradlew.bat testDebugUnitTest test --continue
.\gradlew.bat lintDebug --continue
```

A failure here is fixed first, not committed around. Stage files by name (not `git add -A`), and
write the message in this repository's style (see `git log --oneline -20`): one sentence, imperative,
describing the effect for the user rather than the files touched, e.g. *"Keep the shelf card's play
button at full size, and leave the empty detail pane blank"*. A body explains why, when it is not
obvious. End it with the attribution lines from the system reminder.

## 2 — Open the PR

```bash
git push -u origin HEAD
gh pr create --base master --title "<commit subject>" --body-file <scratchpad>/pr-body.md
```

The body: **Summary** (what changed and why, 2–5 bullets), **Modules touched**, **Verification**
(which of detekt/tests/lint ran and passed, goldens re-recorded or not), then the PR attribution
line. Write it to a scratchpad file rather than inlining it — PowerShell mangles multi-line args.

## 3 — Review, and write the review down

Review the PR's diff (`gh pr diff`) against `master`, not the conversation's memory of it. Prefer a
**fresh subagent** for this, so the reviewer did not write the code it is judging; give it the PR
number and ask for findings with file:line, severity and a concrete failure scenario.

Review against this project's own rules, not generic taste — `CLAUDE.md` lists the ones features
break. In particular:

- episode URL / `youtube://` spellings and the podcast-id hash unchanged (changing them orphans data);
- new URL or file input going through `isPlayableMediaUrl`;
- data items vs messages across the Data Layer (`WearPaths`); no audio crossing to the watch;
- `suspendRunCatching` instead of `runCatching`/bare `catch`; swallowed failures recorded via
  `CrashReporter`;
- KDoc on every file/class/function, tests for changed logic, no `TODO`/`FIXME`/`!!`;
- user-facing text in `strings.xml`, worded per `docs/COPY_RULES.md`;
- a version bump without `gradle/verification-metadata.xml` refreshed;
- anything touching the moments export (the only user-written data).

Drop findings that do not survive a second look. Post what remains on the PR:

```bash
gh pr review <number> --comment --body-file <scratchpad>/review.md
```

`--comment`, because GitHub refuses `--approve`/`--request-changes` on your own PR. The review lists
each finding (severity, `file:line`, what goes wrong, suggested fix) or says plainly that it found
nothing. Nitpicks are marked as such.

## 4 — Fix and commit

Fix every finding that is a real defect; for anything deliberately left, reply on the PR with the
reason. Re-run the step-1 checks, commit the fixes as a separate commit (e.g. *"Address review: …"*)
and push. If the review found nothing, skip this step and say so.

Then wait for CI on the PR's head commit:

```bash
gh pr checks <number> --watch
```

Red CI → read the failing log (`gh run view <run-id> --log-failed`), fix, push, wait again. Do not
merge on red, and do not merge while a check is still pending.

## 5 — Merge

```bash
gh pr merge <number> --squash --delete-branch
```

**Squash**: `master` has a linear history with no merge commits, one sentence-style commit per
change — keep it that way. The squash commit subject is the PR title; the body keeps the
attribution lines. Then bring the local checkout onto the merged result:

```bash
git switch master
git pull --ff-only origin master
git log --oneline -3          # the squash commit is at the top
```

`--ff-only` failing means local `master` diverged — stop and report, do not reset.

## 6 — Install from `master`, only if an app changed

Decide from the files the **merged PR** changed (`gh pr view <number> --json files`):

| Changed paths | Install |
| --- | --- |
| `wear/**`, `core/wearprotocol/**`, `core/common/**`, `core/model/**` | watch (`:wear`) |
| `app/**`, `feature/**`, any other `core/**` module, and the four above too | phone (`:app`) |
| `build-logic/**`, `gradle/libs.versions.toml`, `gradle/verification-metadata.xml`, root `build.gradle.kts` / `settings.gradle.kts` | both |
| only `docs/**`, `.claude/**`, `.github/**`, `config/**`, `*.md`, `src/test/**` (tests and screenshot goldens) | nothing — say so and stop |

`:wear` depends only on `:core:wearprotocol` and `:core:common`, which both expose `:core:model`;
`:app` depends on everything else. Test-only changes do not alter either APK.

**The watch pairing is package name plus signing certificate, and phone and watch are always from the
same build.** A change that needs only the watch still leaves the phone on an older build, which is
fine as long as both came from this machine's debug key — but if the change touched the Data Layer
protocol (`core/wearprotocol/**`, `core/model/**`), install **both**, because strict JSON decoding
means an old phone and a new watch cannot talk.

Install by following the **`install_on_devices`** skill — adb location, device classification by
`ro.build.characteristics`, `ANDROID_SERIAL` per install, and its troubleshooting — while checked
out on the freshly pulled `master`. Never uninstall to make an install succeed without asking; it
wipes the user's library.

## 7 — Report

- PR number and URL, the squash commit SHA on `master`.
- Review: how many findings, how many fixed, which were left and why.
- CI result.
- Install: per device — model, module, outcome — or "no app code changed, nothing installed". Call
  out a skipped device explicitly (usually the watch, when wireless debugging dropped).
