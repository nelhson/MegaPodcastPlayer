# Crash reporting

There is none that leaves the device. Handled failures are written to the device log, and nothing the
app records is sent anywhere. This was a decision, made on 2026-10-08, not an absence.

## Why

Until then both APKs reported to Firebase Crashlytics. Read call site by call site, a report carried
far more than "something broke": YouTube video and playlist ids in exception messages, a feed URL as
a custom key that stuck to every later report in the process, stack frames naming the extractor
(`org.schabi.newpipe…`), and device details tied to an installation id. For a personal player, that
is a record of what someone listens to, held by a third party, in exchange for a dashboard. The
dashboard lost.

Firebase was removed whole rather than switched off. Crashlytics brings Firebase Sessions and
Installations with it, and those talk to Google on start-up whether or not collection is enabled; a
flag would have left the network traffic and only stopped the reports.

## What is wired

| Piece | Where |
| --- | --- |
| `CrashReporter` — the interface every module injects | `:core:common`, `core/common/…/crash/` |
| `LogcatCrashReporter` — the only implementation: logcat, tag `MegaPodcastPlayer` | `:core:common` |
| `NoOpCrashReporter` — for tests that do not care | `:core:common` |
| `CrashModule` — binds the logcat reporter | `:core:common`, `…/di/` |

The interface stays because the reason for it stays: this app survives most of its failures on
purpose — one unreachable feed does not abort a refresh of the other nineteen, a Data Layer write with
no watch in range is simply lost — and each of those would otherwise leave a `Result.failure` that
nothing reads.

## Reading it

```
adb logcat -s MegaPodcastPlayer        # handled failures (W) and process starts (I)
adb logcat -b crash                    # uncaught exceptions, which the platform logs itself
```

The phone and the watch each keep their own log; the watch's is read over its own adb connection.
Logcat is a ring buffer, so a failure from yesterday is usually gone — that is the price of sending it
nowhere, and it is paid on purpose. Reproduce with the device plugged in.

## What belongs in it

A failure a person would want to know about **after** the fact and that the code has already decided
not to show anyone. Not expected outcomes, and not user mistakes.

**Having no network is an expected outcome.** A request that fails because the device is offline — or
because Android has cut a backgrounded app off from the network, which fails DNS with `EAI_NODATA`
even with a connection up — is the device's state, not a bug. Network call sites check
`isConnectivityFailure` (`:core:common`) and skip the record.

## If this is ever revisited

Anything that sends a record off the device is a privacy change and is decided by the user, not
slipped in with a feature. The settings screen says, under About, that nothing leaves the device; a
change here makes that row false.
