# Project profile — MegaPodcastPlayer

## Identity

- **What it is:** personal, sideloaded offline-first podcast player (`:app`) with a Wear OS companion (`:wear`) that is a remote control, tile and complication — the watch plays nothing itself.
- **Application ID(s):** `md.borisveriga.megapodcastplayer` for both `:app` and `:wear` (debug suffix: none, deliberately — ever). Namespaces differ (`…megapodcastplayer` / `…megapodcastplayer.wear`).
- **Modules:** apps `:app`, `:wear`; pure JVM `:core:model`, `:core:wearprotocol`; Android `:core:{common,database,datastore,network,youtube,media,data,designsystem}`; test fixtures `:core:testing`; features `:feature:{listen,library,downloads,search,podcast,player,moments,settings}`. Sources at `<module>/src/main/kotlin/md/borisveriga/megapodcastplayer/…`.
- **Conventions live in:** `CLAUDE.md`, `build-logic/convention/`, `config/detekt/detekt.yml`, `gradle/libs.versions.toml`, `docs/` (`REFACTORING_PLAN.md`, `RELEASE_SIGNING.md`, `DEPENDENCY_VERIFICATION.md`, `CRASH_REPORTING.md`, `SCREENSHOT_TESTS.md`, `COPY_RULES.md`).
- **Default branch:** `master` (there is no `main`).

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
- **Build type:** `debug`. `installRelease` does not exist (no `keystore.properties` → `configureSharedSigning` leaves release unsigned; `failReleasePackagingWithoutAKeystore`).
- **Install tasks:** `installDebug` only.
- **Launch / smoke check:** `monkey -p md.borisveriga.megapodcastplayer -c android.intent.category.LAUNCHER 1` (two namespaces, one app id — do not hardcode a component).
- **Quirks:** debug `wear-debug.apk` is ~95 MB (no R8), so wireless install often dies with `EOF` — push the built APK directly. `:wear` depends only on `:core:wearprotocol` + `:core:common`, so its APK is often already up to date. Stale pre-rename APK on the watch (old `/bpodcat/command` paths) was diagnosed 2026-09-03 via the phone's `dumpsys … gms.wearable`.

## distribute

- **Path:** B — local signed APKs. `.github/workflows/ci.yml` only verifies (detekt, assembleDebug, tests, lint, Kover, debug-signed release smoke build discarded); no Firebase App Distribution.
- **Details:** keystore via `tools\create-release-keystore.ps1` or `docs/RELEASE_SIGNING.md`. Build `:app:assembleRelease :wear:assembleRelease`; archive both mapping files to `dist\<sha>\`. Both APK certificate digests must match. Never offer `-PallowDebugSigningForRelease` — a debug-signed impostor could send `WearCommand`s and read `NowPlayingSnapshot`. Recipient caveats: both APKs needed; version 1.0 update may need uninstall (loses library). YouTube extraction blocks Play Store distribution — raise it if publishing comes up. No baseline profiles.

## merge-to-main

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
- **Measure:** `am start -W md.borisveriga.megapodcastplayer/.MainActivity`; `dumpsys gfxinfo md.borisveriga.megapodcastplayer` scrolling library and animating player sheet.
- **Never propose:** none specific.

## project-report

- **Standing risks to re-check:** YouTube extraction (`:core:youtube`, NewPipeExtractor from JitPack) violates YouTube ToS and breaks when YouTube changes; CI branch trigger vs default branch (currently `master`, correct); detekt alpha on JDK 25.

## security-plan

- **Threat surface:** untrusted RSS/Atom feeds and YouTube extraction results → Room, Coil, Media3, `NewEpisodeNotifier`; `HttpsUpgradeInterceptor` cleartext fallback; the Wearable Data Layer (exported `WearCommandService`, `WearSenderVerifier`); supply chain (NewPipeExtractor via JitPack). No accounts, vault or crypto — don't audit for them.
- **Must not weaken:** `isPlayableMediaUrl` scheme allowlist (both enforcement points); release signing that fails without a keystore.
- **Threat scenarios:** hostile feed; compromised CDN incl. cleartext fallback; malicious app on phone or watch addressing `WearCommandService`; debug-signed impostor with the same app id; backup leaking library/history; stolen unlocked phone.
- **Accepted risks:** YouTube player-response extraction (legal/distribution risk, not a vulnerability — do not propose fixing).
