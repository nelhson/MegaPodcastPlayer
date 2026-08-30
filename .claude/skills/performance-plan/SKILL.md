---
name: performance-plan
description: Audit Pados runtime, startup, and build performance and produce a prioritized optimization roadmap. Use when asked about performance, slow screens, startup time, jank, recomposition, or build speed.
---

# Performance Plan

Audit performance across three lenses (plus optional on-device measurement) and write a roadmap to `docs/reports/YYYY-MM-DD-performance-plan.md`.

## Phase 0 — Ground truth

1. Get today's date: `Get-Date -Format yyyy-MM-dd`.
2. Read `Agent.md`, `app/build.gradle.kts`, `gradle.properties`, and note baseline profile setup (`app/release/baselineProfiles/`).
3. Check whether an emulator/device is connected: `adb devices`. If one is available, include the optional measurement agent below; otherwise run static-analysis agents only and say so in the report.
4. Check `docs/reports/` for a previous `*-performance-plan.md`; if found, track progress against it.

## Phase 1 — Parallel fan-out

Launch these agents **in a single message**. Findings must cite `file:line` evidence and estimate user-visible impact (startup ms, frame drops, build seconds) qualitatively where measurement isn't possible.

1. **Compose runtime** (`general-purpose`)
   First read the `compose-performance-audit` skill, then audit the UI layer (`ui/`): the vault grid (lazy layout keys, contentType, item stability), recomposition risks (unstable parameters, lambdas capturing unstable state, missing `remember`), flow collection patterns (`collectAsStateWithLifecycle`), and heavy work in composition. Report top issues with evidence and fixes.

2. **Startup & crypto hot paths** (`general-purpose`)
   Audit: app startup path (Application class, Hilt graph size, anything eager that could be lazy), unlock latency — Argon2id KDF parameters are a deliberate security/latency trade-off (`data/crypto/Argon2Kdf`, `PasswordKeyDeriver`): report the current cost and whether derivation runs off the main thread with proper UI feedback, vault decrypt/parse on unlock (`data/vault/`), and whether baseline profiles cover the real startup + unlock journey (read the profile rules and generation setup). Do NOT propose weakening KDF parameters for speed — flag latency findings as UX (progress indication, caching via Keystore-wrapped keys) rather than crypto downgrades.

3. **Build performance** (`general-purpose`)
   First read the `gradle-build-performance` skill, then audit: `gradle.properties` (configuration cache, parallel, caching, JVM args), AGP/Gradle/Kotlin version alignment, KSP vs. KAPT usage, unnecessary work in `app/build.gradle.kts`. Time a build: run `./gradlew :app:assembleDebug --profile` twice (`gradlew.bat` on Windows) and report clean-ish vs. incremental timings and the biggest tasks from the profile report.

4. **On-device measurement** (`general-purpose`) — only if `adb devices` showed a device
   First read the `android-emulator-skill`, then: install the debug build, measure cold startup (`adb shell am start -W`), and capture jank stats for the vault screen (`adb shell dumpsys gfxinfo md.borisveriga.pados.debug`). Report numbers, not impressions.

## Phase 2 — Synthesis

Write `docs/reports/<date>-performance-plan.md`:

```markdown
# Pados Performance Plan — <date>

## Executive summary          (biggest wins first; note whether device measurements were taken)
## Measurements               (build timings; startup/jank numbers if a device was available)
## Findings                   (per lens: issue, evidence file:line, estimated impact, fix)
## Optimization roadmap       (ordered by impact/effort; quick wins first)
## Benchmark proposal         (macrobenchmark module: which journeys to benchmark — startup, unlock, vault scroll — and baseline profile regeneration workflow)
## Progress since last plan   (only if a previous performance plan exists)
```

Finish by giving the user the executive summary and top 3 wins in chat plus the report file path. Do not apply fixes and do not commit unless asked.
