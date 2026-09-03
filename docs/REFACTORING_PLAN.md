# MegaPodcastPlayer — Refactoring Plan

**Date:** 2026-08-29
**Scope reviewed:** all 201 source/build files across `:app`, `:wear`, `:core:*` (9 modules),
`:feature:*` (6 modules) and `build-logic` — ~18 000 lines of Kotlin, plus manifests, ProGuard
rules, the version catalog and the Gradle setup.

This is a plan, not a set of applied changes. Nothing in the repository has been modified except
the addition of this file.

---

## 1. Headline assessment

The codebase is in good shape for its age. Module boundaries are clean, the convention plugins
are genuine (not copy-paste), KDoc coverage is unusually high and explains *why* rather than
*what*, ViewModels expose a single immutable `StateFlow` and every screen collects with
`collectAsStateWithLifecycle`. There are zero `TODO`/`FIXME`/`HACK` markers and no `!!` in
production code.

The problems are concentrated in four places:

| Area | State |
|---|---|
| **Untrusted input reaching the media stack** | The one genuine security hole. Feed-supplied URLs reach ExoPlayer unvalidated. |
| **Release signing & secrets hygiene** | Release builds silently fall back to the debug key; `keystore.properties` is not git-ignored. |
| **Cancellation safety** | Six `catch (e: Exception)` sites and 15 `runCatching` blocks wrap suspending calls and swallow `CancellationException`. |
| **Project hygiene** | No CI, no static analysis, no dependency verification, every user-facing string hardcoded, one test helper duplicated five times. |

Findings are numbered `S-n` (security), `C-n` (correctness), `Q-n` (quality/architecture),
`B-n` (build/supply chain), `T-n` (testing). Severity: **High / Medium / Low**.

---

## 2. Security

### S-1 — Feed-supplied audio URLs are never scheme-validated · **High**

`core/media/.../MediaItems.kt:18` calls `setUri(episode.audioUrl)` with a string that came
straight from an RSS `<enclosure url="…">` (`core/network/.../RssParser.kt`, `startElement`,
`name == "enclosure"` branch). The parser checks only the MIME `type` attribute, never the
scheme. That URI is then handed to a `DefaultDataSource.Factory`
(`core/media/.../di/DownloadModule.kt:86`), which resolves `file:`, `content:`, `asset:`,
`rawresource:` and `rtmp:` in addition to HTTP(S).

Consequence: a hostile or compromised feed can publish
`<enclosure url="file:///data/data/md.borisveriga.megapodcastplayer/databases/megapodcastplayer.db" type="audio/mpeg"/>`
and the app will open — and, if the user taps download, **copy into the download cache** — an
arbitrary file readable by the app's own UID. The same applies to `content://` URIs, which reaches
other apps' exported providers under MegaPodcastPlayer's identity.

**Fix**

1. Add a `isPlayableMediaUrl(String): Boolean` guard to `:core:model` accepting only
   `http`, `https` and the internal `youtube://` sentinel (see `YouTubeUrls.kt`).
2. Reject non-conforming enclosures in `RssParser.startElement` / `YouTubeAtomParser` so bad
   items never enter the database. This is the primary gate.
3. Re-check in `PlayableEpisode.toMediaItem()` as defence in depth — the database may already
   hold rows written before the fix.
4. Migration: one-off sweep deleting existing episode rows whose `audio_url` fails the guard.

**Tests:** parser test with `file:`/`content:`/`javascript:` enclosures asserting the item is
dropped; `MediaItemsTest` case asserting a non-HTTP URI never becomes a `MediaItem`.

---

### S-2 — Release builds fall back to the debug signing key · **High**

`build-logic/.../AndroidCommon.kt`, `configureSharedSigning`: when `keystore.properties` is
absent, `buildTypes.release.signingConfig = signingConfigs.debug`. The debug key is a
world-known Android SDK artifact. A release APK signed with it is trivially re-signable by
anyone, and — because the Wearable Data Layer routes purely on *package name + certificate* —
**any app built by anyone with the debug key and the `md.borisveriga.megapodcastplayer` application ID can
send `WearCommand`s to a real installation** and receive its `NowPlayingSnapshot` (which carries
episode titles, show titles and the full queue).

The fallback is convenient for sideloading, but it must be loud and debug-only.

**Fix**

