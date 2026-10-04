# Project profile — MegaPodcastPlayer

## Identity

- **What it is:** personal, sideloaded offline-first podcast player (`:app`) with a Wear OS companion (`:wear`) that is one screen of remote control plus a watch-face complication — the watch plays nothing itself.
- **Application ID(s):** `md.borisveriga.megapodcastplayer` for both `:app` and `:wear` (debug suffix: none, deliberately — ever). Namespaces differ (`…megapodcastplayer` / `…megapodcastplayer.wear`).
- **Modules:** apps `:app`, `:wear`; pure JVM `:core:model`, `:core:wearprotocol`; Android `:core:{common,database,datastore,network,youtube,media,data,designsystem}`; test fixtures `:core:testing`; features `:feature:{library,downloads,search,podcast,player,moments,settings}`. Sources at `<module>/src/main/kotlin/md/borisveriga/megapodcastplayer/…`.
- **Conventions live in:** `CLAUDE.md`, `build-logic/convention/`, `config/detekt/detekt.yml`, `gradle/libs.versions.toml`, `docs/` (`REFACTORING_PLAN.md`, `RELEASE_SIGNING.md`, `DEPENDENCY_VERIFICATION.md`, `CRASH_REPORTING.md`, `SCREENSHOT_TESTS.md`, `COPY_RULES.md`).
- **Default branch:** `main` (renamed from `master` on 2026-09-16).

## Commands

```powershell
.\gradlew.bat detekt --continue
.\gradlew.bat testDebugUnitTest test --continue      # JVM modules use `test`
.\gradlew.bat lintDebug --continue
.\gradlew.bat koverHtmlReportUnit koverLogUnit
.\gradlew.bat testDebugUnitTest -Pmegapodcastplayer.screenshots.record   # re-record goldens, then open the images
```

Gradle daemon runs on JDK 25 (`gradle/gradle-daemon-jvm.properties`); detekt 2.x alpha is pinned for that reason. `versionCode`/`versionName` are in `gradle/libs.versions.toml`, shared by both apps.

## Standing constraints

