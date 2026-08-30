---
name: project-report
description: Generate a comprehensive state-of-the-project report for Pados (architecture, features, test health, delivery readiness, risks). Use when asked for a project report, status snapshot, or project health check.
---

# Project Report

Produce a dated, evidence-backed snapshot of the Pados codebase and write it to `docs/reports/YYYY-MM-DD-project-report.md`.

## Phase 0 — Ground truth (do this yourself, before any agents)

1. Get today's date: `Get-Date -Format yyyy-MM-dd` (PowerShell).
2. Read `Agent.md` (project conventions), `app/build.gradle.kts`, `gradle/libs.versions.toml`.
3. Run `git log --oneline -25` and note current branch/version (`versionCode`/`versionName`).
4. Check `docs/reports/` for the most recent previous `*-project-report.md`. If one exists, read it — the new report must include a **"Changes since last report"** section (delta of commits, versions, and previously listed risks that were resolved or are still open).

## Phase 1 — Parallel fan-out

Launch these agents **in a single message** so they run concurrently:

1. **Codebase map** (`Explore`, thoroughness: very thorough)
   Map the module/package structure across all three Gradle modules — `:app` (phone), `:sync` (shared phone<->watch wire format, pure JVM), `:wear` (Wear OS companion) — starting with `app/src/main/java/md/borisveriga/pados/`: data / domain / ui layers, list every screen (from navigation setup), key repositories and use cases, and enumerate dependency versions from `gradle/libs.versions.toml`. Flag notably outdated dependencies (compare against knowledge of latest stable; do not guess exact latest versions — mark as "check for update" instead). Cite `file:line` for claims.

2. **Test health** (`general-purpose`)
   Run `./gradlew testDebugUnitTest :sync:test` (use `gradlew.bat` on Windows; `:sync` is a `java-library`, so its task is `test`, not `testDebugUnitTest`) and report pass/fail counts per module and any failures verbatim. Then map test coverage by package: which packages under `data/`, `domain/`, `ui/` have unit tests and which have none. Note the instrumentation test suite size. Do NOT fix anything — report only.

3. **Delivery state** (`Explore`, thoroughness: medium)
   Report: versionCode/versionName, signing config setup (names only — never print keystore paths/passwords), R8/minification config, ProGuard rules summary, baseline profile presence (`app/release/baselineProfiles/` and generation config), CI workflows in `.github/workflows/`, Firebase App Distribution setup, and manifest-level settings (allowBackup, dataExtractionRules, FLAG_SECURE usage). Cite `file:line`.

Every agent must cite `file:line` evidence for checkable claims — no impressions.

## Phase 2 — Synthesis

Merge the three reports into `docs/reports/<date>-project-report.md` with this structure:

```markdown
# Pados Project Report — <date>

## Executive summary        (5–8 sentences, plain language)
## Architecture overview    (layers, key components, diagram in ASCII if useful)
## Feature inventory        (user-facing features, each with entry-point file)
## Dependency status        (table: dependency, current version, note)
## Test & CI health         (test run results, coverage-by-package table, CI status)
## Delivery readiness       (version, signing, R8, baseline profiles, distribution)
## Risk register            (table: risk, severity, evidence, suggested owner action)
## Changes since last report (only if a previous report exists)
```

Prioritize the risk register by severity. Keep the whole report scannable — tables for enumerable facts, prose for judgment.

Finish by giving the user the executive summary in chat plus the report file path. Do not commit unless asked.
