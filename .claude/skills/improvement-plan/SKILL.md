---
name: improvement-plan
description: Audit Pados code quality, architecture, testing, and accessibility, and produce a prioritized improvement roadmap. Use when asked for refactoring ideas, tech-debt review, code quality audit, or an improvement plan.
---

# Improvement Plan

Audit the codebase across four lenses and write a prioritized roadmap to `docs/reports/YYYY-MM-DD-improvement-plan.md`.

## Phase 0 — Ground truth

1. Get today's date: `Get-Date -Format yyyy-MM-dd`.
2. Read `Agent.md` — its conventions are the yardstick; deviations from it are findings.
3. Check `docs/reports/` for a previous `*-improvement-plan.md`; if found, the new report must state which previous items were completed and which remain.

## Phase 1 — Parallel fan-out

Launch these agents **in a single message**. Tell each agent: findings must cite `file:line` evidence and state concretely what to change — no vague advice.

1. **Architecture** (`general-purpose`)
   First read the `android-architecture` skill for the reference standard, then audit: Clean Architecture layer separation (does `ui/` reach into `data/`? do use cases exist where logic sits in ViewModels?), Hilt module organization, whether the `:app` / `:sync` / `:wear` split is drawn in the right place (does anything app-specific leak into `:sync`? does `:wear` duplicate what it should share?), domain model purity. Report top findings, each with evidence and a proposed fix.

2. **Code quality** (`general-purpose`)
   First read the `kotlin-concurrency-expert` and `compose-ui` skills for reference standards, then audit: coroutine usage (scopes, dispatchers, cancellation, Flow collection lifecycles in ViewModels/Compose), Compose patterns (state hoisting, stability, side-effect handlers), error handling consistency, and documentation gaps (undocumented public classes/functions per the user's docstring standard). Report top findings with evidence.

3. **Testing** (`general-purpose`)
   First read the `android-testing` skill, then map test coverage gaps: which packages have no unit tests, ViewModels without tests (only LoginViewModel and SettingsViewModel are known to have tests), the single instrumentation test file, missing test categories (screenshot tests, Hilt integration tests, navigation tests). For each gap, state what tests to add and which existing test file to use as a template. Run `./gradlew testDebugUnitTest` (`gradlew.bat` on Windows) to confirm the current suite passes.

4. **Accessibility** (`general-purpose`)
   First read the `android-accessibility` skill, then do a targeted pass over the Compose UI (`ui/`): content descriptions on icon buttons, touch target sizes, semantics for the vault grid, contrast assumptions in `ui/theme/`. Report concrete violations with `file:line`.

## Phase 2 — Synthesis

Deduplicate overlapping findings, then write `docs/reports/<date>-improvement-plan.md`:

```markdown
# Pados Improvement Plan — <date>

## Executive summary
## Quick wins            (< 1 day each — table: item, files, why)
## Medium efforts        (1–3 days — each with a short paragraph and file list)
## Structural changes    (multi-day/architectural — each with rationale and rough migration path)
## Testing roadmap       (ordered list of test suites to add, with template file per suite)
## Progress since last plan   (only if a previous improvement plan exists)
```

Rank within each tier by Impact × Effort. Every item must be actionable without re-doing the analysis: name the files and the change.

Finish by giving the user the executive summary and top 5 items in chat plus the report file path. Do not apply fixes and do not commit unless asked.
