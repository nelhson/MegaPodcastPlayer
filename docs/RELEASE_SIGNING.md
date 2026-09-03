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

## Sideloading without a key

For a local install where the signature does not matter:

```bash
./gradlew :app:assembleRelease :wear:assembleRelease -PallowDebugSigningForRelease=true
```

This restores the old debug-key behaviour for that one invocation. Never use it for anything you
hand to someone else, and never set it in `gradle.properties` — a flag that has to be typed is the
whole mechanism.

## CI

CI has no keystore and does not need one: it builds and tests the debug variant only. If a release
build is ever added there, pass the key through repository secrets and write `keystore.properties`
in a step rather than committing anything.
