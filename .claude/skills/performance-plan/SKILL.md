---
name: performance-plan
description: Audit MegaPodcastPlayer runtime, startup, playback, download and build performance and produce a prioritized optimization roadmap. Use when asked about performance, slow screens, startup time, jank, recomposition, download speed, or build speed.
---

# Performance Plan

Audit performance across three lenses (plus optional on-device measurement) and write a roadmap to
`docs/reports/YYYY-MM-DD-performance-plan.md` (create `docs/reports/` if it does not exist).

## Phase 0 — Ground truth

1. Get today's date: `Get-Date -Format yyyy-MM-dd`.
2. **The root `CLAUDE.md` is a lean pointer, not the rulebook.** Read `build-logic/convention/`, `gradle.properties`,
   `app/build.gradle.kts` and `gradle/libs.versions.toml`.
3. **There are no baseline profiles in this project.** Do not go looking for
   `app/release/baselineProfiles/` — it does not exist. "Add a baseline profile" is therefore a
   legitimate finding rather than something to audit.
4. Check whether a device is connected. `adb` is **not on PATH**; it is at
   `$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe`. If a device is available, include the
   measurement agent below; otherwise run the static agents only and say so in the report.
5. Check `docs/reports/` for a previous `*-performance-plan.md`; if found, track progress against it.

## Phase 1 — Parallel fan-out

Launch these agents **in a single message**. Findings must cite `file:line` and estimate
user-visible impact (startup ms, dropped frames, download throughput, build seconds), qualitatively
where measurement is not possible. There are no `compose-performance-audit`,
`gradle-build-performance` or `android-emulator-skill` skills in this environment — agents work from
the code.

1. **Compose runtime** (`general-purpose`)
   Audit `:core:designsystem` and the `:feature:*` screens: lazy list keys and `contentType` in the
   library, downloads and queue lists; item stability; recomposition risks (unstable parameters,
   lambdas capturing unstable state, missing `remember`); flow collection via
   `collectAsStateWithLifecycle`; and heavy work in composition. Pay particular attention to the
   continuously-animating components — `WaveScrubber`, `WavyProgressLine`, `MorphShape` and the
   player sheet's drag/animation state — since those recompose or redraw on every frame while
   playing and are the most likely source of real jank.

2. **Playback, download and startup hot paths** (`general-purpose`)
   Audit: app startup (`MegaPodcastPlayerApplication`, Hilt graph size, anything eager that could be lazy,
   WorkManager initialisation); the media stack in `:core:media` (data source chain construction per
   open, `EpisodePlayer`, `PlaybackService`); the download path (`EpisodeDownloader`,
   `ChunkedDataSource` — note `MAX_PARALLEL_DOWNLOADS` and the 8 MB chunk size and whether either is
   worth tuning); YouTube resolution latency (`NewPipeAudioResolver` serialises extraction behind one
   lock and caches results — assess whether that lock is ever on a user-visible path); and Room query
   shape in `:core:database` (missing indices, queries returning more than the screen needs).

3. **Build performance** (`general-purpose`)
   Audit `gradle.properties` (configuration cache, parallel, caching, JVM args), AGP/Gradle/Kotlin
   alignment against `gradle/libs.versions.toml`, KSP usage, and the cost of dependency verification
   (`gradle/verification-metadata.xml`). Time it: run
   `.\gradlew.bat :app:assembleDebug --profile` twice and report cold-ish versus incremental timings
   and the biggest tasks from the profile report. Note that the daemon runs on JDK 25 per
   `gradle/gradle-daemon-jvm.properties`, which constrains some tooling (detekt 2.x is pinned for
   exactly this reason).

4. **On-device measurement** (`general-purpose`) — only if a device is connected
   Install the debug build (see the `install_on_devices` skill for device classification and the
   `ANDROID_SERIAL` targeting this needs), then measure cold startup with
   `adb shell am start -W md.borisveriga.megapodcastplayer/.MainActivity` and capture
   `adb shell dumpsys gfxinfo md.borisveriga.megapodcastplayer` while scrolling the library and while the
   player sheet animates. Report numbers, not impressions. Remember both builds are debug — no R8 —
   so absolute figures are pessimistic; say so.

## Phase 2 — Synthesis

Write `docs/reports/<date>-performance-plan.md`:

```markdown
# MegaPodcastPlayer Performance Plan — <date>

## Executive summary          (biggest wins first; note whether device measurements were taken)
## Measurements               (build timings; startup/jank numbers if a device was available)
## Findings                   (per lens: issue, evidence file:line, estimated impact, fix)
## Optimization roadmap       (ordered by impact/effort; quick wins first)
## Benchmark proposal         (macrobenchmark module: which journeys — startup, library scroll,
                               player sheet, first play — plus a baseline profile, which this
                               project has none of)
## Progress since last plan   (only if a previous performance plan exists)
```

Finish by giving Boris the executive summary and top 3 wins in chat plus the report file path. Do not
apply fixes and do not commit unless asked.
