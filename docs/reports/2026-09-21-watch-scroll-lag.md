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
  the screen shared one reuse pool typed `null`: a scrolled-away queue row's composition was offered
  to a downloaded-row slot, mismatched, and was thrown away. Reuse never once succeeded here.
- Every item is wrapped in an extra `Box` whose `graphicsLayer` block runs each frame and does
  `state.layoutInfo.internalVisibleItemInfo()` followed by `fastFirstOrNull { it.index == index }` —
  a scan of the visible items, per item, per frame — before setting scale, alpha and a translation.

**2. Two binder calls into the system server, on the main thread, mid-scroll.**
`rememberReduceMotion()` was called inside `Waveform`, which lives inside `NowPlayingHeader`, which
was a list `item {}`. Scrolling the header out of view ran `unregisterContentObserver`; scrolling it
back ran `Settings.Global.getFloat` *and* `registerContentObserver`, during composition, on a scroll
frame. It is a per-screen reading that was being taken per visibility toggle.

**3. The waveform competed with the scroll for frames.** An infinite transition asks for a frame
every frame for as long as it runs. The last report gave the canvas its own layer so the frame only
repaints the bars — but the frame is still requested, and while the phone plays it was requested
throughout every swipe, on the one item most likely to be halfway off the screen at the time.

Smaller: the header rebuilt a `Brush.verticalGradient` on each composition (a brush is a shader's
cache key) and stacked a `clip` node under the `background` that draws through it.

## What changed

- **`ScalingLazyColumn` → `TransformingLazyColumn`**, with the matching `ScreenScaffold` overload
  and `rememberTransformationSpec()`. Each item is told its own scroll progress instead of the
  column looking it up by index every frame.
- **Every item carries a `contentType`** as well as a key — `queueRow`, `downloadedRow`,
  `listHeader`, and one apiece for the fixed controls — so rows of a shape hand their composition on
  to the next row of that shape.
- **One `scrollTransform` modifier** applies the spec to every item: `transformedHeight` plus a
  `graphicsLayer` calling `applyContentTransformation`. Deliberately not the Material `transformation`
  parameter that `Button` and `ListHeader` accept — that transforms a surface's container and its
  content on separate curves, and half this column (the transport rows, the waveform header) is not
  a surface. One curve for everything is what the screen looked like before.
- **The reduce-motion setting is read once, above the list**, and handed to the header.
- **The waveform stops while the column is scrolling.** `scrolling` reaches it as a lambda and is
  called inside `Waveform`, so a scroll starting or stopping recomposes seven bars and nothing else.
  Two recompositions per swipe in exchange for sixty animation frames a second.
- **The header's brush is remembered**, and the shape goes to `background(brush, shape)` rather than
  to a `clip` node above it.
- Every private composable on the screen now takes a `modifier`, which is how the transformation
  reaches it and is what they should have taken anyway.

Metrics re-run afterwards: all 18 composables still `restartable skippable`.

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
the waveform would have been running, and the queue and downloaded sections scroll at the same cost
as the controls above them. Then confirm the waveform resumes when the finger lifts.

`dumpsys gfxinfo md.borisveriga.megapodcastplayer framestats` around a scripted `input swipe` loop
would turn that into numbers, and is worth doing before anyone reaches for the next item below.

## The ceiling nobody has raised yet

`wear-debug.apk` is 79 MB and contains **no** `assets/dexopt/baseline.prof` — only the
profileinstaller version marker. There is no `androidx.baselineprofile` plugin and no benchmark
module in the repo, and `.claude/project-profile.md` records that the watch is only ever installed
as a debug build. All of Compose's scroll, layout and draw paths are running interpreted on a watch
CPU.

This change removes real work and is worth having on its own terms, but it is very likely the
smaller half of the problem. A baseline profile is the larger half, and it is still deferred.

## Still deferred

A baseline profile; Compose stability configuration for `:core:wearprotocol` types; `@Immutable` on
the state; raising `QUEUE_BUTTON_SIZE` to the 48 dp touch-target floor via
`Modifier.touchTargetAwareSize`, which is unrelated to scrolling but was noticed in the same file.
