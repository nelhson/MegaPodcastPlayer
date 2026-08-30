---
name: security-plan
description: Run a deep defensive security audit of Pados (crypto review, OWASP MASVS, threat modeling, supply chain) and produce a hardening roadmap. Use when asked for a security audit, security plan, threat model, or crypto review of this app.
---

# Security Plan

This is a **defensive review of the user's own password manager app** — the goal is finding and fixing weaknesses in Pados, not exploiting anything. Produce severity-ranked findings and a hardening roadmap in `docs/reports/YYYY-MM-DD-security-plan.md`.

## Phase 0 — Ground truth

1. Get today's date: `Get-Date -Format yyyy-MM-dd`.
2. Read `Agent.md`, `app/src/main/AndroidManifest.xml`, `app/proguard-rules.pro`, and skim `data/crypto/` file names so agent prompts reference real classes.
3. Constraint to carry into all prompts: exports are **v4 identity-bound with no v2/v3 back-compat** — that binding is a security property; findings must not propose weakening it.
4. Check `docs/reports/` for a previous `*-security-plan.md`; if found, the new report tracks which previous findings were fixed.

## Phase 1 — Parallel fan-out

Launch these agents **in a single message**. Each finding needs: severity (Critical/High/Medium/Low/Info), `file:line` evidence, attack scenario, and a concrete fix. Instruct agents to verify claims in code — never report from memory of "typical" apps.

1. **Crypto review** (`general-purpose`)
   Deep-review `data/crypto/` (Argon2Kdf, HKDF, CryptoEngine, PasswordKeyDeriver, PassphraseGenerator, PasswordStrengthEstimator) and `data/vault/VaultFileStore`: KDF parameters vs. current OWASP/RFC 9106 recommendations, IV/nonce generation and reuse risk, authenticated encryption mode and tag verification, key material lifecycle (zeroization, time held in memory, Keystore usage), randomness sources (SecureRandom only), constant-time comparisons for MACs/verification. Also review the corresponding unit tests in `app/src/test/` for coverage of failure/tamper cases.

2. **OWASP MASVS audit** (`general-purpose`)
   First read the `owasp-security` skill, then audit against MASVS categories: STORAGE (at-rest encryption, backup exclusion rules, external storage, logs leaking secrets), AUTH (biometric implementation — CryptoObject binding or not, fallback path, session/auto-lock in `domain/` session management), PLATFORM (FLAG_SECURE coverage on every sensitive screen, clipboard handling and auto-clear, intents/exported components in the manifest, WebView usage), CODE (debug menu reachable in release builds? BuildConfig.ENVIRONMENT branching, logging of sensitive data), RESILIENCE (R8 config, root detection stance — note as decision, not automatic finding).

3. **Threat model** (`general-purpose`)
   Build a threat model for an offline password manager with one local cross-device channel (the Wear OS companion: `:sync` wire format, `:app` `data/watch/`, `:wear`). Scenarios: (a) stolen phone, locked; (b) stolen phone, unlocked/seized while open; (c) **stolen watch** — locked, and unlocked-on-wrist; (d) malicious app on the same device (accessibility abuse, clipboard sniffing, screen capture, an exported `WearableListenerService` any app can address); (e) exported vault file stolen from shared storage or messaging; (f) device backup/cloud sync leakage, **including the encrypted snapshot DataItem that persists in Google Play services storage on both devices**; (g) machine-in-the-middle on the Data Layer during pairing (the SAS confirmation is the only defence); (h) shoulder surfing / evil maid. For each: enumerate existing mitigations found **in the code** (cite `file:line`) and remaining gaps. Output a mitigations-vs-gaps matrix.

4. **Supply chain & build** (`general-purpose`)
   Audit `gradle/libs.versions.toml` and `app/build.gradle.kts`: dependencies with known CVEs or long-unmaintained status (verify with WebSearch, cite advisories), alpha/beta dependencies in security-critical paths (e.g. androidx.biometric alpha), ProGuard keep rules that over-expose classes, debug signing/test keystore hygiene, CI workflow secrets handling in `.github/workflows/`, and whether Dependabot/Renovate exists.

## Phase 2 — Synthesis

Deduplicate (the MASVS and threat-model agents will overlap), then write `docs/reports/<date>-security-plan.md`:

```markdown
# Pados Security Plan — <date>

## Executive summary          (overall posture in plain language + finding counts by severity)
## Findings                   (ordered by severity; each: ID, title, severity, evidence file:line, scenario, fix)
## Threat model matrix        (scenario × mitigations × gaps)
## Hardening roadmap          (ordered: fix-now / next-release / longer-term; each item maps to finding IDs)
## Accepted-risk decisions    (items needing a user decision, e.g. root detection, with trade-offs)
## Fixed since last audit     (only if a previous security plan exists)
```

Severity ranking beats completeness of prose — a reader must see the worst issues in the first screen.

Finish by giving the user the executive summary and all Critical/High findings in chat plus the report file path. Do not apply fixes and do not commit unless asked.
