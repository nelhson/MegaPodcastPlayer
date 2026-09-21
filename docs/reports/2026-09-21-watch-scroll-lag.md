# Watch: the laggy scroll down the one column

Date: 2026-09-21. Scope: `:wear` only. Follows `2026-09-13-watch-swipe-lag.md`, which fixed the
swipe between two pages and deferred exactly the two things this picks up.

## The complaint

Scrolling the watch screen stutters on the Galaxy Watch Ultra 2. The screen is one column now — the
pager the earlier report was about is gone — so this is the column itself, not a page transition.

## What was ruled out first

The obvious suspect, and the one the last round fixed, is recomposition. It is not this.

Built with `-Pmegapodcastplayer.compose.metrics=true` (still needing `--rerun-tasks`, as the last
report noted), every composable in `:wear` comes back `restartable skippable` — `WatchPlayerScreen`,
`NowPlayingHeader`, `QueueRow` and `DownloadedRow` included, despite `WatchPlayerUiState`,
`NowPlayingSnapshot` and `WatchEpisode` all being inferred unstable. Strong skipping compares them
by instance and the instances only change when the phone publishes, which `shouldPublish` keeps
rare. The `uiState`/`position` split holds. Nothing on that axis was left to win.

## What was actually happening

**1. `ScalingLazyColumn` was doing per-frame work proportional to the square of the visible items,
and could not reuse a row composition at all.** Reading Wear Compose foundation 1.6.2:

- `ScalingLazyListScope` takes a key and *nothing else*. There is no `contentType`, so every item on
  the screen shared one reuse pool typed `null`, so a scrolled-away queue row's composition could be
  offered to a downloaded-row slot, mismatch, and be thrown away. That pool still reused correctly
  while scrolling *within* a section, where the slot leaving and the slot arriving are the same
  shape; what it could not do is tell the two row types apart at the boundary between them.
- Every item is wrapped in an extra `Box` whose `graphicsLayer` block runs each frame and does
  `state.layoutInfo.internalVisibleItemInfo()` followed by `fastFirstOrNull { it.index == index }` —
  a scan of the visible items, per item, per frame — before setting scale, alpha and a translation.

**2. Three trips to the system server, on the main thread, mid-scroll.**
`rememberReduceMotion()` was called inside `Waveform`, which lives inside `NowPlayingHeader`, which
was a list `item {}`. Scrolling the header out of view ran `unregisterContentObserver`; scrolling it
back ran `Settings.Global.getFloat` in the `remember` initialiser — during composition — and then
`registerContentObserver` as the `DisposableEffect` was applied. It is a per-screen reading that was
being taken per visibility toggle.

Smaller: the header rebuilt a `Brush.verticalGradient` on each composition (a brush is a shader's
cache key) and stacked a `clip` node under the `background` that draws through it.

A third candidate — the waveform's infinite transition asking for a frame every frame during a
swipe — was tried and rejected; see below.

## What changed

- **`ScalingLazyColumn` → `TransformingLazyColumn`**, with the matching `ScreenScaffold` overload
  and `rememberTransformationSpec()`. Each item is told its own scroll progress instead of the
  column looking it up by index every frame.
- **Every item carries a `contentType`** as well as a key — `queueRow`, `downloadedRow`,
  `listHeader`, and one apiece for the fixed controls — so rows of a shape hand their composition on
  to the next row of that shape.
- **One `scrollTransform` modifier** applies the spec to every item: `transformedHeight` plus a
  `graphicsLayer` calling `applyContainerTransformation` — the half that carries the scale and the
  recentring translation, which must match the slot `transformedHeight` reserves. Deliberately not
  the Material `transformation` parameter that `Button` and `ListHeader` accept, because half this
  column (the transport rows, the waveform header) is not a surface, and a column where only some
  items taper reads as a bug.
- **The reduce-motion setting is read once, above the list**, and handed to the header.
- **The header's brush is remembered**, and the shape goes to `background(brush, shape)` rather than
  to a `clip` node above it.
- Every private composable on the screen now takes a `modifier`, which is how the transformation
  reaches it and is what they should have taken anyway.

Metrics re-run afterwards: all 18 composables still `restartable skippable`.

## Two things this got wrong first, and an independent review caught

Both were merged in #7 and fixed immediately after in #8. They are recorded here rather than
quietly corrected, because each is a trap the next person to touch this file can fall into.