- Episode audio URLs are identities: `youtube://video/<id>` is the stored URL and the Media3 cache key; a feed URL is hashed into the podcast id. Changing either spelling orphans data.
- The video screen's `youtube://video-only/<id>?h=<height>` sentinel is minted in-process by `VideoMode.kt` (`:core:media`) and never stored; `isPlayableMediaUrl` rejects it on purpose. Video is streaming only — downloads stay audio, and the audio half of a downloaded episode still plays from the cache while its picture streams.
- The player has two faces, the sheet and the video screen (`Route.Video`), and which one a tap on the collapsed bar opens is the persisted `PlayerMode` (`:core:model`), set only by the shell (`MegaPodcastPlayerApp`'s `openPlayer`, the one door every screen that starts an episode goes through, since 2026-10-04). The picture streams for as long as the player is in video on an episode that has one and the app is in front (`KeepPictureEffect` in `PlayerSheetScaffold`, since 2026-10-02): on the video screen, and minimised in the collapsed bar, which draws it live on a `TextureView`. One activity-scoped `VideoViewModel` serves both. In the background the episode plays as sound, and coming back asks for the picture again.
- Untrusted feed input reaches the media stack: new URL/file handling goes through `isPlayableMediaUrl` (`:core:model`), enforced twice on purpose; `MIGRATION_2_3` LIKE patterns must stay in step.
- Watch pairing = package name + signing certificate. Install both sides from the same build.
- Data Layer: state is a data item, a command is a message (`WearPaths`); no audio crosses to the watch.
- No version compatibility: strict JSON decoding, Room destructive fallback — schema/protocol changes are free but wipe on-device data.
- `:core:model` / `:core:wearprotocol` stay pure JVM.
- Moments are the only user-written data; `MomentsRepository`'s Markdown export must stay self-contained with links.
- Use `suspendRunCatching`, never swallow `CancellationException`; record swallowed failures via `CrashReporter` (`:core:common`, the only Firebase module).
- No `TODO`/`FIXME`/`!!`; user-facing text in `strings.xml` worded per `docs/COPY_RULES.md`.
- Dependency verification is on: a bump needs `gradle/verification-metadata.xml` refreshed.

## User data at risk

Uninstall of `md.borisveriga.megapodcastplayer` on the phone deletes the Room database (subscriptions, queue, positions, moments) and the `episode_downloads` cache (the whole offline library). It is Boris's daily-driver phone.

## install_on_devices

- **Targets:** `:app` → Galaxy Z Fold 7, `:wear` → Galaxy Watch Ultra 2.
- **Build type:** `debug` for the phone; **`wrist` for the watch** (since 2026-09-30). `wrist` is
  release's R8 configuration, not debuggable, signed with the debug key — a build type of its own
  in both application convention plugins (`addWristBuildType` in `AndroidCommon.kt`), documented in
  `docs/RELEASE_SIGNING.md`. Reason, with numbers: `docs/reports/2026-09-30-watch-performance-plan.md`
  — the 80 MB debug APK cold-started in 1.7 s with an 11 % janky scroll, and ART refuses to
  AOT-compile a debuggable package at all, so it could never get better; the shrunk build is 3.4 MB
  and starts in under 0.3 s. Same key and app id, so it pairs with the debug phone build without an
  uninstall. Installing `wear-debug.apk` over it brings the slowness back. The phone has
  `:app:installWrist` too; it stays on `debug` unless Boris asks, because it is the daily driver and
  a build type change there is his call.
- **Install tasks:** `:app:installDebug` for the phone; `:wear:installWrist` for the watch (3.4 MB,
  so Gradle's streamed install is fine; if it still dies, `adb install -r --no-streaming
  wear\build\outputs\apk\wrist\wear-wrist.apk`). **After installing on the watch**, three
  commands, in this order: launch the app once (`monkey` below), then
  `adb shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE -p md.borisveriga.megapodcastplayer/androidx.profileinstaller.ProfileInstallReceiver`,
  then `adb shell cmd package compile -m speed-profile -f md.borisveriga.megapodcastplayer`. A fresh
  install sits at dexopt `verify` until the watch is idle and charging; the baseline profile ships
  inside the APK and only reaches ART's profile store when the app's profile installer runs (on a
  launch — the broadcast makes it now), and a compile before that finds nothing and stays at
  `verify` (seen 2026-10-01). `speed-profile` is what brings the cold start into the 200–300 ms
  band; `tools\watch-perf.ps1 -Compile` does all three. Then `dumpsys package` must show
  `status=speed-profile` and no `DEBUGGABLE`. The 3.4 MB APK streams fine over wireless adb.
  Never `installRelease`: without `keystore.properties`, `configureSharedSigning` leaves release
  unsigned and `failReleasePackagingWithoutAKeystore` fails the task unless the flag is passed, and
  the flag is for CI's smoke build only.
- **Launch / smoke check:** `monkey -p md.borisveriga.megapodcastplayer -c android.intent.category.LAUNCHER 1` (two namespaces, one app id — do not hardcode a component).
- **adb:** not on PATH; use `$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe`. Both devices are on wireless debugging, so their serials (IP:port) change on every reconnect: re-list before each install.
- **Try `adb mdns services` before the server restart.** Seen 2026-09-21 (second run): the watch was
  already connected under its mDNS service name while `adb devices` showed no phone; `adb mdns services`
  listed the phone's current `IP:port`, and `adb connect <ip>:<port>` took it straight away. The absent
  side is as often the phone as the watch. A leftover `offline` `IP:port` row for a device that is also
  connected by service name is stale — `adb disconnect` it rather than installing to it.
- **A watch that is "simply absent" is often an adb server that has stopped discovering.** Seen 2026-09-21: `adb devices` showed only the phone, `adb mdns services` was empty and `adb mdns check` reported the daemon healthy — yet `adb kill-server; adb start-server` immediately listed both devices with their `IP:port`, which `adb connect <ip>:<port>` then took. Try the server restart *before* asking Boris to touch the watch; it costs the phone's connection, which comes straight back.
- **A device that advertises over mDNS but refuses every `connect` needs re-pairing, not persuading.** Seen 2026-09-22: `adb mdns services` listed the phone's two `_adb-tls-connect._tcp` ports, yet `connect <ip>:<port>` gave `failed to connect` / `actively refused`, on both ports and by service name; `reconnect offline` and `kill-server`/`start-server` only produced an `offline` row that then vanished. `adb pair <ip>:<pairPort> <code>` from the phone's "Pair device with pairing code" screen fixed it instantly and the device came up as `device` without a further `connect`. Ask for the pairing code once the cheap tricks have each failed once — do not keep cycling them.
- **Quirks:** the debug `wear-debug.apk` is ~80 MB (no R8), which is why the watch does not get it any more: wireless install of it often dies with `EOF`, and `adb install -r --no-streaming <apk>` was the workaround. The 3.4 MB `wrist` APK has not needed it. Expect the link to drop right after any push completes; the install has still succeeded, so reconnect and check `lastUpdateTime` rather than reinstalling. `:wear` depends only on `:core:wearprotocol` + `:core:common`, so its APK is often already up to date. Stale pre-rename APK on the watch (old `/bpodcat/command` paths) was diagnosed 2026-09-03 via the phone's `dumpsys … gms.wearable`.

## distribute

- **Path:** B — local signed APKs. `.github/workflows/ci.yml` only verifies (detekt, assembleDebug, tests, lint, Kover, debug-signed release smoke build discarded) and publishes nothing.
- **Handing a build to Boris for download:** Firebase App Distribution on project `megapodcastplayer`, used since 2026-09-13. Run `npx -y firebase-tools@latest appdistribution:distribute <apk>` with `$env:CI = "1"`; it is already logged in. Upload **debug** APKs of both sides from the same commit, as separate releases of the one app id `1:860786298283:android:a06b5caf417d126f44d6ee`, with notes saying PHONE or WATCH and the SHA. Tester: borisveriga@gmail.com. Link the tester page, not the binary URL, which expires.
- **Details:** keystore via `tools\create-release-keystore.ps1` or `docs/RELEASE_SIGNING.md`. Build `:app:assembleRelease :wear:assembleRelease`; archive both mapping files to `dist\<sha>\`. Both APK certificate digests must match. Never offer `-PallowDebugSigningForRelease`, and never distribute a `wrist` APK either — both are debug-signed, and a debug-signed impostor could send `WearCommand`s and read `NowPlayingSnapshot`. Recipient caveats: both APKs needed; version 1.0 update may need uninstall (loses library). YouTube extraction blocks Play Store distribution — raise it if publishing comes up. No baseline profiles of the app's own code; Compose's library profiles ride in every shrunk APK.

## merge-to-main

- **CI gate:** none. CI does not run on pull requests and `main` has no branch protection; merge once the local detekt, unit-test and lint runs pass, without waiting for CI (Boris's call, 2026-09-16). CI still builds `main` after the merge.
- **Review checklist extras:** URL/`youtube://` spellings and podcast-id hash unchanged; new URL input via `isPlayableMediaUrl`; data items vs messages (`WearPaths`), no audio to watch; `suspendRunCatching` + `CrashReporter`; KDoc, tests, no `TODO`/`FIXME`/`!!`; strings per `docs/COPY_RULES.md`; version bumps refresh `verification-metadata.xml`; moments export untouched or deliberate. Goldens in `src/test/screenshots/*.png` must be opened.
- **Changed paths → install:** `wear/**`, `core/wearprotocol/**`, `core/common/**`, `core/model/**` → watch (and phone; protocol changes → **both**). `app/**`, `feature/**`, other `core/**` → phone. `build-logic/**`, `gradle/libs.versions.toml`, `verification-metadata.xml`, root scripts → both. Only `docs/**`, `.claude/**`, `.github/**`, `config/**`, `*.md`, `src/test/**` → nothing.

## feature-plan

- **Competitors to survey:** Pocket Casts, AntennaPod, Podcast Addict, Overcast (note server-account-dependent features — this app has no account).
- **Idea seeds:** sleep timer, chapters, playback stats, silence trimming / volume boost, OPML import/export, widget, Android Auto, per-show playback settings, smart playlists, transcripts, cross-device position sync.

## improvement-plan

- **Prior audits:** `docs/REFACTORING_PLAN.md` (2026-08-29, numbered backlog referenced by CI, e.g. `T-3`) and `docs/reports/*-improvement-plan.md`.
- **Focus areas:** feature modules reaching past `:core:data`; Android leaking into pure-JVM modules; repository interface/impl split (`PodcastRepository`/`OfflineFirstPodcastRepository`, `DownloadRepository`/`MediaDownloadRepository`); `@Dispatcher` injection; accessibility of `WaveScrubber`, `WavyProgressLine`, `ReorderHandle`, `SelectionToolbar` (reorder needs a TalkBack action); existing `ColorContrastTest`/`TypographyTest`. Compose tested via Robolectric; no instrumentation suite; fixtures `MainDispatcherRule`, `InMemoryPreferencesDataStore`, `TestModels`.

## performance-plan

- **Hot paths:** continuously animating `WaveScrubber`, `WavyProgressLine`, `MorphShape`, player sheet drag; startup (`MegaPodcastPlayerApplication`, Hilt, WorkManager); `:core:media` data source chain, `EpisodePlayer`, `PlaybackService`; downloads (`EpisodeDownloader`, `ChunkedDataSource`, `MAX_PARALLEL_DOWNLOADS`, 8 MB chunks); `NewPipeAudioResolver` single lock; Room queries.
- **Baseline profiles:** none.
- **Measure:** `am start -W md.borisveriga.megapodcastplayer/.MainActivity`; `dumpsys gfxinfo md.borisveriga.megapodcastplayer` scrolling library and animating player sheet. Watch: `powershell -ExecutionPolicy Bypass -File tools\watch-perf.ps1` (optionally `-Watch <ip:port>`, `-Compile` to force `speed-profile` first, `-Skip 0,1` to resume after the link drops) runs the protocol of `docs/reports/2026-09-30-watch-performance-plan.md` — installed-package flags and dexopt status, device state, cold start ×3, 20 scripted swipes — and writes a summary plus framestats under `docs/reports/perf/`. Read the janky count and the 95th/99th percentile; the median is the swipe script's pacing, not the app. The numbers in that report are the baseline for the `wrist` build.
- **Watch baseline on the `wrist` build (2026-10-01):** COLD start 331–342 ms, 2 janky frames of 869 over 20 scripted swipes, every percentile at the script's 34 ms. Firebase's `FirebaseInitProvider` costs 30–37 ms of that start on the main thread, measured and accepted — it is the provider's own init, not the `CrashReporter` injection, so do not propose deferring ours for it. Still unmeasured: the tap → button-change round trip (two temporary `Log.d` lines, both logcats at epoch time; the report says where). A `force-stop` followed by a pause before `am start -W` measures a WARM start on this watch, because Play Services re-binds the chip service in the gap; the script starts with no pause.
- **Never propose:** none specific.

## project-report

- **Standing risks to re-check:** YouTube extraction (`:core:youtube`, NewPipeExtractor from JitPack) violates YouTube ToS and breaks when YouTube changes; CI branch trigger vs default branch (currently `main`, correct); detekt alpha on JDK 25.

## security-plan

- **Threat surface:** untrusted RSS/Atom feeds and YouTube extraction results → Room, Coil, Media3, `NewEpisodeNotifier`; `HttpsUpgradeInterceptor` cleartext fallback; the Wearable Data Layer (exported `WearCommandService`, `WearSenderVerifier`); supply chain (NewPipeExtractor via JitPack). No accounts, vault or crypto — don't audit for them.
- **Must not weaken:** `isPlayableMediaUrl` scheme allowlist (both enforcement points); release signing that fails without a keystore.
- **Threat scenarios:** hostile feed; compromised CDN incl. cleartext fallback; malicious app on phone or watch addressing `WearCommandService`; debug-signed impostor with the same app id; backup leaking library/history; stolen unlocked phone.
- **Accepted risks:** YouTube player-response extraction (legal/distribution risk, not a vulnerability — do not propose fixing).
