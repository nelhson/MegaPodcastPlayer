---
name: project-report
description: Generate a comprehensive state-of-the-project report for BPodcat (architecture, features, test health, delivery readiness, risks). Use when asked for a project report, status snapshot, or project health check.
---

# Project Report

Produce a dated, evidence-backed snapshot of the BPodcat codebase and write it to
`docs/reports/YYYY-MM-DD-project-report.md` (create `docs/reports/` if it does not exist).

## Phase 0 — Ground truth (do this yourself, before any agents)

1. Get today's date: `Get-Date -Format yyyy-MM-dd` (PowerShell).
2. **There is no `Agent.md` or `CLAUDE.md` in this repository.** The conventions are enforced by
   code, so read those instead: `build-logic/convention/` (the plugins every module applies),
   `config/detekt/detekt.yml` (each override carries its own stated reason), and
   `gradle/libs.versions.toml` (single source of truth for versions).
3. Read `docs/REFACTORING_PLAN.md` — a substantive prior audit dated 2026-08-29 that CI itself
   references. Findings that repeat it should say so rather than being presented as new.
4. Run `git log --oneline -25`, and note the branch. `versionCode`/`versionName` are **not** in
   `app/build.gradle.kts`; they are hardcoded to `1` / `"1.0"` in the two application convention
   plugins.
5. Check `docs/reports/` for the most recent previous `*-project-report.md`. If one exists, read it —
   the new report must include a **"Changes since last report"** section (delta of commits and
   previously listed risks that were resolved or are still open).

## Phase 1 — Parallel fan-out

Launch these agents **in a single message** so they run concurrently.

1. **Codebase map** (`Explore`, thoroughness: very thorough)
   Map the module graph. It is not three modules — it is nineteen:
   - Applications: `:app` (phone), `:wear` (Wear OS companion).
   - Pure-JVM contracts shared with the watch: `:core:model`, `:core:wearprotocol`.
   - Android infrastructure: `:core:common`, `:core:database`, `:core:datastore`, `:core:network`,
     `:core:youtube`, `:core:media`, `:core:data`, `:core:designsystem`.
   - Shared test utilities on every Android module's test classpath: `:core:testing`.
   - Features: `:feature:library`, `:feature:downloads`, `:feature:search`, `:feature:podcast`,
     `:feature:player`, `:feature:settings`.

   Sources live at `<module>/src/main/kotlin/md/borisveriga/bpodcat/…`. List every screen from
   `app/.../navigation/BPodcatNavigation.kt`, the repositories in `:core:data`, and the DAOs in
   `:core:database`. Enumerate dependency versions from the version catalog and flag notably
   outdated ones — mark as "check for update" rather than guessing exact latest versions. Cite
   `file:line`.

2. **Test health** (`general-purpose`)
   Run `.\gradlew.bat testDebugUnitTest test --continue` (both tasks: `:core:model` and
   `:core:wearprotocol` are JVM modules whose task is `test`, not `testDebugUnitTest`). Report
   pass/fail counts per module and any failures verbatim. Then map coverage by module and package —
   which have unit tests and which have none. Note that several modules render Compose through
   Robolectric rather than on a device, and that there is no instrumentation suite. Optionally run
   `.\gradlew.bat koverLogDebug` for the headline coverage number, which CI already publishes. Do
   NOT fix anything — report only.

3. **Delivery state** (`Explore`, thoroughness: medium)
   Report: the hardcoded version, `configureSharedSigning` behaviour (release fails without
   `keystore.properties`; `-PallowDebugSigningForRelease` is the opt-in escape hatch and why it is
   discouraged — see `docs/RELEASE_SIGNING.md`), R8/shrinking config for both applications,
   ProGuard rules summary, the single CI workflow `.github/workflows/ci.yml`, dependency
   verification (`gradle/verification-metadata.xml`, `docs/DEPENDENCY_VERIFICATION.md`), lint
   baselines per module, and manifest-level settings (exported components, permissions). State
   explicitly that there is **no** Firebase, no distribution workflow and no baseline profiles.
   Cite `file:line`. Never print keystore paths or passwords.

Every agent must cite `file:line` evidence for checkable claims — no impressions.

## Phase 2 — Synthesis

Merge into `docs/reports/<date>-project-report.md`:

```markdown
# BPodcat Project Report — <date>

## Executive summary        (5–8 sentences, plain language)
## Architecture overview    (module graph, layers, key components)
## Feature inventory        (user-facing features, each with entry-point file)
## Dependency status        (table: dependency, current version, note)
## Test & CI health         (test run results, coverage-by-module table, CI status)
## Delivery readiness       (version, signing, R8, distribution — and what does not exist)
## Risk register            (table: risk, severity, evidence, suggested owner action)
## Changes since last report (only if a previous report exists)
```

Prioritize the risk register by severity. Tables for enumerable facts, prose for judgment.

Two standing risks worth checking the current state of every time:

- **CI trigger mismatch.** `ci.yml` runs on pushes to `main`, but the working branch is `master`. If
  that is still true, pushes to the default branch run no CI at all; only pull requests do.
- **YouTube extraction.** `:core:youtube` reads YouTube's player response, which their terms of
  service do not permit (stated plainly in `YouTubeAudioResolver.kt`). It is an accepted trade-off
  for a sideloaded personal build and a hard blocker for Play Store distribution. It also breaks
  without warning whenever YouTube changes its player.

Finish by giving Boris the executive summary in chat plus the report file path. Do not commit unless
asked.