**`scrollTransform` applied the wrong half of the spec.** `ResponsiveTransformationSpecImpl` splits
the work: `applyContentTransformation` sets `compositingStrategy` and `alpha` and *nothing else*,
while `applyContainerTransformation` is what sets `scaleX`, `scaleY` and the recentring
`translationY = -height * (1 - scale) / 2`. Meanwhile `getTransformedHeight` has already shrunk the
item's layout slot to `scale * height`. Pairing the shrunken slot with the content transformation
gives an item that reserves less space and still draws at full size without recentring — which is
not a smaller item but a full-size one overlapping its neighbour, with the taper gone entirely. The
screen would have opened with rows crowding into each other at both edges of the round display.

**The waveform pause was not a pause.** `moving = false` is the *stopped* shape — every bar at
`WAVEFORM_REST` — so stopping the transition during a scroll made the bars announce "nothing is
playing" through every flick, contradicting the one thing the header's own KDoc says the waveform is
for. The transition also sat in a different composition group, so it restarted at phase zero and
jumped the wave along the row when the finger lifted. And the frames it bought back were a redraw of
an 18 dp canvas that already has its own layer, against a scroll producing a frame per vsync
regardless. Reverted; the waveform runs throughout.

**Why nothing caught either.** Both are visual, and `:wear` is the only Compose module in this repo
with no Roborazzi goldens — `configureWearCompose` never called `configureScreenshotTests()`. Layout
bounds cannot catch them either: `transformedHeight` moves the layout slot identically in the broken
and the fixed version, and the whole difference lives in the `graphicsLayer`. A golden of this
screen is the only mechanical guard, and it is the first thing on the deferred list below.

## Tests

Wear module: 97 tests, all passing; detekt clean; `lintDebug` found no new issues.

- `WatchPlayerScreenTest` gains `a watch with animations turned off still gets its header`, which
  pins the hoisted reduce-motion read end to end by zeroing `ANIMATOR_DURATION_SCALE`.
- `what is downloaded on the phone is listed under the queue` changed technique, not claim. It read
  the bounds of both section headers after one scroll; `TransformingLazyColumn` keeps a tighter
  window than `ScalingLazyColumn` did, so the queue header is disposed by the time the downloads
  header arrives, and the fetch failed to find a node rather than failing an ordering assertion. It
  now walks the boundary in two steps — header above its own row, that row above the next header —
  which is the pattern `the queue sits right under the moment button` already uses and documents,
  and which covers one more boundary than the original did.

## Not verified on hardware

Same caveat as last time, same reason: the watch (`SM_L715F`) dropped to `offline` on wireless
debugging partway through and would not come back with `adb reconnect`. Nothing here has been felt
on the wrist. The check, once it is reachable: install both sides from the same build, play
something, and scroll the column repeatedly — no hitch as the drag begins, no rhythmic hitch while
an episode plays, and the queue and downloaded sections scroll at the same cost as the controls
above them. Look hardest at the top and bottom edges of the round screen: rows should taper and fade
as they leave, and must not crowd into or overlap each other. That is the failure mode #7 shipped
and #8 fixes, and it is the one nothing in the module can check.

`dumpsys gfxinfo md.borisveriga.megapodcastplayer framestats` around a scripted `input swipe` loop
would turn that into numbers, and is worth doing before anyone reaches for the next item below.

## The ceiling nobody has raised yet

`wear-debug.apk` is 76 MB (79,659,224 bytes) and contains **no** `assets/dexopt/baseline.prof` — only the
profileinstaller version marker. There is no `androidx.baselineprofile` plugin and no benchmark
module in the repo, and `.claude/project-profile.md` records that the watch is only ever installed
as a debug build. All of Compose's scroll, layout and draw paths are running interpreted on a watch
CPU.

This change removes real work and is worth having on its own terms, but it is very likely the
smaller half of the problem. A baseline profile is the larger half, and it is still deferred.

## Still deferred

Roborazzi goldens for `:wear` — `configureWearCompose` needs `configureScreenshotTests()` and the
two roborazzi dependencies, which is all that stands between this module and the suite every other
Compose module already has; a baseline profile; Compose stability configuration for `:core:wearprotocol` types; `@Immutable` on
the state; raising `QUEUE_BUTTON_SIZE` to the 48 dp touch-target floor via
`Modifier.touchTargetAwareSize`, which is unrelated to scrolling but was noticed in the same file.
