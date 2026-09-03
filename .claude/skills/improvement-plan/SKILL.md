---
name: improvement-plan
description: Audit MegaPodcastPlayer code quality, architecture, testing, and accessibility, and produce a prioritized improvement roadmap. Use when asked for refactoring ideas, tech-debt review, code quality audit, or an improvement plan.
---

# Improvement Plan

Audit the codebase across four lenses and write a prioritized roadmap to
`docs/reports/YYYY-MM-DD-improvement-plan.md` (create `docs/reports/` if it does not exist).

## Phase 0 — Ground truth

1. Get today's date: `Get-Date -Format yyyy-MM-dd`.
2. **The root `CLAUDE.md` is a lean pointer, not the rulebook.** The conventions are enforced by code and are the
   yardstick; deviations from them are findings. Read:
   - `build-logic/convention/` — what every module is actually held to.
   - `config/detekt/detekt.yml` — every override states its reason; an override without one is
     itself a finding.
   - Boris's global standard: KDoc on every file, class and function (purpose, parameters, returns)
     plus inline comments for non-obvious logic, and unit tests for new or changed logic. Trivial
     config/doc changes are exempt.
3. **Read `docs/REFACTORING_PLAN.md` first.** It is a real audit of the whole repository dated
   2026-08-29 with a numbered backlog that CI references (`T-3`). Re-reporting an item it already
   raised, as though it were new, is the main way this skill wastes Boris's time — state which of its
   items are now done, which remain, and only then add anything new.
4. Check `docs/reports/` for a previous `*-improvement-plan.md`; if found, the new report must state
   which previous items were completed and which remain.

## Phase 1 — Parallel fan-out

Launch these agents **in a single message**. Tell each: findings must cite `file:line` evidence and
state concretely what to change — no vague advice. There are no `android-architecture`,
`kotlin-concurrency-expert`, `compose-ui` or `android-testing` skills in this environment, so agents
must work from the code and from the conventions above, not from a reference skill.

1. **Architecture** (`general-purpose`)
   Audit the module graph, which is the project's main structural asset: do `:feature:*` modules
   reach past `:core:data` into `:core:database` or `:core:network`? Does anything Android leak into
   the pure-JVM `:core:model` / `:core:wearprotocol` (they are shared with the watch and must stay
   dependency-free)? Does `:wear` duplicate what it should share through `:core:wearprotocol`? Are
   Hilt modules placed with the thing they provide? Is the repository interface/implementation split
   consistent (`PodcastRepository` / `OfflineFirstPodcastRepository`,
   `DownloadRepository` / `MediaDownloadRepository`)?

2. **Code quality** (`general-purpose`)
   Audit coroutine usage (scopes, injected dispatchers via `@Dispatcher`, cancellation,
   `suspendRunCatching` versus bare `runCatching` — the latter swallows `CancellationException`),
   Compose patterns (state hoisting, stability, `collectAsStateWithLifecycle`), error handling
   consistency, and KDoc gaps against the standard in Phase 0. Note where documentation exists but
   has drifted from the code — a confidently wrong comment is worse than none.

3. **Testing** (`general-purpose`)
   Run `.\gradlew.bat testDebugUnitTest test --continue` (both tasks — the two pure-JVM modules use
   `test`) and confirm the suite passes. Then map gaps by module: which packages have no tests, which
   ViewModels are untested, and which test categories are missing entirely (there is no
   instrumentation suite; Compose is tested through Robolectric). For each gap name the tests to add
   and an existing file to use as a template. `:core:testing` holds the shared fixtures
   (`MainDispatcherRule`, `InMemoryPreferencesDataStore`, `TestModels`) — new tests should use them
   rather than rolling their own.

4. **Accessibility** (`general-purpose`)
   Targeted pass over `:core:designsystem` and the `:feature:*` screens: content descriptions on icon
   buttons, touch target sizes, semantics on the custom components (`WaveScrubber`,
   `WavyProgressLine`, `ReorderHandle`, `SelectionToolbar`), and the reorder gesture's accessibility
   story — a drag handle with no semantic action is unusable with TalkBack. `ColorContrastTest` and
   `TypographyTest` already exist in `:core:designsystem`; check what they cover before reporting
   contrast findings. Cite `file:line`.

## Phase 2 — Synthesis

Deduplicate overlapping findings, then write `docs/reports/<date>-improvement-plan.md`:

```markdown
# MegaPodcastPlayer Improvement Plan — <date>

## Executive summary
## Progress against REFACTORING_PLAN.md   (which numbered items are done, which remain)
## Quick wins            (< 1 day each — table: item, files, why)
## Medium efforts        (1–3 days — each with a short paragraph and file list)
## Structural changes    (multi-day/architectural — each with rationale and rough migration path)
## Testing roadmap       (ordered list of suites to add, with a template file per suite)
## Progress since last plan   (only if a previous improvement plan exists)
```

Rank within each tier by Impact × Effort. Every item must be actionable without re-doing the
analysis: name the files and the change.

Finish by giving Boris the executive summary and top 5 items in chat plus the report file path. Do
not apply fixes and do not commit unless asked.
