---
name: feature-plan
description: Plan a new feature for BPodcat. With an argument (e.g. /feature-plan sleep timer) it produces a detailed implementation plan for that feature; with no argument it first brainstorms and prioritizes a feature backlog, then plans the top candidate. Use when asked to plan, scope, or brainstorm new features.
---

# Feature Plan

Produce a feature implementation plan and write it to
`docs/reports/YYYY-MM-DD-feature-plan-<slug>.md` (create `docs/reports/` if it does not exist).

## Mode selection

- **Argument given** → skip to "Plan a named feature" using the argument as the feature.
- **No argument** → run "Brainstorm backlog" first, ask Boris which candidate to plan in detail with
  AskUserQuestion, then continue with "Plan a named feature" for his pick.

## Phase 0 — Ground truth (always, before any agents)

1. Get today's date: `Get-Date -Format yyyy-MM-dd`.
2. **There is no `Agent.md` or `CLAUDE.md`.** The conventions a plan must follow are:
   - `build-logic/convention/` — the module baseline every new module would inherit.
   - Boris's global standard: KDoc on every file, class and function, plus unit tests for new or
     changed logic, run before the work is considered done.
   - `config/detekt/detekt.yml` — a plan that requires suppressing a rule should say so up front.
3. Skim the module graph so agent prompts reference real paths: `:app`, `:wear`, pure-JVM
   `:core:model` and `:core:wearprotocol`, then `:core:common`, `:core:database`, `:core:datastore`,
   `:core:network`, `:core:youtube`, `:core:media`, `:core:data`, `:core:designsystem`,
   `:core:testing`, and `:feature:{library,downloads,search,podcast,player,settings}`. Sources are at
   `<module>/src/main/kotlin/md/borisveriga/bpodcat/…`.
4. Carry these standing constraints into every agent prompt — they are the ones features break:
   - **Episode audio URLs are identities, not addresses.** A YouTube episode stores the
     `youtube://video/<id>` sentinel and it doubles as the Media3 cache key; a feed URL is hashed
     into the podcast id and compared by a unique index. Changing how either is spelled re-imports
     or orphans everything already stored.
   - **The watch pairing is package name plus signing certificate.** Anything touching the
     application ID, signing, or `:core:wearprotocol` affects whether the watch works at all.
   - **`:core:model` and `:core:wearprotocol` are pure JVM.** A feature that needs an Android type in
     either is a design problem, not a dependency to add.
   - **Untrusted feed input reaches the media stack.** New URL or file handling must go through
     `isPlayableMediaUrl`, and say so in the plan.

## Brainstorm backlog (no-argument mode)

Launch these agents **in a single message**:

1. **Codebase-driven ideas** (`Explore`, thoroughness: very thorough)
   Survey what exists today — subscriptions and refresh, iTunes search, YouTube playlists as shows,
   queue and reordering, downloads with a keep-limit sweep, playback with speed and skip controls,
   the Wear companion, new-episode notifications — and identify natural extensions and gaps.
   Consider at least: sleep timer, chapters, playback statistics, silence trimming and volume boost,
   OPML import/export, home-screen widget, Android Auto, per-show playback settings, smart playlists,
   transcript or search-within-episode, cross-device position sync. For each: what exists today that
   it builds on (with file paths) and rough integration difficulty.

2. **Competitor survey** (`general-purpose`, needs WebSearch)
   Research current feature sets of Pocket Casts, AntennaPod, Podcast Addict and Overcast. Multiple
   searches, cross-check claims across at least two sources, cite URLs. Identify (a) table-stakes
   features BPodcat lacks, (b) differentiators that suit an offline-first app with first-class
   YouTube-playlist support and a Wear companion. Note explicitly which competitor features depend on
   a server-side account, since BPodcat has none.

Then synthesize a **prioritized backlog** (table: feature, user value, effort, notes) and use
AskUserQuestion to let Boris pick from the top 3–4. Continue below with his pick.

## Plan a named feature

Launch these agents **in a single message**:

1. **Affected code & reuse** (`Explore`, thoroughness: very thorough)
   For feature "<feature>": find every code path it touches and the existing patterns to reuse —
   repositories in `:core:data`, DAOs in `:core:database`, `UserPreferencesDataSource` in
   `:core:datastore`, the design system components, `BPodcatNavigation`. Cite `file:line`. Flag where
   the feature would cut across a module boundary the graph currently forbids.

2. **Data & media impact** (`Explore`, thoroughness: very thorough)
   Assess impact on: the Room schema (`:core:database` — entities, DAOs, and whether a migration in
   `Migrations.kt` is needed, since real installations hold real data), the media stack
   (`:core:media` — the data source chain, download cache keys, `PlaybackService`), preferences, and
   the watch contract (`:core:wearprotocol` — a change here is a two-sided release, because the
   phone and watch update independently). Cite `file:line`.

3. **UX & platform integration** (`Explore`, thoroughness: medium)
   How it fits the current navigation and screens, which new components are needed and whether
   `:core:designsystem` already has one, platform APIs and permissions required (manifest changes),
   foreground-service implications if it touches playback or downloads, and how it degrades on the
   watch's small round screen if it surfaces there.

## Synthesis

Write `docs/reports/<date>-feature-plan-<slug>.md`:

```markdown
# Feature Plan: <feature> — <date>

## Summary & user value
## Current-state analysis        (what exists, what's reused — with file paths)
## Design                        (data model, migration, media/watch impact, UX flow)
## Implementation milestones     (ordered, each independently shippable if possible)
## Test plan                     (unit tests per the standard in Phase 0; what cannot be unit-tested
                                  and why)
## Risks & open questions
## Out of scope
```

Every milestone names the files it creates or modifies. Anything touching stored identities, a Room
migration, or the watch contract must be called out explicitly — those are the changes that cannot be
undone by shipping again.

Finish by giving Boris a summary in chat plus the report file path. Do not implement and do not
commit unless asked.
