---
name: install_on_devices
description: Build MegaPodcastPlayer and sideload it onto Boris's Galaxy Fold 7 (phone) and Galaxy Watch Ultra 2 (Wear OS) over adb. Use when asked to install on devices, sideload, put the app on the phone or the watch, deploy to the Fold or the Watch, or try it on real hardware.
---

# Install on devices

Two APKs onto two devices: `:app` on the Galaxy Fold 7, `:wear` on the Galaxy Watch Ultra 2.

This is a **local** build, unlike `distribute` — it needs no keystore, no CI and no network beyond
adb, and it is the right tool for "let me look at this on the watch".

## The constraint that governs everything

`:app` and `:wear` both declare `applicationId = "md.borisveriga.megapodcastplayer"` — no `applicationIdSuffix`
on debug, deliberately — and both must be signed with the **same certificate**. The Wearable Data
Layer routes messages purely on package name plus signing certificate, so a phone and a watch
holding differently-signed builds will install fine, launch fine, and silently never see each other.
`AndroidApplicationConventionPlugin` and `configureSharedSigning` exist to enforce exactly this.

**Therefore: always install both sides from the same machine in the same build type.** If you install
only one side, say so explicitly in the report — a half-updated pair is the most confusing state
this app can be in, because nothing about it looks broken.

## 0 — Find adb