- Keep the fallback for the `debug` build type only.
- For `release` with no `keystore.properties`: leave `signingConfig = null` and register a
  `doFirst` on the assemble task that fails with an actionable message, **or** gate the fallback
  behind an explicit `-PallowDebugSigningForRelease=true` flag so it can never happen by
  accident in CI.

---

### S-3 — `keystore.properties` and keystores are not git-ignored · **High (latent)**

`configureSharedSigning` reads `rootProject.file("keystore.properties")` containing
`storePassword`, `keyPassword` and `keyAlias` in plaintext. `.gitignore` ignores
`local.properties` but **not** `keystore.properties`, `*.jks` or `*.keystore`. The file does not
exist yet — which is exactly why this is cheap to fix now and expensive later (a committed
signing key means rotating the key and every installed APK).

**Fix**

```gitignore
keystore.properties
*.jks
*.keystore
```

Also prefer environment variables over the properties file when `CI=true`, and document the
setup in `docs/`.

---

### S-4 — `WearCommandService` does not verify the sending node · **Medium**

`app/.../wearsync/WearCommandService.kt:onMessageReceived` filters on path only. Play Services
already restricts delivery to same-package/same-certificate peers, so with S-2 fixed this is
mostly defence in depth — but combined with S-2 it is the second half of the remote-control
hijack.

**Fix:** resolve `messageEvent.sourceNodeId` against `NodeClient.connectedNodes` (or, better,
the set advertising a watch-side capability) before dispatching, and drop unknown senders. Add
a unit test on the extracted predicate — `WearCommandExecutor` is already testable because it is
separated from the service; do the same for the sender check.

---

### S-5 — The media session accepts every controller · **Medium**

`core/media/.../PlaybackService.kt:110` installs a `ResumptionCallback` that overrides only
`onPlaybackResumption`. The inherited `MediaSession.Callback.onConnect` accepts **all**
controllers, and the service is `exported="true"` (correctly — Media3 requires it). Any app on
the device can therefore bind the session, read episode/show metadata and drive playback.

**Fix:** override `onConnect` to build a `ConnectionResult` that grants the full command set only
to the app's own UID, to the system UI / media-button receiver, and to Android Auto; grant
everyone else an empty or read-only command set. Keep the allowlist in one documented constant.

---

### S-6 — Unbounded response buffering in the NewPipe bridge · **Low**

`core/youtube/.../OkHttpNewPipeDownloader.kt` calls `response.body.string()` with no size cap.
The bodies are YouTube JSON/HTML, but the host is not under our control and OOM in the extractor
kills the player process.

**Fix:** `response.peekBody(MAX_EXTRACTOR_BODY_BYTES)` with a documented ceiling (2 MB is
generous), failing the request cleanly above it.

---

### S-7 — Backup rules are unedited templates · **Low**

`app/src/main/res/xml/backup_rules.xml` and `data_extraction_rules.xml` are the Studio-generated
stubs with everything commented out, while `allowBackup="true"`. The whole Room database (library,
listening positions, queue) and the whole DataStore therefore leave the device via cloud backup
and device-to-device transfer.

**Fix:** decide deliberately. Either exclude the DataStore and the HTTP/download caches (they are
regenerable and the caches are large), or set `allowBackup="false"`. Either way, replace the
template comments with a real policy and a comment saying why.

### Already correct — do not "fix"

- Both XML parsers disable external general/parameter entities via `disableIfSupported`
  (`RssParser.kt`) — XXE is handled.
- All SQL goes through Room `@Query`; no `@RawQuery`, no string-built SQL.
- `HttpLoggingInterceptor` is `BuildConfig.DEBUG`-gated and at `BASIC` level.
- No cleartext-traffic opt-in; `minSdk 34` means HTTP is blocked by platform default.
- JitPack is scoped to exactly `com.github.TeamNewPipe` by a content filter in
  `settings.gradle.kts`.
- `PendingIntent` uses `FLAG_IMMUTABLE`.
- `EpisodeDownloadService` is `exported="false"` with the right `foregroundServiceType`.

---

## 3. Correctness

### C-1 — `CancellationException` swallowed across the app · **High**

Kotlin's `catch (e: Exception)` and `runCatching` both catch `CancellationException`. In
suspending code this breaks structured concurrency: a cancelled coroutine keeps running, and
`ViewModel`/service scopes leak past their owner's death.

Confirmed sites in production code:

| File | Line | Impact |
|---|---|---|
| `core/data/.../OfflineFirstPodcastRepository.kt` | 150 | `addFeed` — a cancelled add is reported as a failure and continues writing |
| `core/data/.../OfflineFirstPodcastRepository.kt` | 213 | `refreshAll` loop — **cancelling a library refresh does not stop it**; it keeps fetching every remaining feed |
| `core/data/.../OfflineFirstPodcastRepository.kt` | 72, 192 | `runCatching` around `itunes.search` / `refreshOne` |
| `core/media/.../PlaybackConnection.kt` | 71, 300 | `callbackFlow` setup and `onController` |
| `core/media/.../PlaybackService.kt` | 265 | resumption callback |
| `wear/.../PhonePlayerClient.kt` | 144, 154, 166, 170, 185 | link polling and node lookup |
| `app/.../NowPlayingPublisher.kt` | 157 | Data Layer write |

**Fix**

1. Add to `:core:common`:
   ```kotlin
   /** Like [runCatching], but never swallows coroutine cancellation. */
   suspend inline fun <T> suspendRunCatching(block: () -> T): Result<T> =
       try { Result.success(block()) }
       catch (e: CancellationException) { throw e }
       catch (e: Throwable) { Result.failure(e) }
   ```
2. Replace every suspending `runCatching` with it, and add
   `catch (e: CancellationException) { throw e }` above each `catch (e: Exception)`.
3. Enable detekt's `SwallowedException` / `TooGenericExceptionCaught` (see B-2) so this cannot
   regress.

**Test:** a `refreshAll` test that cancels the calling job mid-loop and asserts the fake feed
source stops receiving requests. This test fails today.

---

### C-2 — `runBlocking` on the main thread in `PlaybackService` · **Medium**

`PlaybackService.kt:82` blocks `onCreate` on a DataStore read; `:137` blocks `onDestroy` on a
position write. Both run on the main thread. The KDoc argues the read is small — usually true,
but under memory pressure (exactly when the service is being recreated) a cold DataStore read
does file I/O and can hit the ANR window, and `onDestroy` is called during system-initiated
shutdown when disk contention is at its worst.

**Fix**

- `onCreate`: construct the player with `PlaybackSettings` defaults, then apply persisted
  settings from a `serviceScope.launch` as soon as they arrive. Speed and skip intervals are
  cosmetic for the first few hundred milliseconds, and `setSeekForwardIncrementMs` /
  `setPlaybackSpeed` are settable post-construction on the player.
- `onDestroy`: write the position on `onIsPlayingChanged(false)` and on the 5 s ticker (both
  already exist), and use `goAsync`-style handoff to the application scope rather than
  `runBlocking` for the final flush — or accept losing at most 5 s of progress and drop the
  final flush entirely.

---

### C-3 — The periodic refresh was designed but never built · **Medium**

There is a complete, unused scaffold for background refresh:

- `PodcastEntity.autoRefresh` column and `PodcastDao.getAutoRefreshable()` (`PodcastDao.kt:41`)
- `PodcastRepository.refreshAll(onlyAutoRefreshable: Boolean)` — **the `true` branch has no
  caller anywhere in the app**
- `Podcast.autoRefresh`'s KDoc literally says "whether the periodic refresh worker should
  include this show"
- `androidx.work:work-runtime-ktx` declared in `app/build.gradle.kts` and never imported
- `RECEIVE_BOOT_COMPLETED`, `POST_NOTIFICATIONS` and `FOREGROUND_SERVICE_DATA_SYNC` in the
  manifest

Either finish it or remove it — a permission requested for a feature that does not exist is a
Play Store review problem and an honest-manifest problem.

**Recommended:** finish it. A `RefreshWorker : CoroutineWorker` in `:app` (or a new
`:core:sync`), `PeriodicWorkRequest` every 6 h with `NetworkType.CONNECTED`, wired in
`MegaPodcastPlayerApplication.onCreate` via `WorkManager.getInstance(this).enqueueUniquePeriodicWork(…, KEEP, …)`,
plus a "new episodes" notification. This also closes the loop on the `is_new` episode flag,
which `clearNewFlags` already maintains.

