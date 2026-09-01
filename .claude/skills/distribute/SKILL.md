---
name: distribute
description: Build signed release APKs of BPodcat (phone and watch) for handing to someone else. Use when asked to distribute, ship a build, cut a release, produce a signed APK, or send the app to someone.
---

# Distribute

Produce **signed release APKs** of `:app` and `:wear` that can be handed to another person.

## What this project does and does not have

**There is no Firebase App Distribution, no tester groups and no release workflow.** The only
GitHub Actions workflow is `.github/workflows/ci.yml`, which runs detekt, `assembleDebug`, unit
tests, lint and Kover — it never produces a distributable artifact. There is no
`google-services.json`, no Firebase Gradle plugin and no `firebaseAppDistribution` block anywhere in
the build. Do not offer to dispatch a distribution workflow; there is nothing to dispatch.

So distribution here means: build signed APKs locally, then hand them over by whatever means Boris
chooses. If he wants CI-based distribution, that is a feature to build, not a command to run — say
so plainly rather than improvising a workflow file.

For getting a build onto Boris's own phone and watch, use **`install_on_devices`** instead. It is
faster, needs no keystore, and is almost always what "put this on my phone" means.

## The constraint that governs everything

`:app` and `:wear` share `applicationId = "md.borisveriga.bpodcat"` and **must be signed with the
same certificate**. The Wearable Data Layer routes messages purely on package name plus signing
certificate, so a mismatched pair installs, launches, and silently never connects. Both APKs come
out of the same build with the same key, so this holds automatically — but never hand someone a
phone APK from one signing setup and a watch APK from another.

## 0 — Preconditions

A release build with **no keystore fails on purpose**, with an actionable message. Check first:

```bash
ls keystore.properties          # at the repository root; git-ignored
```

Missing → stop and tell Boris. Point him at `docs/RELEASE_SIGNING.md`, which documents both paths:

```powershell
powershell -ExecutionPolicy Bypass -File tools\create-release-keystore.ps1   # does both steps
```

or `keytool -genkeypair` by hand plus a hand-written `keystore.properties` (`storeFile`,
`storePassword`, `keyAlias`, `keyPassword`).

**Never offer `-PallowDebugSigningForRelease=true` as a way to get past this.** It exists for a local
sideload only. The debug key is a world-known SDK artifact, so a release APK signed with it is
trivially re-signable — and because the Data Layer trusts package name plus certificate, anyone
building with the debug key and this application ID could send `WearCommand`s to a real installation
and read back its `NowPlayingSnapshot`: episode titles, show titles, the whole queue. That is the
reason the fallback was removed; do not quietly reintroduce it.

## 1 — Preflight the tree

A handed-out APK should correspond to a known commit:

```bash
git rev-parse --abbrev-ref HEAD
git status --porcelain            # ideally empty
git log --oneline -5
```

If the tree is dirty, say what is uncommitted and let Boris decide. Do not commit on your own
initiative, and do not refuse to build — it is his call.

## 2 — Version

`versionCode` and `versionName` live in `gradle/libs.versions.toml` (the `[versions]` table), and
both application convention plugins read them, so the phone and the watch always carry the same
version. Check what they say:

```bash
grep -n "^versionCode\|^versionName" gradle/libs.versions.toml
```

Before a build that will be handed over, ask whether to bump `versionCode` (and `versionName` if the
change is user-visible). A recipient updating in place over the same `versionCode` may hit
`INSTALL_FAILED_VERSION_DOWNGRADE`, or worse, silently keep the old build because Android sees no
upgrade. Quote the **commit SHA** in the handover message as well; the version alone does not
identify a build.

## 3 — Build

```powershell
.\gradlew.bat :app:assembleRelease :wear:assembleRelease --console=plain
```

R8 and resource shrinking are on for both. The watch module keeps R8 deliberately — it strips
unused Compose and Play Services code from an APK that has to fit on a watch.

Outputs:

| Module | Path |
| --- | --- |
| Phone | `app/build/outputs/apk/release/app-release.apk` |
| Watch | `wear/build/outputs/apk/release/wear-release.apk` |

## 3a — Archive the R8 mapping files

R8 keeps line numbers (`-keepattributes SourceFile,LineNumberTable` in both `proguard-rules.pro`
files) but renames everything, so a stack trace from a distributed build is only readable with the
mapping file from **that exact build**. There is no crash reporter and no upload, so the mapping
must be kept by hand, next to the APKs, named by commit:

```powershell
$sha = git rev-parse --short HEAD
New-Item -ItemType Directory -Force "dist\$sha" | Out-Null
Copy-Item app\build\outputs\mapping\release\mapping.txt  "dist\$sha\app-mapping.txt"
Copy-Item wear\build\outputs\mapping\release\mapping.txt "dist\$sha\wear-mapping.txt"
```

`dist/` is git-ignored. A trace is read back either in Android Studio (Analyze → Analyze Stack
Trace, pointing it at the mapping file) or with `retrace` from the SDK command-line tools, which
are **not** installed on this machine as of 2026-09-01 (`sdkmanager "cmdline-tools;latest"` adds
`cmdline-tools\latest\bin\retrace.bat`).

Skip this only if Boris says the build is throwaway; a mapping file that was not kept cannot be
recreated.

## 4 — Verify the pair is actually signed with one key

Worth doing every time, because a mismatch is invisible until someone reports the watch "not
working". `apksigner` is in the SDK build-tools:

```powershell
$signer = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools\*\apksigner.bat" | Select-Object -Last 1
& $signer verify --print-certs app\build\outputs\apk\release\app-release.apk
& $signer verify --print-certs wear\build\outputs\apk\release\wear-release.apk
```

The **certificate SHA-256 digests must match**. If they differ, stop — the pair is broken and
handing it over wastes the recipient's time.

## 5 — Report

State: branch, commit SHA, whether the tree was clean, both APK paths and sizes, and the shared
certificate digest (the digest is public, unlike the keystore password — printing it is fine).

Add the two caveats the recipient needs: both APKs must be installed for the watch to work, and
they are all version 1.0, so an in-place update may need an uninstall first — which on a real
installation deletes the Room database and the `episode_downloads` cache, i.e. every subscription
and every downloaded episode.

## Never print

Keystore passwords, the contents of `keystore.properties`, or the keystore file path. When a command
would echo them, redirect or filter. Certificate **digests** are fine and are the point of step 4.

## Notes

- YouTube support resolves audio by extracting YouTube's own player response, which their terms of
  service do not permit — see the note in `YouTubeAudioResolver.kt`. This is an accepted trade-off
  for a sideloaded personal build and is **not compatible with distribution on Google Play**. If
  Boris talks about publishing to the Play Store, raise this before anything else.
- No baseline profiles exist in this project, so release startup is not profile-optimised. Mention it
  only if startup performance comes up.