`adb` is **not on PATH** on this machine. It lives in the SDK:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
```

If that path does not exist, stop and say so — do not go hunting for another copy.

## 1 — Enumerate and classify the devices

```powershell
& $adb devices -l
```

Read the **state** of every line, not just the serial:

| State | Meaning | Action |
| --- | --- | --- |
| `device` | ready | install to it |
| `offline` | the connection dropped, usually wireless debugging | step 2 |
| `unauthorized` | the RSA prompt was never accepted | accept it on the device screen |
| `no permissions` | driver/udev problem | report; do not attempt workarounds |

**Classify by asking the device, never by matching a model string.** Model numbers change with every
generation and a hardcoded one silently installs the wrong APK:

```powershell
& $adb -s <serial> shell getprop ro.build.characteristics   # contains "watch" => the watch
& $adb -s <serial> shell getprop ro.product.model           # for the report
```

Anything whose characteristics contain `watch` gets `:wear`. Everything else gets `:app`.

For recognition only — do **not** match on these:

| Device | `ro.product.model` | `adb devices -l` model | product | SDK |
| --- | --- | --- | --- | --- |
| Galaxy Watch Ultra 2 | `SM-L715F` | `SM_L715F` (underscores) | `projectv2ul` | 37 |
| Galaxy Fold 7 | `SM-F966B` | `SM_F966B` | `q7qxxx` (device `q7q`) | 36 (Android 16) |

Note the two spellings: `adb devices -l` prints `model:SM_L715F` with an underscore while
`getprop ro.product.model` returns `SM-L715F` with a hyphen. Another reason not to match on it.

If more than one non-watch device is attached, ask Boris which one is the Fold rather than guessing.

## 2 — When a device is missing or offline

This is the normal case, not the exception. The watch has no USB port, so it is **always** wireless
debugging, and that connection drops whenever the watch sleeps, changes network or reboots.

`adb reconnect offline` is worth one try, but **observed behaviour on this watch is that it drops
the device from the list entirely rather than recovering it** — after which only re-connecting from
the watch works. Do not keep polling for it to come back; it will not.

```powershell
& $adb reconnect offline
& $adb devices -l          # if this is now empty, go straight to re-connecting
```

To re-connect — on the watch: Settings → Developer options → Wireless debugging (the port changes
every time it is toggled), then:

```powershell
& $adb connect <ip>:<port>              # the port under "Wireless debugging", changes every time
& $adb pair <ip>:<pairPort> <code>      # only if it has never been paired, or was un-paired
```

Both devices must be on the same Wi-Fi network as this machine.

**Do not silently install to whatever is left.** If the Fold is there and the watch is not, install
the phone, then report plainly which device was skipped and why — see the constraint above.

## 3 — Build type: debug

Use `debug` unless Boris explicitly says otherwise.

**`installRelease` does not exist in this project.** There is no `keystore.properties` at the
repository root, so `configureSharedSigning` leaves the release `signingConfig` null; AGP registers
no install task for an unsigned variant, and `failReleasePackagingWithoutAKeystore` fails the
packaging task anyway with an actionable message. Confirmed against `:app:tasks` / `:wear:tasks` —
the only install tasks are `installDebug` and `installDebugAndroidTest`.

A release sideload is possible with `-PallowDebugSigningForRelease=true`, but the result is
debug-signed, which is the exact thing that flag's documentation warns against. If Boris wants a
real release build on hardware, that is a `distribute` job, not this one.

## 4 — Install

Target one device at a time with `ANDROID_SERIAL`; with two devices attached an untargeted install
either fails or picks the wrong one.

```powershell
$env:ANDROID_SERIAL = "<phone-serial>"; .\gradlew.bat :app:installDebug --console=plain
$env:ANDROID_SERIAL = "<watch-serial>"; .\gradlew.bat :wear:installDebug --console=plain
Remove-Item Env:\ANDROID_SERIAL
```

PowerShell has no inline `VAR=x cmd` prefix, so it must be set and cleared as above. Clear it when
you are done — a stale `ANDROID_SERIAL` misroutes every later adb and Gradle command in the session.

Run the two installs **sequentially**, not in parallel: they share one Gradle daemon and one build
directory, and the watch build is small enough that there is nothing to win.

### Retrying a failed watch install without rebuilding

`installDebug` assembles and installs. When only the *install* failed, the APK is already on disk and
Gradle would just re-run the same fragile transfer. Push it directly instead — the error messages are
better too:

```powershell
& $adb -s <watch-serial> install -r wear\build\outputs\apk\debug\wear-debug.apk
```

`:wear` depends only on `:core:wearprotocol` and `:core:common`, so most changes elsewhere in the
repo leave `wear-debug.apk` genuinely up to date — check its timestamp before assuming a rebuild is
needed.

## 5 — Launch (only if asked)

```powershell
& $adb -s <serial> shell monkey -p md.borisveriga.megapodcastplayer -c android.intent.category.LAUNCHER 1
```

`monkey` resolves the launcher activity itself, which matters here: the two modules have different
namespaces (`md.borisveriga.megapodcastplayer` and `md.borisveriga.megapodcastplayer.wear`) under one application ID, so
a hardcoded component name is right for only one of them.

## 6 — Report

One line per device: model, serial, module, build type, outcome. State explicitly if either device
was skipped, and whether the pair is now on matching builds.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | installed copy was signed with a different key — a release build, another machine, or a rebuilt debug keystore | uninstall first; **see the warning below** |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | `versionCode` comes from `gradle/libs.versions.toml` and both apps share it, so this means the installed build carried a higher code than the catalog does now | uninstall first |
| App installs on both, watch shows nothing / controls do nothing | phone and watch have different certificates or build types | reinstall **both** from this machine, same build type |
| `INSTALL_FAILED_INSUFFICIENT_STORAGE` on the watch | Wear devices have very little free space | uninstall unused watch apps; the `:wear` APK is small, so this usually means the watch is genuinely full |
| Gradle installs to the wrong device | `ANDROID_SERIAL` unset or stale | set it per install, clear it after |
| `adb` hangs on first use | it is starting its server | expected once; give it a few seconds |
| `InstallException: EOF`, `DeviceException` | the adb link died mid-transfer — the usual outcome on the watch, because the debug `:wear` APK is **~72 MB** and wireless debugging rarely survives it | step 2, then push the already-built APK directly (below) rather than re-running Gradle |
| Watch drops to `offline` mid-install | wireless debugging timed out | step 2, then re-run only the `:wear` install |

**Before suggesting an uninstall, warn Boris what it costs.** `adb uninstall md.borisveriga.megapodcastplayer`
(or `:app:uninstallDebug`) removes app-private storage with the app: the Room database — every
subscription, queue entry and playback position — and the `episode_downloads` cache, which is the
user's entire offline library. That is a real loss on his own daily-driver phone, not a test device.
Say it plainly and let him decide; never uninstall pre-emptively to make an install succeed.

## Notes

- Both installs are debug builds: no R8, so they are larger and slower than anything a tester sees.
  If something is slow *only* on these builds, that is expected and not a finding.
- The phone build carries the whole app; the watch build deliberately carries no Room, no Retrofit
  and no phone-side code — it talks to the phone over the Data Layer. A watch that launches but
  shows no playing episode is usually a pairing/signing problem, not a watch-side bug.
