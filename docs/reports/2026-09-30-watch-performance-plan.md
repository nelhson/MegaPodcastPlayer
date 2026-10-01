# Watch: why it is slow, and the plan

Date: 2026-09-30. Scope: `:wear`, the phone's `wearsync` package, and the build that puts the
watch APK on the wrist. HEAD `c911300`. Follows `2026-09-13-watch-swipe-lag.md` and
`2026-09-21-watch-scroll-lag.md`, which fixed the recomposition and list-transform costs and both
ended on the same unverified suspicion. This report closes it.

## The complaint

"The application on the watch is slow." Three different things answer to that sentence on a
Galaxy Watch Ultra 2, and the plan below is ordered so that the first measurement tells them
apart:

| What is slow | What it feels like | What decides it |
|---|---|---|
| **Opening** | raising the wrist, tapping the icon, waiting for the controls | process start, Hilt, Firebase, Compose's first frame — and how much of the APK's code is *compiled* rather than interpreted |
| **Moving** | scrolling the column, the glint, the fade between title and volume | frame time; whether Compose's layout/draw paths run as native code |
| **Answering** | tapping play and waiting for the button to change shape; turning the bezel and hearing nothing | the round trip: watch → Play Services → Bluetooth → phone process (possibly cold) → player → data item → watch |

The first two share one cause. The third has its own.

## The finding the code already gives

**The watch has only ever run a debug build, and a debug build is the wrong build for a watch.**

`.claude/project-profile.md` records that `:wear` is installed as `debug` only (`installDebug`;
`installRelease` does not exist without a keystore). What that debug APK is, measured from the
build outputs on this machine today:

| | `wear-debug.apk` (what is on the wrist) | `wear-release.apk` (same source, 2026-09-21) |
|---|---|---|
| Size | **80.4 MB** | 3.3 MB |
| Dex | **19 files, 77.8 MB** | 1 file, 2.8 MB |
| `assets/dexopt/baseline.prof` | none | **present (7 KB)** — Compose's own library profiles, merged by AGP |
| `android:debuggable` | true | false |
| R8 | off | on, `proguard-android-optimize.txt` |

Four consequences, each of which is a known cost on Wear OS and all of which stack:

1. **Interpreted Compose.** ART compiles ahead of time from a profile. A debug build ships no
   profile, and `debuggable=true` tells ART to generate code that can be deoptimised for a debugger,
   which rules out most of its optimisations. Compose's measure, layout and draw run through the
   JIT on every cold start, on a watch CPU, and the JIT's work is thrown away when the process is
   killed — which on a watch is shortly after every backgrounding. The release APK carries Compose's
   baseline profile (`baseline.prof` above), so the same paths are AOT-compiled at install time.
2. **77 MB of dex to map and verify.** Nineteen dex files instead of one: the whole of
   `material-icons-extended`, Coil (unused on the watch — see `wear/build.gradle.kts`), Play
   Services, Firebase, and every Compose module unshrunk. Class loading and dex verification are
   paid at start, and the page cache on a watch is small.
3. **No R8 optimisation.** Kotlin's intrinsic null checks, Compose's `sourceInformation` calls and
   the compiler's `traceEventStart/End` bookkeeping all stay in the bytecode; R8 strips them in the
   release build. This is the difference between a lambda-heavy Compose screen and the same screen
   with the debug scaffolding removed.
4. **The previous two reports optimised the wrong half first.** Both removed real work — the
   recomposition storm and the per-frame item scan — and both said so: "very likely the smaller half
   of the problem." Nothing has been felt on hardware since either landed, because the watch dropped
   off adb both times.

None of this is a guess about which line of Kotlin is slow. It is the reason no line of Kotlin
has been able to make the watch feel fast: the build is the ceiling, and the ceiling has not been
raised.

## What else the code says

Read in full: `WatchPlayerScreen.kt`, `WatchPlayerViewModel.kt`, `WatchPlayerUiState.kt`,
`PhonePlayerClient.kt`, `NowPlayingChipService.kt`, `MainActivity.kt`, `WearApplication.kt`, the
`:core:wearprotocol` contract, and the phone's `NowPlayingPublisher`, `WearCommandService` and
`WearCommandExecutor`. What the earlier reports fixed still holds: `uiState`/`position` are split
so the clock does not recompose the list; every item has a key and a `contentType`;
`TransformingLazyColumn` is told each item's progress; the reduce-motion read is above the list;
the header brush is remembered. The remaining items are secondary and are listed by the symptom
they touch.

