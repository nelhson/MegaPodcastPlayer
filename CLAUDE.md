# MegaPodcastPlayer

Personal, sideloaded podcast player for Android (`:app`) with a Wear OS companion (`:wear`) that is
a remote control, a tile, a watch-face complication, and — for episodes the phone has sent it — a
player of its own.
Kotlin, Jetpack Compose, Hilt, Room, Media3. Application ID `md.borisveriga.megapodcastplayer`.

This file is deliberately short. The conventions are enforced by code, so read the source of
truth rather than a summary of it:

- `build-logic/convention/` — the plugins every module applies (SDK levels, lint, signing, tests).
- `config/detekt/detekt.yml` — every override carries its own reason.
- `gradle/libs.versions.toml` — the only place a version is written.
- `docs/` — `REFACTORING_PLAN.md` (audit of 2026-08-29, largely applied), `RELEASE_SIGNING.md`,
  `DEPENDENCY_VERIFICATION.md`, and dated reports under `docs/reports/`.

## Layout

Nineteen modules. Sources live at `<module>/src/main/kotlin/md/borisveriga/megapodcastplayer/…`.

- Apps: `:app`, `:wear`.
- Pure JVM, shared with the watch: `:core:model`, `:core:wearprotocol`. No Android types here.
- Android infrastructure: `:core:common`, `:core:database`, `:core:datastore`, `:core:network`,
  `:core:youtube`, `:core:media`, `:core:data`, `:core:designsystem`.
- Shared test utilities, on every Android module's test classpath automatically: `:core:testing`.
- Features: `:feature:{library,downloads,search,podcast,player,settings}`.

## Commands (Windows: use `.\gradlew.bat`)

```
.\gradlew.bat detekt --continue                      # static analysis, fastest signal
.\gradlew.bat testDebugUnitTest test --continue      # both tasks: the JVM modules use `test`
.\gradlew.bat lintDebug --continue                   # warningsAsErrors; baselines are opt-in per module
.\gradlew.bat assembleDebug
.\gradlew.bat koverHtmlReportUnit koverLogUnit       # coverage of every module's unit tests; reporting only
```

`adb` is not on PATH; the `install_on_devices` skill knows where it is. Release builds fail
without `keystore.properties` by design; see `docs/RELEASE_SIGNING.md`.

## Constraints that features break

- **Episode audio URLs are identities, not addresses.** `youtube://video/<id>` is both the stored
  URL and the Media3 cache key; a feed URL is hashed into the podcast id. Changing either spelling
  orphans existing data.
- **Untrusted feed input reaches the media stack.** New URL or file handling goes through
  `isPlayableMediaUrl` in `:core:model`.
- **The watch pairing is package name plus signing certificate.** `:app` and `:wear` share both;
  no `applicationIdSuffix` on debug, ever. Install both sides from the same build.
- **Three things cross the Data Layer, and each has its own shape.** State is a *data item* (kept
  and replayed on connect), a command is a *message* (an event, never de-duplicated), and episode
  audio is a *channel* (tens of megabytes, one node, opened and closed per transfer). Putting one on
  another's transport is the mistake `:core:wearprotocol`'s `WearPaths` exists to prevent.
- **The watch's copy of an episode is the watch's.** It keeps its own index and its own position,
  and reports that position back with `ReportPosition`; a run out of Bluetooth range is reconciled
  when the phone is next reachable, not lost.
- **Never swallow `CancellationException`.** Use `suspendRunCatching` from `:core:common`; detekt's
  `TooGenericExceptionCaught` and `SwallowedException` enforce it.
- **Dependency verification is on.** A bump needs `gradle/verification-metadata.xml` refreshed;
  the procedure is in `docs/DEPENDENCY_VERIFICATION.md`.
- **YouTube extraction** (`:core:youtube`, NewPipeExtractor) is against YouTube's terms. Accepted
  for a personal build; a hard blocker for any store distribution.

## Working here

- KDoc on every file, class and function; unit tests for new or changed logic, run before done.
- No `TODO`/`FIXME` comments and no `!!` in production code (detekt rejects both).
- User-facing strings live in each module's `strings.xml`, never in Kotlin literals.
- CI (`.github/workflows/ci.yml`) runs on pushes to `master` (the working branch), on pull requests
  and on manual dispatch. It includes a debug-signed release smoke build whose APKs are discarded.
- Do not commit unless asked. Reports go to `docs/reports/YYYY-MM-DD-<name>.md`.