**If deferred:** delete `androidx.work` from `:app`, drop `RECEIVE_BOOT_COMPLETED` (verify
Media3's `PlatformScheduler` does not need it first — it does, so keep that one), and mark
`refreshAll(onlyAutoRefreshable = true)` as unreachable or remove the parameter.

**Done in Phase 4** — built, not deferred. The pieces:

- `RefreshWorker` (`:app`, `sync/`): a `@HiltWorker` `CoroutineWorker` calling
  `refreshAll(onlyAutoRefreshable = true)`. A run where *every* feed failed is retried with
  exponential backoff up to three attempts, on the theory that it means the network came back only
  far enough to satisfy the `CONNECTED` constraint; one failure among several is a success.
- `RefreshScheduler`: `PeriodicWorkRequest` every 6 h, `NetworkType.CONNECTED`, enqueued as unique
  work with `KEEP` from `MegaPodcastPlayerApplication.onCreate`. `KEEP` is the load-bearing half — `REPLACE`
  would push the next run six hours out on every app launch, so a phone used daily would never
  refresh in the background at all.
- `NewEpisodeNotifier`: an interface, so the worker is testable without a notification manager. Its
  content — how many lines, and whether the tap has a single show to open — is a pure function
  (`newEpisodeNotificationContent`) tested on its own; `SystemNewEpisodeNotifier` does the posting.
  One notification id, so each refresh replaces the last card rather than stacking.
- Tapping it opens `MainActivity` through an **explicit** intent carrying `EXTRA_PODCAST_ID`, not an
  exported deep-link URI: navigation into the app stays something only the app can ask for. The
  activity is now `singleTop` so a tap while it is running reaches `onNewIntent`.
- `POST_NOTIFICATIONS` is requested once per launch from the composition
  (`NotificationPermissionEffect`), because the worker runs with no UI to ask from. The notifier
  re-checks the grant on every post, so a denial simply means silence.
- `RefreshSummary` gained `newEpisodes: List<NewEpisode>` (show title + episode title), built from
  entities the refresh already had in hand; `newEpisodeCount` is now derived from it. That also
  retired `refreshOne`'s `NOT_MODIFIED = -1` sentinel and the `coerceAtLeast(0)` it forced on
  `PodcastDetailViewModel`.
- `androidx.work` + `androidx.hilt.work` moved from `:core:data` — which declared them and never
  imported them — to `:app`, which now uses them. The manifest removes WorkManager's default
  `androidx.startup` initializer, as on-demand initialisation requires.

Every permission in the manifest now backs a feature that exists: `POST_NOTIFICATIONS` for the
notification above, `FOREGROUND_SERVICE_DATA_SYNC` for `EpisodeDownloadService`'s `dataSync` type,
and `RECEIVE_BOOT_COMPLETED` for Media3's `PlatformScheduler`.

**Not done, deliberately:** auto-download still has no interaction with the notification — an
episode the refresh queues for download is announced as new like any other, and the download's own
progress notification is separate. Merging the two is a product question, not refactoring debt.

---

### C-4 — Watch link poller runs on a fixed 10 s timer · **Low**

`PhonePlayerClient.phoneLink` polls `currentLink()` every 10 s for as long as the flow is
collected, on top of the capability listener. On a watch this is a measurable battery cost.

**Fix:** back off — poll at 10 s for the first minute after the screen comes up, then at 60 s;
or poll only while `PhoneLink != CONNECTED`, since the listener is reliable for the
connected→disconnected transition that matters least.

---

## 4. Code quality & architecture

### Q-1 — Every user-facing string is hardcoded in Kotlin · **High (effort), Medium (impact)**

`stringResource` appears **zero** times in the entire codebase. `app/res/values/strings.xml` and
`wear/res/values/strings.xml` are 3 lines each (`app_name` only). Every label, every dialog, every
`contentDescription` is an inline Kotlin literal — including interpolated ones like
`"Remove the download of ${episode.title}"` (`DownloadsScreen.kt:340`).

This blocks localisation entirely (notable given the library is partly Russian-language shows),
makes copy review impossible, and means TalkBack strings cannot be adjusted without a code change.

**Fix — do this module by module, one PR each:**

1. `:core:designsystem` gains nothing; strings belong to the feature that shows them.
2. Per feature module, add `src/main/res/values/strings.xml` and move every literal, using
   `<string name="…">%1$s</string>` placeholders for the interpolated ones.
3. Replace with `stringResource(R.string.…)`. Composables that are not `@Composable`-scoped
   (e.g. `AddPodcastResult.toUserMessage()` in ViewModels) must return a `@StringRes Int` plus
   args, not a `String` — this is the part that actually changes design, so do the ViewModels
   deliberately rather than mechanically.
4. Order by size, smallest first (quoted literals per module): `:feature:library` (26) → `:wear` (29)
   → `:feature:downloads` (31) → `:feature:search` (36) → `:feature:settings` (38) →
   `:feature:player` (42) → `:feature:podcast` (45).

Roughly 150–200 strings. Mechanical but not automatable safely; budget a day.

**Done in Phase 3.** All seven modules plus `:app` (the navigation bar labels) and
`:core:designsystem` (the `SourceBadge` label and the default loading description, which are part
of those components' contracts) now read from `strings.xml`. Counts are `<plurals>`; two-part
sentences are joined by a `%1$s … %2$s` string so a translator can reorder them. Two view-model
signatures changed rather than being translated in place, as step 3 anticipated:
`SearchUiState.searchError` is now a `SearchError` sealed interface instead of pre-worded text,
and `TopLevelDestination.label` is now a `@StringRes labelResId`.

**Deliberately left:** `:core:common`'s formatters (`formatDuration`, `formatPublishedDate`,
`formatBytes`, `formatRemaining`) still produce English — "Today", "Yesterday", "3 days ago",
"1 h 24 min", "90 MB", "… left". They are pure functions with no `Context`, unit-tested as such,
and used from three feature modules; moving them onto resources changes their design and their
tests rather than moving a literal, so it is its own piece of work. Until it happens, an episode
row is part-translated: the labels around the metadata line are localised, the date and duration
inside it are not.

---

### Q-2 — `MainDispatcherRule` duplicated in five modules · **Medium**

Five byte-identical 33-line files differing only in `package`:
`feature/downloads`, `feature/player`, `feature/podcast`, `feature/settings`, `wear`.

**Fix:** create `:core:testing` (a `megapodcastplayer.jvm.library` — it needs no Android), holding
`MainDispatcherRule`, the existing `InMemoryPreferencesDataStore` (currently stranded in
`core/datastore/src/test/`, where no other module can reach it), and shared test fixtures for
`Episode`/`Podcast`/`PlaybackState`. Add `add("testImplementation", project(":core:testing"))`
to `AndroidLibraryConventionPlugin` and `AndroidApplicationConventionPlugin` so every module gets
it for free. Delete the five copies.

This also makes the DataStore-on-Windows workaround (in-memory fake) available to every module
instead of only `:core:datastore`.

---

### Q-3 — Orphaned KDoc block in `DownloadModule` · **Low**

`core/media/.../di/DownloadModule.kt` has two consecutive `/** … */` blocks (lines ~62–81): a
doc comment describing the standalone database, immediately followed by the doc for
`networkDataSourceFactory`. The first documents nothing — it was detached from
`providesDownloadDatabaseProvider` when the file was reordered. Move it back onto that provider.

---

### Q-4 — UI state exposes `List<T>`, which Compose treats as unstable · **Low**

`SearchUiState.results`, `DownloadsUiState`, `PlayerUiState`, `PodcastDetailUiState`,
`WatchPlayerUiState.upNext` all hold `List<…>`. Compose cannot prove a `List` immutable, so every
composable taking one is unskippable and recomposes on every parent state change.

**Fix:** add `kotlinx-collections-immutable` and use `ImmutableList`/`persistentListOf`, or mark
the state classes `@Immutable` where the contract genuinely holds. Measure first with a
composition-count trace — this is a real but small win and should not be done blind.
Note: if adopting kotlinx.collections.immutable, use the 0.5.x API names (`adding`, `removingAt`)
per KEEP-0459.

**Measured in Phase 3 — no change made.** The premise no longer holds on this toolchain. Strong
skipping has been on by default since Kotlin 2.0.20, and this project is on 2.3.21: an unstable
parameter is compared by instance rather than disqualifying the composable. The Compose compiler
reports (`./gradlew assembleDebug -Pmegapodcastplayer.compose.metrics=true`, wired in `AndroidCompose.kt`)
say so directly — across `:app`, `:wear` and all six feature modules, **48 of 48** restartable
composables are `skippable`, none unskippable, even though `SearchUiState`, `DownloadsUiState`,
`PlayerUiState`, `PodcastDetailUiState` and `WatchPlayerUiState` are all still reported as
`unstable class`.

Adding a dependency and rewriting five state classes to fix a number that is already zero is not
worth it. Re-measure with the same flag after a Compose or Kotlin bump; if unskippable composables
appear, the fix above is still the right one.

---

### Q-5 — Feature modules depend directly on `:core:data` · **Low / architectural, informational**

`AndroidFeatureConventionPlugin` wires `:core:data` into every feature, so ViewModels call
repositories directly with no use-case layer. This matches the Now-in-Android reference
architecture and is a legitimate choice at this size — flagged only so the decision is explicit.
Revisit if a single ViewModel starts orchestrating three or more repositories
(`SettingsViewModel` and `PodcastDetailViewModel` are the ones to watch).

---

### Q-6 — Dead artifacts · **Low**

- `app/src/androidTest/` exists and is **empty**, while `app/build.gradle.kts` declares
  `androidTestImplementation` for JUnit and Espresso. Either write a smoke test or delete both.
- Version catalog entries declared and never referenced anywhere:
  `androidx-lifecycle-service`, `androidx-media3-ui-compose`, `compose-gradlePlugin`,
  `hilt-android-testing`, `mockk-android`.

**Done in Phase 3**, with one correction: `compose-gradlePlugin` *is* referenced — it is a
`compileOnly` dependency of `build-logic/convention` — and stays. The other four were removed,
along with `androidx-test-espresso-core`, which only the deleted `:app` androidTest wiring used.
`androidx-test-junit` also stays: several modules use it as a `testImplementation`.

---

## 5. Build, tooling & supply chain

### B-1 — No CI · **High**

There is no `.github/workflows`, no CI configuration of any kind, and the repository has exactly
one commit. Everything below is unenforceable without this.

**Fix:** a single `ci.yml` running on push and PR:
`assembleDebug`, `testDebugUnitTest` (all modules), `lintDebug`, and — once B-2 lands — `detekt`.
Use `gradle/actions/setup-gradle` with the build cache enabled; the project already has
configuration cache on, so builds will be fast.

---

### B-2 — No static analysis, no lint configuration · **High**

No detekt, no ktlint, no `.editorconfig`, no `lint.xml`, no lint baseline. Android Lint runs with
stock defaults and nothing fails the build.

**Fix**

1. Add a `megapodcastplayer.detekt` convention plugin applying detekt to every module with one shared
   config. Enable at minimum: `TooGenericExceptionCaught`, `SwallowedException` (these catch C-1),
   `MaxLineLength`, `LongMethod`, `LongParameterList`, `ForbiddenComment`.
2. Add `.editorconfig` matching `kotlin.code.style=official`.
3. In `configureAndroidCommon`, set `lint { warningsAsErrors = true; abortOnError = true }` and
   generate a baseline so the existing warnings do not block the change.

---

### B-3 — No dependency verification, and a JitPack dependency · **Medium**

`NewPipeExtractor` resolves from JitPack, which builds artifacts from a git tag on demand — a
mutable source by design. There is no `gradle/verification-metadata.xml`, so nothing pins the
checksum of that or of any other artifact.

**Fix:** `./gradlew --write-verification-metadata sha256 help`, commit the result, and refresh it
deliberately on every dependency bump. This is the single highest-value supply-chain control
available here and costs one command.

---

### B-4 — Gradle wrapper distribution is not checksum-pinned · **Low**

`gradle-wrapper.properties` has `validateDistributionUrl=true` but no `distributionSha256Sum`.

**Fix:** add the published SHA-256 for `gradle-9.7.1-bin.zip`.

---

### B-5 — `debug { applicationIdSuffix = "" }` is a no-op · **Low**

`AndroidApplicationConventionPlugin`: setting an empty suffix does nothing. It appears to be a
placeholder recording "deliberately no suffix, because the watch pairing requires matching
application IDs". Replace with a comment; the empty assignment reads as an unfinished edit.

---

## 6. Testing

### T-1 — Two feature modules have no tests at all · **Medium**

`feature/library` and `feature/search` have `src/main` and no `src/test`. `LibraryViewModel`
(refresh orchestration, summary formatting) and `SearchViewModel` (debounce, link-vs-search
branching, `flatMapLatest` cancellation) are exactly the kind of logic that regresses silently.
`core/designsystem` is presentational and needs no unit tests.

**Fix:** ViewModel tests with Turbine + the shared `MainDispatcherRule` from Q-2. For
`SearchViewModel`, use `runTest`'s virtual time to assert the 400 ms debounce (`DEBOUNCE_MS`) and that a
fast-typed query issues exactly one network call.

### T-2 — No instrumentation or screenshot coverage · **Low**

The only `androidTest` directory is empty (Q-6). Per the recorded environment constraints, the
Wear emulator cannot be paired for this project and the watch UI is already tested through
Robolectric (`WatchPlayerScreenTest`) — extend that pattern to the phone's Compose screens rather
than investing in an emulator-based suite.

### T-3 — No coverage measurement · **Low**

Add Kover to the convention plugins and report in CI. Do not set a failing threshold initially;
measure for a few weeks first.

---

## 7. Execution plan

Each phase is independently shippable. Phase 0 exists so that later phases are verifiable.

### Phase 0 — Make the work verifiable (½ day)

| # | Item |
|---|---|
| B-1 | CI workflow: assemble + unit tests + lint |
| B-2 | detekt convention plugin, `.editorconfig`, lint baseline, `warningsAsErrors` |
| Q-2 | `:core:testing` module; delete the five `MainDispatcherRule` copies |

**Exit:** `./gradlew build detekt` green in CI on a PR.

### Phase 1 — Security (1–1½ days)

| # | Item |
|---|---|
| S-3 | `.gitignore` the keystore and its properties file *(do this first — it is one line)* |
| S-1 | URL scheme allowlist in parser + `toMediaItem`, plus the cleanup migration |
| S-2 | Fail the release build rather than signing with the debug key |
| S-4 | Verify `sourceNodeId` in `WearCommandService` |
| S-5 | Restrict `MediaSession.Callback.onConnect` |
| S-7 | Write real backup / data-extraction rules |
| B-3 | `gradle/verification-metadata.xml` |
| B-4 | Pin the wrapper distribution checksum |

**Exit:** a feed with a `file://` enclosure produces no playable episode (new test); a release
build with no keystore fails with a clear message.

### Phase 2 — Correctness (1 day)

| # | Item |
|---|---|
| C-1 | `suspendRunCatching` in `:core:common`; convert all 21 sites; enable the detekt rules that guard it |
| C-2 | Remove both `runBlocking` calls from `PlaybackService` |
| C-4 | Back off the watch link poller |
| S-6 | Cap the NewPipe response body |

**Exit:** the new "cancel `refreshAll` mid-loop" test passes; no `runBlocking` remains in
`:core:media`.

### Phase 3 — Quality (2–3 days)

| # | Item |
|---|---|
| Q-1 | String externalisation, one module per PR, in the order given |
| T-1 | `LibraryViewModel` and `SearchViewModel` tests |
| Q-3, Q-6, B-5 | Orphaned KDoc, dead `androidTest` dir, unused catalog entries, no-op suffix |
| Q-4 | Compose stability — **measure first**, then decide |
| T-3 | Kover, reporting only |

### Phase 4 — Feature debt (½–1 day, decide first)

| # | Item |
|---|---|
| C-3 | Either build `RefreshWorker` + the new-episode notification, or remove the unused scaffold and the `androidx.work` dependency |

This is the one item that is a product decision rather than a refactor. It also intersects with
the two gaps recorded in the project's milestone notes — the missing "Downloaded" screen consumer
for `observeDownloadedEpisodes()`, and the watch's missing artwork/tile/complication — which are
new work rather than refactoring and are deliberately out of scope here.

**Decided: build it.** See C-3 above for what shipped.

**Exit:** the worker refreshes only shows that opted in, retries a run in which every feed failed,
and posts nothing without the runtime permission — all three covered by tests in `:app`.

---

## 8. Explicitly out of scope

- Rewriting `RssParser` against a library. The hand-rolled SAX parser is well-tested against
  three real-world fixtures and its skip-the-item failure mode is correct for podcast feeds.
- Introducing a use-case/domain layer (Q-5). Not justified at this size.
- Replacing NewPipeExtractor. The R8 rules are hard-won and documented; leave them alone.
- Any change to the Wear Data Layer *protocol*. It is versioned by `@SerialName`, tolerates
  unknown fields on both sides, and is the most carefully built part of the codebase.