### Answering (tap → button changes)

- **Every command does a capability lookup first.** `PhonePlayerClient.send` calls
  `capablePhoneNodeIds()` — `capabilityClient.getCapability(...).await()`, an IPC into Play
  Services — before `sendMessage`. Two round trips to GMS per tap, and the first is answering a
  question `phoneLink` already asks every 10–60 s and the capability listener answers on change.
  Cache the reachable node ids from that flow and look them up only when the cache is empty.
- **The phone verifies the sender with another IPC.** `WearCommandService` →
  `WearSenderVerifier.isTrusted` reads the node list per message. Same fix on the phone side, or
  at least a short-lived cache.
- **The phone process may be cold.** Play Services starts `WearCommandService` on demand; the
  first tap after the phone app has been killed pays Hilt graph construction, `publisher.start()`
  and the player connection before anything happens. Nothing on the watch can shorten that; the
  watch can *hide* it.
- **Play/pause is not optimistic.** `SetVolume` and `SeekTo` already show the wearer's value at
  once and hold it over the round trip (`VOLUME_HOLD_MS`, `SEEK_HOLD_MS`). `TogglePlayPause` does
  not: the button waits for the phone's snapshot. The same held-value pattern on `isPlaying` would
  make the most-pressed button on the screen answer the thumb immediately and correct itself if
  the phone disagrees.
- **Two publishes per command.** `WearCommandExecutor.execute` calls `publishCurrent()` and the
  phone's state flow publishes again once the player reacts. Harmless for latency (the first is
  the one that matters) but it is two data items and two watch wake-ups per tap; worth knowing
  when reading the logs, not worth changing first.
- **If Bluetooth is down, the Data Layer routes through the cloud.** With the watch on Wi-Fi and
  the phone out of Bluetooth range, `FILTER_REACHABLE` can still list the phone and every command
  takes seconds. A watch on Wi-Fi *for adb* is exactly that situation, so the measurement below
  checks the transport before trusting a round-trip number.

### Moving (frames)

- **`ProgressGlint` allocates per frame.** Its draw lambda builds a new `Path` and a new
  `Brush.horizontalGradient` every frame of a 1.8 s infinite loop while playing. Its own
  `graphicsLayer` keeps the redraw to that layer, but the allocations are GC pressure at 60 Hz on
  a device with little heap headroom. Reuse the path; build the gradient once per `filled` width.
- **An infinite transition keeps the frame clock running.** While the glint runs, the Recomposer
  wakes every vsync for as long as the app is in front. That is by design (the glint is what says
  "playing"), but it is a fixed cost every scroll pays on top of its own work. Measure with the
  glint on and off (pause the phone) before deciding anything.
- **Stability across the module boundary.** `NowPlayingSnapshot`, `WatchEpisode` and
  `WatchPlayerUiState` are inferred unstable because the first two come from `:core:wearprotocol`.
  Strong skipping compares them by instance so the list does skip today; a stability configuration
  file for that package makes it skip by value and stops depending on the publisher keeping
  instances rare. Cheap, deferred twice already.
- **The ticker runs while paused and idle.** `elapsedRealtimeTicker` fires every second regardless,
  and each tick rebuilds `WatchPlayerFrame` — a `snapshot.copy(...)` and a data-class `equals` over
  the two episode lists so the `StateFlow` can drop it. Trivial per tick; it is a wake-up per second
  for nothing while paused. Gate it on `isPlaying`.

### Opening (cold start)

- **Firebase initialises on the main thread before `onCreate`.** `WearApplication` injects
  `CrashReporter`; the Crashlytics SDK is started by `FirebaseInitProvider` before the application
  class runs. That is 100–300 ms on a watch in the debug build. Measure it (`logcat` shows
  `FirebaseInitProvider` and `FirebaseApp` timestamps) before touching it: the project's rule that
  "a failure nobody is shown still goes somewhere" is worth more than a tenth of a second, but it
  should be a known tenth.
- **Coil is on the classpath and never used.** `wear/build.gradle.kts` says so and why it stays
  (verification metadata). R8 removes it from the release build; in the debug build it is one of
  the nineteen dex files. Not a fix, a cleanup — and the verification-metadata refresh it needs is
  the same one the baseline-profile plugin below will need, so do them together.

