# Crash reporting

Firebase Crashlytics, on both APKs, reporting to one Firebase project (`megapodcastplayer`).

The phone and the watch share an application ID, so they are **one** Firebase Android app and one
`google-services.json` serves both. What tells their reports apart is the `buildType` custom key and
the process breadcrumb each writes on start-up ("Phone process started" / "Watch process started").

## Why the watch reports separately

The watch is not a screen for the phone. It holds its own copy of an episode, plays it off the
wrist, keeps its own position and reconciles later — most of that happens with the phone out of
Bluetooth range. A watch-side crash there leaves no trace on the phone at all, which is exactly the
window this is for.

## What is wired

| Piece | Where |
| --- | --- |
| `CrashReporter` — the interface every module injects | `:core:common`, `core/common/…/crash/` |
| `FirebaseCrashReporter` — the only code that touches Firebase | `:core:common` |
| `NoOpCrashReporter` — the binding when Firebase is absent | `:core:common` |
| `CrashModule` — chooses between them at runtime | `:core:common`, `…/di/` |
| `AndroidCrashlyticsConventionPlugin` — applies the two Gradle plugins | `build-logic/convention/` |
| `google-services.json` | `app/` and `wear/` |

Firebase is on exactly one module's compile classpath. Everything else — features, `:core:data`,
`:app`, `:wear` — injects `CrashReporter` and cannot see Crashlytics at all. That is what keeps the
unit tests runnable without a Firebase project, and what makes replacing the backend a one-file job.

## What gets reported

**Uncaught exceptions and ANRs** need no code. Crashlytics installs its handler from a content
provider, before `Application.onCreate`, which is why a crash during start-up is still caught.

**Non-fatals** are the point of the abstraction. This app survives most of its failures on purpose —
one unreachable feed does not abort a refresh of the other nineteen, a Data Layer write with no
watch in range is simply lost — and each of those leaves a `Result.failure` that nothing reads.
Wired so far:

- `OfflineFirstPodcastRepository.refreshAll` — a feed that failed to refresh, with the feed URL as a
  custom key rather than in the message, so every such failure groups into one issue.
- `ChapterResolver` — a publisher's chapters document that could not be fetched or read.
- `EpisodeAudioSender` — a channel the watch would not accept, and a transfer that stopped short.
  Kept apart because the causes differ: out of range versus a gap in the download cache.

The rule for adding one: report a failure a person would want to know about **after** the fact and
that the code has already decided not to show anyone. Not expected outcomes, not user mistakes, and
nothing on a hot path — every call crosses into a native library and writes to disk.

**Having no network is an expected outcome.** A request that fails because the device is offline —
or because Android has cut a backgrounded app off from the network, which fails DNS with
`EAI_NODATA` even with a connection up — is the device's state, not a bug. Network call sites check
`isConnectivityFailure` (`:core:common`) and skip the report; the failure is still logged, and a
feed that failed is still named in the library's refresh message. Until that check existed, one
backgrounded refresh filed a non-fatal per show, and those were the only issues in the dashboard.

## Reporting is on in debug builds

The usual advice is to disable collection for debug, on the assumption that debug runs on a desk.
Here it is the other way round: `install_on_devices` sideloads **debug** APKs onto the Fold and the
Watch, and those are the builds this app actually lives in. Switching debug off would leave the only
builds anyone uses unreported. The `buildType` key is what separates the two in the dashboard.

## google-services.json

It lives in `app/` and `wear/` — the `com.google.gms.google-services` plugin reads it from the module
directory and looks nowhere else, so the repository root does not work. Both copies come from the
same Firebase app entry; re-download once and overwrite both.

It is **committed**, and this repository is public. That is deliberate and it is Google's documented
position: the Android config carries no secret. The API key in it identifies the project rather than
authorising anything, and it ships inside every APK regardless, so keeping it out of git would only
hide it from CI. The exposure that remains is that someone could send junk reports to this project's
Crashlytics.

Worth doing once, in the Google Cloud console: restrict that API key to Android apps, package
`md.borisveriga.megapodcastplayer` plus the release signing certificate's SHA-1. If Firestore,
Storage or Auth are ever switched on in this project, that changes the picture entirely — those need
security rules, and the key stops being uninteresting.

## Builds without it

A clone or fork with no `google-services.json` still builds. `AndroidCrashlyticsConventionPlugin`
applies the Firebase plugins only when the file is there, and `CrashModule` falls back to
`NoOpCrashReporter` when no `FirebaseApp` initialised.

The cost of that leniency would be a release that silently ships with no crash reporting, so a
**release** build without the file fails at execution time with an actionable message. Debug builds,
`detekt`, `lint` and IDE sync keep working. Same shape as the missing-keystore guard in
`configureSharedSigning`, and for the same reason.

## The R8 mapping file

A release stack trace is unreadable without it, so `assembleRelease` uploads it — except when
`-PallowDebugSigningForRelease=true` is passed. That flag already means "this artifact must never
reach anyone": CI's release smoke build and a local sideload of a release variant. Their mapping
files describe nothing anyone will ever look up, and making every CI run depend on a Firebase upload
succeeding would trade a real signal for an unrelated flake.

So: the APKs that go to a device through `distribute` upload their mappings, and nothing else does.

## Related

- `docs/RELEASE_SIGNING.md` — the other thing a real release build needs.
- `docs/DEPENDENCY_VERIFICATION.md` — Crashlytics added ~50 pinned artifacts; a version bump here
  means regenerating `gradle/verification-metadata.xml`.
