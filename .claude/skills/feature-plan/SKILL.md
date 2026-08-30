---
name: feature-plan
description: Plan a new feature for Pados. With an argument (e.g. /feature-plan autofill service) it produces a detailed implementation plan for that feature; with no argument it first brainstorms and prioritizes a feature backlog, then plans the top candidate. Use when asked to plan, scope, or brainstorm new features.
---

# Feature Plan

Produce a feature implementation plan and write it to `docs/reports/YYYY-MM-DD-feature-plan-<slug>.md`.

## Mode selection

- **Argument given** → skip to "Plan a named feature" using the argument as the feature.
- **No argument** → run "Brainstorm backlog" first, then ask the user which candidate to plan in detail, then continue with "Plan a named feature" for their pick.

## Phase 0 — Ground truth (always, before any agents)

1. Get today's date: `Get-Date -Format yyyy-MM-dd`.
2. Read `Agent.md` (architecture and testing conventions all plans must follow).
3. Read the memory note / constraint: **exports are v4 identity-bound with no v2/v3 back-compat** — any feature touching export/import must respect the v4 format (see `data/export/` — `VaultExporter`, `VaultImporter`).
4. Skim `app/src/main/java/md/borisveriga/pados/` top-level packages so agent prompts can reference real paths.

## Brainstorm backlog (no-argument mode)

Launch these agents **in a single message**:

1. **Codebase-driven ideas** (`Explore`, thoroughness: very thorough)
   Survey the app's current feature set (vault, generators, audit, export/import, biometric unlock, tags, pinning) and identify natural extensions and gaps. Consider at least: Android Autofill service, passkey/credential-manager support, breach/leak checking, browser extension companion, wearable/widget, secure notes/attachments, folders/vault sharding, password history, emergency access. For each idea report: what exists today that it builds on (with file paths), and rough integration difficulty.

2. **Competitor survey** (`general-purpose`, needs WebSearch)
   Research current feature sets of Bitwarden, KeePassDX, Proton Pass, and 1Password (mobile). Follow the deep-research pattern: multiple searches, cross-check claims across at least two sources, cite URLs. Identify (a) table-stakes features Pados lacks, (b) differentiators feasible for an offline-first, no-cloud password manager. Note explicitly which competitor features conflict with Pados's offline/no-server privacy posture.

Then synthesize a **prioritized backlog** (table: feature, user value, effort, fits-offline-posture, notes) and use AskUserQuestion to let the user pick the candidate (offer the top 3–4). Continue below with their pick.

## Plan a named feature

Launch these agents **in a single message**:

1. **Affected code & reuse** (`Explore`, thoroughness: very thorough)
   For feature "<feature>": find every code path it touches, and existing utilities/patterns to reuse (repositories, use cases, ViewModels, Compose components, navigation graph). Cite `file:line`. Flag where the feature contradicts existing architecture from `Agent.md`.

2. **Data model & crypto impact** (`Explore`, thoroughness: very thorough)
   Assess impact on: vault schema (`domain/model/`, `data/vault/`), encryption (`data/crypto/`), export format (v4 identity-bound, no v2/v3 back-compat — `data/export/`), preferences, and migrations needed for existing users' on-disk data. Cite `file:line`.

3. **UX & platform integration** (`Explore`, thoroughness: medium)
   How the feature fits the current navigation and screens (`ui/`), which new screens/components are needed, platform APIs/permissions required (manifest changes), and security-sensitive UI concerns (FLAG_SECURE, clipboard, biometric gating).

## Synthesis

Write `docs/reports/<date>-feature-plan-<slug>.md`:

```markdown
# Feature Plan: <feature> — <date>

## Summary & user value
## Current-state analysis        (what exists, what's reused — with file paths)
## Design                        (data model, crypto/export impact, UX flow)
## Implementation milestones     (ordered, each independently shippable if possible)
## Test plan                     (unit tests per Agent.md conventions; instrumentation if needed)
## Risks & open questions
## Out of scope
```

Every milestone names the files it creates/modifies. Security-relevant steps (key handling, export changes, new attack surface) must be called out explicitly.

Finish by giving the user a summary of the plan in chat plus the report file path. Do not implement and do not commit unless asked.