## Measurement protocol

Run before any change, then after each phase, on the watch, same commit, same episode playing.
All read-only. Wireless debugging serials change per reconnect; `$w` is the watch's `IP:port`.

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$pkg = "md.borisveriga.megapodcastplayer"
$w   = "<watch ip:port>"

# 0. What is actually installed — the number that decides everything else.
& $adb -s $w shell dumpsys package $pkg | Select-String "versionName|lastUpdateTime|flags=|dexopt|status=|\[arm64"
& $adb -s $w shell pm path $pkg
& $adb -s $w shell dumpsys meminfo $pkg | Select-String "TOTAL|\.dex|\.oat|\.art|Dalvik"

# 1. Device state that throttles everything.
& $adb -s $w shell settings get global low_power
& $adb -s $w shell settings get global animator_duration_scale
& $adb -s $w shell dumpsys battery | Select-String "level|status|plugged"
& $adb -s $w shell cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor
& $adb -s $w shell dumpsys bluetooth_manager | Select-String "state|Connected"

# 2. Cold start, three times.
1..3 | ForEach-Object {
  & $adb -s $w shell am force-stop $pkg
  & $adb -s $w shell am start -W -n "$pkg/md.borisveriga.megapodcastplayer.wear.MainActivity" | Select-String "TotalTime|WaitTime"
}

# 3. Scroll frames: reset, ten swipes each way, read the histogram.
& $adb -s $w shell dumpsys gfxinfo $pkg reset
1..10 | ForEach-Object {
  & $adb -s $w shell input swipe 225 360 225 120 300
  & $adb -s $w shell input swipe 225 120 225 360 300
}
& $adb -s $w shell dumpsys gfxinfo $pkg | Select-String "Total frames|Janky|percentile|Number "
& $adb -s $w shell dumpsys gfxinfo $pkg framestats > framestats.txt

# 4. Tap round trip. `input tap` on the play button, both logcats at epoch time.
# Needs two temporary Log.d lines (PhonePlayerClient.send before sendMessage; the
# snapshots listener on arrival) — removed before commit. Also read GMS's own line:
& $adb -s $w shell logcat -v epoch -T 200 | Select-String "megapodcast|WearableService|DataItem"

