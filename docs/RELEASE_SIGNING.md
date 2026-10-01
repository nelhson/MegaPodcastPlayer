# Release signing

A release build with no signing key **fails**:

```
* What went wrong:
Execution failed for task ':app:packageRelease'
> No signing key for the release build.
```

That is deliberate. It used to fall back to the Android SDK's debug key, which is public — anyone
can re-sign an APK signed with it. Worse, the Wearable Data Layer routes purely on *package name
plus signing certificate*, so an app built by anyone with the debug key and the
`md.borisveriga.megapodcastplayer` application ID could send `WearCommand`s to a real installation and read
back its `NowPlayingSnapshot`: episode titles, show titles, the whole queue.

Debug builds are unaffected and still use the debug key.

## Setting up a real key

Both APKs must be signed with the **same** key — the phone and the watch app only talk to each
other because their application ID and certificate match.

The quick way, which does both steps below and prompts once for a password:

```powershell
powershell -ExecutionPolicy Bypass -File tools\create-release-keystore.ps1
```

By hand:

```bash
keytool -genkeypair -v \
  -keystore megapodcastplayer-release.jks \
  -alias megapodcastplayer \
  -keyalg RSA -keysize 4096 -validity 10000
```

Then create `keystore.properties` at the repository root:

```properties
storeFile=megapodcastplayer-release.jks
storePassword=…
keyAlias=megapodcastplayer
keyPassword=…
```

`keystore.properties`, `*.jks` and `*.keystore` are all git-ignored. Keep the keystore and its
passwords somewhere you will still have them in five years: losing the key means every installed
copy of the app has to be uninstalled before an update can be installed, because Android will not
accept an APK signed with a different certificate.

`storeFile` is resolved relative to the repository root.

## The `wrist` build type is not a release

Both application modules have a third build type, `wrist`: release's R8 configuration and
`debuggable=false`, signed with the **debug** key. It exists because the watch was slow for as long
as it ran the debug build — 80 MB of unshrunk dex, no baseline profile, and a `debuggable` flag that
makes ART refuse to compile the package ahead of time at all — and the same source shrunk is 3 MB
and cold-starts in a sixth of the time (`docs/reports/2026-09-30-watch-performance-plan.md`).

```bash
./gradlew :wear:installWrist          # the watch's daily build
./gradlew :app:installWrist           # the phone can have the same, when wanted
```

Its trust is exactly the debug build's: same key, same application ID, so it pairs with a debug
build on the other device with no uninstall, and an APK from it is as re-signable as a debug one.
That is why it is a build type with its own name rather than the flag below — `distribute` builds
`…Release` tasks, and nothing named `wrist` can be mistaken for one — and why the rule for it is the
rule for debug APKs: install it on your own devices, hand it to nobody. The keystore guard below
leaves it alone on purpose; that guard is for the artifact that *claims* to be a release.

Crashlytics uploads the `wrist` mapping file when the build is made, because it is the build whose
crashes actually arrive and an unmapped R8 stack trace is unreadable. The build type key on each
report tells `wrist` from `release`.

## Sideloading without a key

For a local install where the signature does not matter:

```bash
./gradlew :app:assembleRelease :wear:assembleRelease -PallowDebugSigningForRelease=true
```

This restores the old debug-key behaviour for that one invocation. Never use it for anything you
hand to someone else, and never set it in `gradle.properties` — a flag that has to be typed is the
whole mechanism. For a day-to-day install of the shrunk build, prefer `installWrist` above, which
needs no flag and cannot be confused with a release.

## CI

CI has no keystore and does not need one: it builds and tests the debug variant only. If a release
build is ever added there, pass the key through repository secrets and write `keystore.properties`
in a step rather than committing anything.
