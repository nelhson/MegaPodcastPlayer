---
name: security-plan
description: Run a deep defensive security audit of BPodcat (untrusted feed input, the Wear Data Layer, OWASP MASVS, supply chain) and produce a hardening roadmap. Use when asked for a security audit, security plan, threat model, or security review of this app.
---

# Security Plan

This is a **defensive review of Boris's own app** — the goal is finding and fixing weaknesses in
BPodcat, not exploiting anything. Produce severity-ranked findings and a hardening roadmap in
`docs/reports/YYYY-MM-DD-security-plan.md` (create `docs/reports/` if it does not exist).

## What the threat surface actually is

BPodcat is not a password manager and has no vault, no crypto layer and no user accounts. Do not
audit for those. Its real exposure is three things:

1. **Untrusted remote input.** RSS/Atom feeds and YouTube extraction results are attacker-authored
   XML, JSON and URLs, and they reach a Room database, Coil and the Media3 data source chain.
2. **A local cross-device channel.** The Wearable Data Layer between `:app` and `:wear`, which routes
   purely on package name plus signing certificate and carries listening data.
3. **Supply chain.** Notably NewPipeExtractor, resolved from JitPack rather than Maven Central.

## Phase 0 — Ground truth

1. Get today's date: `Get-Date -Format yyyy-MM-dd`.
2. **There is no `Agent.md`.** Read `app/src/main/AndroidManifest.xml`,
   `wear/src/main/AndroidManifest.xml`, both `proguard-rules.pro`, `docs/RELEASE_SIGNING.md` and
   `docs/DEPENDENCY_VERIFICATION.md`.
3. Read `docs/REFACTORING_PLAN.md` — it already names untrusted input reaching the media stack as
   the one genuine security hole and describes the fix that was applied. Verify the current state of
   that fix rather than re-reporting it as open.
4. Constraints to carry into every prompt:
   - **`isPlayableMediaUrl` is a scheme allowlist, enforced twice on purpose** (feed parsers, and
     `PlayableEpisode.toMediaItem` as defence in depth). `MIGRATION_2_3` cleans rows written before
     it existed and its `LIKE` patterns must stay in step. Findings must not propose relaxing it.
   - **Release signing deliberately fails without a keystore.** The debug-key fallback is opt-in via
     `-PallowDebugSigningForRelease` precisely because the Data Layer trusts package name plus
     certificate. Do not propose restoring the automatic fallback.
5. Check `docs/reports/` for a previous `*-security-plan.md`; if found, track which findings were
   fixed.

## Phase 1 — Parallel fan-out

Launch these agents **in a single message**. Each finding needs: severity
(Critical/High/Medium/Low/Info), `file:line` evidence, an attack scenario, and a concrete fix.
Instruct agents to verify claims in code — never report from memory of "typical" apps.

1. **Untrusted input** (`general-purpose`)
   Deep-review the path from remote bytes to execution: `:core:network` (`RssParser` — XML entity
   expansion and external entities, unbounded fields, `FeedRemoteDataSource`), `HttpsUpgradeInterceptor`
   (it downgrades to cleartext on transport failure — assess what that permits), `:core:youtube`
   (`OkHttpNewPipeDownloader`'s 2 MB body cap, what the extractor does with attacker-controlled
   pages), and `:core:model` (`isPlayableMediaUrl`, `isYouTubeVideoId`, `PodcastLinkParser`). Then
   check where those values land: Room, Coil image loading, the Media3 chain, and the notification
   text in `NewEpisodeNotifier`. Also review the corresponding tests for tamper and malformed-input
   coverage.

2. **OWASP MASVS audit** (`general-purpose`)
   First read the `owasp-security` skill — it exists in this environment, unlike most skills other
   audits reference. Then audit: STORAGE (what the Room database and `episode_downloads` cache hold,
   `allowBackup` and data extraction rules, whether logs leak feed contents or URLs), PLATFORM
   (exported components in both manifests — `WearCommandService` is the one any app can address —
   pending intents, foreground service types), CODE (debug-only behaviour reachable in release,
   `BuildConfig.DEBUG` branching such as the OkHttp logging interceptor, what is logged at WARN and
   above), and RESILIENCE (R8 configuration and ProGuard keep rules that over-expose classes).

3. **Threat model** (`general-purpose`)
   Build a threat model for an offline podcast player with one local cross-device channel. Scenarios:
   (a) a hostile feed the user subscribes to; (b) a compromised or impersonated CDN, including the
   cleartext fallback path; (c) a malicious app on the phone addressing the exported
   `WearCommandService`; (d) a malicious app on the *watch* doing the same; (e) an attacker who
   installs a debug-signed build with the same application ID — what `WearSenderVerifier` does and
   does not prevent; (f) device backup or cloud sync leaking the library and listening history;
   (g) a stolen unlocked phone. For each, enumerate mitigations found **in the code** with
   `file:line`, and the remaining gaps. Output a mitigations-vs-gaps matrix.

4. **Supply chain & build** (`general-purpose`)
   Audit `gradle/libs.versions.toml` and the convention plugins: dependencies with known CVEs or
   long-unmaintained status (verify with WebSearch, cite advisories), alpha/beta dependencies in
   sensitive paths (detekt is pinned to a 2.0.0-alpha for a stated JDK reason — assess the risk of
   that, not just its presence), **NewPipeExtractor resolving from JitPack** (a source-built artifact
   from a third-party service — assess what dependency verification does and does not guarantee
   here), the `gradle/verification-metadata.xml` regime, CI secrets handling in
   `.github/workflows/ci.yml`, and whether Dependabot or Renovate exists.

## Phase 2 — Synthesis

Deduplicate (the MASVS and threat-model agents will overlap), then write
`docs/reports/<date>-security-plan.md`:

```markdown
# BPodcat Security Plan — <date>

## Executive summary          (posture in plain language + finding counts by severity)
## Findings                   (ordered by severity; each: ID, title, severity, evidence file:line,
                               scenario, fix)
## Threat model matrix        (scenario × mitigations × gaps)
## Hardening roadmap          (fix-now / next-release / longer-term; each item maps to finding IDs)
## Accepted-risk decisions    (items needing Boris's decision, with trade-offs)
## Fixed since last audit     (only if a previous security plan exists)
```

Severity ranking beats completeness of prose — the worst issues must be on the first screen.

**One standing item belongs in every report's accepted-risk section:** `:core:youtube` works by
extracting YouTube's own player response, which their terms of service do not permit. It is stated
plainly in `YouTubeAudioResolver.kt` and is a deliberate, accepted trade-off for a sideloaded
personal build. It is a legal and distribution risk, not a vulnerability — record it as such and do
not propose "fixing" it.

Finish by giving Boris the executive summary and all Critical/High findings in chat plus the report
file path. Do not apply fixes and do not commit unless asked.
