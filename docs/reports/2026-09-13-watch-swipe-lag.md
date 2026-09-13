# Watch: the laggy swipe to the episodes page

Date: 2026-09-13. Scope: `:wear` only.

## The complaint

Swiping from the now-playing page to the episodes page (phone queue, downloaded on phone, on
this watch) stuttered badly on the Galaxy Watch Ultra 2.

## What was actually happening

The whole watch UI is one screen, `WatchPlayerScreen`: a two-page `HorizontalPager` fed by a single
`StateFlow<WatchPlayerUiState>` collected above the pager. Three things made a swipe expensive,
and they compounded.

1. **The clock recomposed the whole screen.** `WatchPlayerViewModel.uiState` combined a 1 Hz
   ticker and, during the watch's own playback, a 500 ms poll of the player. `WatchPlayerUiState`
   carried `positionMs` and `progress`, so every tick delivered a new, non-equal state object to
   the top of the tree. Nothing below could skip: the state holds `List`/`Map` fields, the pager's
   content lambda captured it, and the rows had no keys. Every second, both pages, both
   `ScalingLazyColumn` blocks, every row's string formatting and the `copyable`/`arriving` getters
   (each read three times) ran again — including in the middle of a swipe.
2. **The destination page was built on the first frame of every swipe.** Wear's `HorizontalPager`
   defaults to `beyondViewportPageCount = 0`: the page being swiped to was composed from scratch as
   the drag began and thrown away once the pager settled. The same default is why the episodes list
   forgot its scroll position every time it was left.
3. **A copy in flight was a recomposition storm.** `WatchEpisodeStore` republished transfer
   progress every 16 KB. Over Bluetooth that is several new states a second, each doing all of (1).

The page transition animation itself was not the problem: `AnimatedPage` reads the pager offset in
the draw phase and does not recompose. One smaller contributor was the `Waveform` canvas, which
invalidates every animation frame while playing and had no layer of its own, so each frame redrew
the whole page layer that the pager was concurrently moving.

## What changed

- **The clock left the screen state.** `WatchPlayerUiState` no longer has `positionMs`/`progress`;
  they are a separate `PlaybackPosition`. `watchPlayerFrame()` (formerly `watchPlayerUiState()`)
  builds both from one reading of the inputs. The snapshot inside the state has its `positionMs`
  zeroed, so polling the watch's player does not change the state either.
- **Two flows from the view model.** `frame` is the old combine; `uiState` and `position` are
  `map`+`stateIn` views of it. A `StateFlow` drops a value equal to the one it holds, so `uiState`
  is silent while only the clock moves. The view model's own reads go through `frame.value`.
- **The screen reads the position only where it draws it.** The stateless screen takes
  `position: () -> PlaybackPosition`. The indicator reads it in its progress lambda, the scrub thumb
  in an `offset {}` lambda, the scrub-commit wait in a `snapshotFlow`, and the time label — a new
  `PositionLabel` composable — is the one place it is read in composition. The Compose compiler
  metrics confirm it: `EpisodesPage` takes nothing that moves with the clock, and `PositionLabel`
  is the only composable that reads the position while composing.
- **Both pages stay composed.** `beyondViewportPageCount = 1`. A swipe never starts by building
  the page it is going to, and the episodes list keeps its place. Checked against Wear Compose
  foundation 1.6.2: `LocalScreenIsActive` and `hierarchicalFocusGroup` still follow the current
  page, so the hidden page cannot take rotary focus.
- **Rows are keyed** (`queue:`, `phone:`, `watch:`, `arriving:` prefixes, because one episode can be
  queued on the phone and downloaded there at once), and `copyable`/`arriving` are read once per
  composition.
- **The waveform draws in its own layer** (`graphicsLayer()` on the canvas).
- **Transfer progress reports every 256 KB** instead of 16 KB. A 20 MB episode moves a 200 px bar
  about a pixel per 100 KB, so nothing visible was lost.

## Tests

Wear module: 150 tests, all passing; detekt clean.

- `WatchPlayerUiStateTest`: a ticking clock, and the watch's player being polled, change the
  position and leave the state equal; the snapshot's position is not carried into the state; the
  cues are.
- `WatchPlayerViewModelTest`: a fresh phone reading and a fresh player poll reach `position`
  without `uiState` emitting. Driven by data rather than the ticker, because
  `isReturnDefaultValues` makes `SystemClock.elapsedRealtime()` return 0 on the JVM. One existing
  test lost an expected emission for exactly the reason this change exists (moving the bar no
  longer re-emits the screen state).
- `WatchPlayerScreenTest`: the time label follows the position lambda; the episodes list keeps its
  place when paged away and back. Because both pages are now composed, the helpers pick the list
  on the page being shown, and two "the other page is not here" assertions became "not displayed".

## Not verified on hardware

Neither device was reachable over adb when this was done, so the swipe has not yet been felt on
the watch. The next time both are connected, install both sides from the same build and check:
swipe repeatedly while an episode plays (no rhythmic hitch, no hitch as the drag starts); scroll
the episodes list, swipe away and back (it stays where it was); start a copy to the watch and
swipe while it arrives; tap the bar and turn the bezel (the commit still waits for a pause).

Two caveats for that check. The watch is sideloaded as a **debug** build (no R8, debuggable),
which makes all of Compose on a watch slower than a release build; this change removes real work,
but what remains is a debug build's cost. And the Compose metrics flag
(`-Pmegapodcastplayer.compose.metrics=true`) only wrote its reports when the compile task was
forced with `--rerun-tasks`; an ordinary build with the flag came back with nothing, which is worth
a look in `build-logic` some day.

## Deferred

Compose stability configuration for `:core:wearprotocol` types (their rows are still `unstable`
in the metrics, though strong skipping compares them by instance and the instances only change
when the phone publishes), `@Immutable` on the state, `TransformingLazyColumn`, and a baseline
profile.