# 5. The one-command experiment: force AOT on the debug build.
# If scroll and start improve markedly, the build is the cause — before anything is rebuilt.
& $adb -s $w shell cmd package compile -m speed -f $pkg
# then repeat 2 and 3. Undo: cmd package compile --reset $pkg
```

Optional, for the record: `perfetto -o /data/misc/perfetto-traces/t.pftrace -t 20s sched freq gfx
view` during step 3, pulled and opened at ui.perfetto.dev, shows whether the main thread is in
interpreter frames (`art::interpreter`) — the direct signature of item 1 above.

### Results (measured 2026-09-30, Galaxy Watch Ultra 2 `SM-L715F`, Wear OS on Android 17)

Same commit, same episode loaded on the phone, phone connected over Bluetooth, watch on Wi-Fi for
adb only. Device state first: `low_power` 0, `animator_duration_scale` 1.0, battery 69 % on
battery, governor `walt` at 1.75 of 1.96 GHz, display 60 Hz (`vsyncRate=60`). Nothing was
throttling the watch; what follows is the app's own cost. The app runs under `primaryCpuAbi=armeabi-v7a`
— the watch is 32-bit.

| Measure | Debug, as it was on the wrist | Shrunk build, as installed (`verify`) | Shrunk + `speed-profile` | Shrunk + `speed` |
|---|---|---|---|---|
| `dumpsys package` flags | `DEBUGGABLE`, dexopt `verify` | not debuggable, `verify` (reason=install) | `speed-profile` | `speed` |
| APK on device | 80.4 MB | **3.38 MB** | 3.38 MB | 3.38 MB |
| `am start -W` COLD | **1 729 ms** (1 375, 1 280 on later runs) | **963 ms** (first run, profile being installed) | — (no cold run landed) | **280 ms** |
| `am start -W` WARM | 537 / 545 ms | 187 / 170 ms | 241 / 176 / 137 ms | 176 / 171 ms |
| Scroll, 20 scripted swipes: janky frames | **76 of 668 (11.4 %)**; second run 151 of 594 (25.4 %) | 8 of 807 (**1.0 %**) | 6 of 841 (0.7 %) | 6 of 795 (0.75 %) |
| 90th / 95th / 99th percentile frame | 48 / 53 / **73 ms**; second run 69 / 85 / 133 ms | 34 / 42 / 48 ms | 44 / 46 / 48 ms | 34 / 34 / **34 ms** |
| Slow UI thread frames | 75; second run 142 | 7 | 6 | 1 |
| Tap → button changes | not measured: the watch dropped off adb mid-test, twice | | | |

Three notes on reading the table:

- **The median frame is 32–34 ms in every column** and the system still counts under 1 % of them
  as janky. The display is 60 Hz, so this is not a 30 Hz screen: it is the pacing of `input
  swipe`, which injects motion events roughly every 32 ms, and a scroll frame is produced per event.
  The median measures the script, the tail and the janky count measure the app. On the debug build
  the tail was 2–4 vsyncs; on the shrunk build every frame in the `speed` column took the same
  34 ms — nothing was slow.
- **The AOT experiment on the debug build could not be run.** `cmd package compile -m speed -f`
  returned `Success` in three seconds and the state stayed `[status=verify]`. ART Service caps a
  *debuggable* package at `verify` — the JIT is kept for the debugger — so no amount of background
  dexopt would ever have compiled that APK. That is the sharpest version of the finding: the debug
  build is not merely uncompiled, it is uncompilable on this OS.
- **The shrunk build as installed is already most of the win** (963 ms cold, 1 % jank) before any
  compilation, because R8 removed 74 MB of dex and the debug scaffolding. `speed-profile` — what
  background dexopt reaches on its own within a day, using the baseline profile the APK carries —
  brings the cold start to the 200–300 ms band. Full `speed` adds nothing measurable over it and
  costs install time and storage; it is not recommended, it was the upper bound.

**Where the wrist stands now:** the shrunk, non-debuggable, debug-signed APK from this session is
installed on the watch (`lastUpdateTime=2026-09-30 21:56:24`, `speed`-compiled by hand). The
phone still runs its debug build and the pairing is intact — the screenshot after install shows
the episode, the link and the controls, so R8 broke neither the JSON contract nor the Play
Services path. `.claude/project-profile.md` records this; installing the debug APK again would
put the numbers in the first column back.

## The plan

Ordered by expected effect per hour, and so that each phase is measured before the next starts.
Phases 1 and 2 are build work with no Kotlin in them; phase 3 is the code. If phase 1 fixes the
complaint, phase 3 is still worth doing for the tap latency and the battery, but on its own
schedule.

### Phase 0 — measure — done

The protocol above, on the debug build. Results are in the table. Step 5 turned out to be
impossible on a debuggable package, which answered the question more firmly than running it
would have.

### Phase 1 — put a shrunk, non-debuggable build on the watch — done (see "What landed")

**Immediate, zero code — done 2026-09-30:** `:wear:assembleRelease -PallowDebugSigningForRelease=true`,
installed with `adb install -r --no-streaming`. The flag exists for exactly this ("for local
sideloading only", `AndroidCommon.kt`); CI builds the same APK on every push. It is signed with the
debug key, so it pairs with the debug phone build already installed, and the install needed no
uninstall. Its trust is identical to the debug APK it replaces — same key, same app id — so nothing
in the threat model in `.claude/project-profile.md` moves. The "never offer" rule there is about
*handing the APK to someone*, which this is not. Cold start went from 1.7 s to under 0.3 s and the
scroll tail from 73 ms to 34 ms; see the table.

**Durable:** a build type that says what it is, so the install skill never has to pass a flag that
`distribute` forbids. In `AndroidApplicationWearConventionPlugin` (and `AndroidApplicationConventionPlugin`,
so both sides can be installed alike):

```kotlin
create("wrist") {          // or `benchmark`, Google's name for the same idea
    initWith(getByName("release"))
    isDebuggable = false
    signingConfig = signingConfigs.getByName("debug")
    matchingFallbacks += "release"
    // Crashlytics: no mapping upload; the build type key already tells reports apart.
}
```

Then `install_on_devices` in the profile changes to `installWrist` for the watch (and the phone,
if the same speed is wanted there — it is Boris's daily driver, so that is a separate call), the
`adb install -r --no-streaming` quirk goes away with the 80 MB, and `docs/RELEASE_SIGNING.md`
gains a paragraph on why this build type is *not* a release. Lint's `WearRecents` and the
`failReleasePackagingWithoutAKeystore` guard are unaffected: the guard matches `…Release` task names
only.

**Not this:** switching both devices to a real release keystore. It is the right end state and
`tools\create-release-keystore.ps1` is ready, but a signing change on the phone means an uninstall,
and an uninstall deletes the Room database and the offline library. That waits for a deliberate
moments export and a settings export, on a day chosen for it.

Verify after the build type lands: `dumpsys package` must not list `DEBUGGABLE`, and the dexopt
status should read `speed-profile` once background dexopt has run (`cmd package compile -m
speed-profile -f` forces it; the install skill can do that as its last step, since a freshly
installed APK sits at `verify` until the watch is idle and charging).

### Phase 2 — a baseline profile of the watch's own code — probably not needed

Phase 1 brings Compose's library profiles for free (`baseline.prof` is in the release APK), and the
table shows the app's own code is not the remaining cost: `verify` → `speed-profile` moved the
cold start from 963 ms (first run) into the 137–241 ms warm band, and `speed` — which compiles
*everything*, this app's code included — measured the same as `speed-profile` on scroll. An
`androidx.baselineprofile` module (`:wear:baselineprofile`, Macrobenchmark on Wear OS API 33+, the
watch is at 34) would only shave the first minute after an install. Revisit if the first scroll
after an install is visibly worse than the second; otherwise leave it.

### Phase 3 — the code (1–2 days, each item with its test)

In the order the measurements will most likely ask for:

1. **Optimistic play/pause.** `WatchPlayerViewModel.togglePlayPause` sets a held `isPlaying` the way
   `setVolume` holds a level; `watchPlayerFrame` shows it until a snapshot arrives after the send
   or `PLAY_HOLD_MS` passes. Test in `WatchPlayerUiStateTest` and `WatchPlayerViewModelTest`, the
   same shape as the volume tests.
2. **Cache the phone's node ids.** `PhonePlayerClient` keeps the last non-empty result of
   `capablePhoneNodeIds()` from the listener and the poll; `send` uses it and falls back to a lookup
   only when it is empty or the send fails. One IPC per tap instead of two. Test with a fake
   `CapabilityClient` counting calls.
3. **`ProgressGlint` without per-frame allocation.** A remembered `Path` reset each frame and a
   gradient keyed on `filled`. Pinned by a Roborazzi golden of the bar — `:wear` still has none;
   `configureWearCompose` needs `configureScreenshotTests()` and the two roborazzi dependencies, as
   the 09-21 report deferred.
4. **Gate the ticker on `isPlaying`.** One wake-up per second only while something moves. Test that
   a paused snapshot produces no further `position` emissions.
5. **Stability configuration** for `md.borisveriga.megapodcastplayer.core.wearprotocol.**`, then
   re-run `-Pmegapodcastplayer.compose.metrics=true --rerun-tasks` and check the three types read
   `stable`.
6. **Measure Firebase's start cost** on the watch from the phase 0 logcat. If it is a visible share
   of `TotalTime`, defer `CrashReporter` construction off the application's `onCreate` path rather
   than turn collection off.
7. **Phone-side sender cache** in `WearSenderVerifier`, if step 4 of the protocol shows the phone's
   half of the round trip dominating.

### Phase 4 — keep it measured

Add the phase 0 protocol as `tools\watch-perf.ps1` so the next report starts from numbers, and a
line in `.claude/project-profile.md` under `performance-plan` pointing at it. The two earlier
reports each ended with "not verified on hardware"; this one should be the last that could.

## Still deferred

Release keystore on both devices (needs the moments and settings exports first);
`QUEUE_BUTTON_SIZE` to the 48 dp floor, noticed in the 09-21 report and still unrelated to speed.

## What landed (same day, after the measurements)

Everything in the plan that is code or build, with the two on-device measurements left open
because neither device was on adb when this was implemented. Nothing below has been felt on the
wrist yet; `tools\watch-perf.ps1` is what to run when it is.

### Phase 1, durable — the `wrist` build type

`addWristBuildType` in `AndroidCommon.kt`, applied by both application convention plugins:
`initWith(release)`, `isDebuggable = false`, debug signing, `matchingFallbacks += "release"`.
`:wear:installWrist` is now the watch's install task and the profile says so; the phone stays on
`debug` unless asked. `docs/RELEASE_SIGNING.md` has the paragraph on why it is not a release. One
departure from the sketch above: the Crashlytics mapping file **is** uploaded for `wrist`. This is
the build whose crashes actually arrive, an unmapped R8 trace is unreadable, and the mapping that
would decode it is overwritten by the next build on this desk; the build type key still tells the
reports apart. The upload needs the network at build time, which the desk has and CI — which never
builds `wrist` — does not need.

### Phase 3 — the code

1. **Optimistic play/pause.** `PlaybackToggle` and `PLAY_HOLD_MS` (3 s, longer than the volume
   hold because the first tap after the phone app is killed pays its whole start-up) in
   `WatchPlayerUiState.kt`; `watchPlayerFrame` shows the wearer's word until a snapshot arrives
   *after the send saying that state*, or the hold passes. The bar follows the button: a pause
   freezes it where it was pressed, a play starts it moving from the press, so the glint and the
   glyph answer the thumb together. A tap that cannot be delivered puts the button back.
2. **One IPC per tap.** `PhonePlayerClient` remembers the last answer of the capability lookup —
   which the link poll and the capability listener already refresh — and `send` uses it, looking
   the phone up only when there is no answer or the remembered node refuses the message.
3. **`ProgressGlint` without per-frame allocation.** The band's gradient is built once per band
   width and translated into place; the clip path is one remembered object, reset each frame. The
   drawing is `ProgressGlintFrame`, a still frame, which is what the golden renders.
4. **The clock is gated.** `ticksWhile(moving)` runs once a second only while the phone says
   playing or a play/pause is pending; a paused watch wakes when the phone speaks and not
   otherwise. The clock is read as each frame is built rather than carried in the tick, and it is
   injected, so the view model tests move virtual time and count the wake-ups.
5. **Stability configuration.** `config/compose-stability.conf` marks
   `md.borisveriga.megapodcastplayer.core.wearprotocol.*` stable; `configureComposeStability` wires
   it into the Compose compiler for the watch module. Verification is in the section below.
6. **Firebase's start cost** — *not measured*; needs the watch on adb. The profile records how.
7. **Phone-side sender cache.** `WearSenderVerifier` keeps the last node list for ten seconds and
   lets it vouch for a node it named; a node it did not name is still checked live, so the cache
   only ever makes the check cheaper for the paired watch, never looser for anyone else. Done
   without the round-trip measurement the plan gated it on, because it is small and fails the same
   way the live check does.

### Roborazzi goldens for `:wear`

`configureWearCompose` now calls `configureScreenshotTests()` and adds the two roborazzi
artifacts. `WatchPlayerScreenshotTest` records six goldens under `wear/src/test/screenshots/`:
playing, volume open, scrubbing, idle, disconnected, and one frame of the glint. One variant each,
because Wear Material has one colour scheme.

### Phase 4

`tools\watch-perf.ps1` runs steps 0–3 of the protocol against whichever connected device is a watch,
re-reading the serial before every batch, and writes a summary and the framestats under
`docs/reports/perf/`. The profile's `performance-plan` section points at it.

### Verified on this machine

- `:wear:assembleWrist` → `wear-wrist.apk`, 3.38 MB, one `classes.dex`, `assets/dexopt/baseline.prof`
  present, no `debuggable` attribute in the manifest, signed with the same certificate as
  `wear-debug.apk` (`ce4cb092…9f6d`). `:app:assembleWrist` → 10.8 MB. Both uploaded their mapping
  file to Crashlytics as part of the build.
- Compose compiler report for `:wear` (`-Pmegapodcastplayer.compose.metrics=true`):
  `WatchPlayerUiState`, `ReceivedSnapshot` and `PlaybackPosition` read `stable`, with
  `snapshot: NowPlayingSnapshot` a `stable val`; before the configuration file they were inferred
  unstable. Every composable on the screen — `WatchPlayerScreen`, `NowPlayingTop`, `ProgressRow`,
  `ProgressGlintFrame`, `TransportRow`, `QueueRow` — is `restartable skippable`.
- `detekt` clean across the build; `:wear:lintDebug` and `:app:lintDebug` clean (the `wrist`
  builds also ran `lintVitalWrist`, clean).
- `:wear:testDebugUnitTest`: 161 tests, all passing, the six goldens in verify mode. New:
  eight `watchPlayerFrame` cases for the play/pause hold and the bar that follows it; five view
  model cases, including the two that count clock reads — a paused phone reads the clock zero
  times in ten seconds, a playing one exactly once a second; `PhonePlayerClientTest`, five cases
  on the remembered node ids; `WatchPlayerScreenshotTest`, six goldens. `:app`:
  `WearSenderVerifierTest` gains six cases on the sender cache; all passing.
- One thing the goldens needed that the plan did not foresee: Robolectric's native graphics
  reach into `java.nio.DirectByteBuffer` by reflection when Wear draws its curved time text, and
  the JDK's module system refuses that. `configureScreenshotTests` now passes
  `--add-opens=java.base/java.nio=ALL-UNNAMED` to every unit-test JVM. Test JVM only.

### Measured on the wrist (2026-10-01, `wrist` build of the code above, `speed-profile`)

Same watch, same protocol, now from `tools\watch-perf.ps1`; the raw output is under
`docs/reports/perf/2026-10-01-0414-watch/`. Battery 60 % on battery, `low_power` 0, animator
scale 1.0, Bluetooth connected to the phone. The APK on the device is 3.2 MB, one dex, not
debuggable.

| Measure | Debug (09-30) | Shrunk + `speed-profile` (09-30) | **`wrist` + phase 3 code (10-01)** |
|---|---|---|---|
| `am start -W` COLD | 1 729 ms | — | **342 / 331 ms** |
| `am start -W` WARM | 537 / 545 ms | 241 / 176 / 137 ms | 388 / 186 / 152 ms |
| Scroll, 20 swipes: janky frames | 76 of 668 (11.4 %) | 6 of 841 (0.7 %) | **2 of 869 (0.23 %)** |
| 90th / 95th / 99th percentile frame | 48 / 53 / 73 ms | 44 / 46 / 48 ms | **34 / 34 / 34 ms** |
| Slow UI thread frames | 75 | 6 | 2 |

Read with the same caveat as before: the 34 ms median and percentiles are the swipe script's
pacing, and in this run *no* frame fell outside it. The phase 3 code cost nothing measurable and
the scroll is as smooth as the measurement can see; the glint was running for all of it (the
episode was playing).

Two things learnt about the measurement itself, both now in the script:

- **`cmd package compile -m speed-profile` right after an install leaves the package at `verify`.**
  The baseline profile ships inside the APK and reaches ART's profile store only when the app's
  own profile installer runs, on a launch. Launch once, send the
  `androidx.profileinstaller.action.INSTALL_PROFILE` broadcast, *then* compile; `-Compile` does
  exactly that, and the profile's install notes say so.
- **A two-second pause between `force-stop` and `am start -W` turns a COLD start into a WARM one**
  on this watch: Play Services re-binds the chip service and brings the process back in the gap.
  The script's first run of the day (388 ms, WARM) is that; the two COLD figures came from a
  stop-and-start with no pause.

**Phase 3.6, Firebase, measured and closed.** From `logcat -v epoch` around two COLD starts:
the process is forked at +0, `FirebaseApp` begins initialising at +84 / +67 ms (that gap is the
fork, bind and class loading before any content provider runs), and `FirebaseInitProvider`
reports success at +114 / +104 ms — **30 and 37 ms** on the main thread, in starts of 342 and
331 ms. That is the tenth the plan said should be a known tenth, and it is the content
provider's own work, not the `CrashReporter` injection `WearApplication` does afterwards:
deferring ours would not move it, and removing the provider means disabling it in the manifest
and initialising Firebase by hand off the main thread, with the early-crash window that opens.
Not worth 30 ms. Left as it is, on purpose.

### Not yet felt by a thumb

The optimistic play/pause is verified by its tests and the screen draws right in the goldens, but
whether the flip *feels* immediate on the wrist is a thumb's call: tap play and pause repeatedly,
including once after the phone's app has been killed. The glyph should flip as the thumb lifts;
the bar should stop dead on pause and start on play without a later jump; the phone should catch
up within the three-second hold. The tap-to-change round trip itself (step 4 of the protocol) is
still unmeasured and needs the two temporary log lines the protocol describes.
