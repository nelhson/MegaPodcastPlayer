---
name: distribute
description: Build Pados and publish it to Firebase App Distribution for testers. Use when asked to distribute, ship a build to testers, send a test build, release to testers, or push a build to Firebase.
---

# Distribute to testers

Dispatch the `Firebase App Distribution` GitHub Actions workflow, watch it, and report back.

**The build always happens in CI, never locally.** The signing keystores and
`app/google-services.json` are git-ignored and exist only on Boris's Windows machine, so a
remote session (phone, cloud container) cannot build. Dispatching CI is the only path that works
from everywhere — do not offer to run `gradlew appDistributionUpload*` instead.

## 0 — Preconditions

Run `gh auth status`. It must succeed and the token must include the `workflow` scope. If it
fails, say so plainly and stop — do not guess at an alternative.

## 1 — Parse the request

| Input | Values | Default |
| --- | --- | --- |
| variant | `dev`, `release`, `both` | **`dev`** |
| groups | comma-separated Firebase tester groups | `testers` |
| notes | free text shown to testers | auto-generated (step 3) |

`/distribute`, `/distribute release`, `/distribute both`, and
`/distribute dev "fixes the vault search"` are all valid. Free text that isn't a variant name is
the release note.

`dev` builds the `debug` build type: applicationId `md.borisveriga.pados.debug`, app name
"Dev Pados", no R8, installs alongside production. `release` builds the minified, upload-key-signed
production artifact.

## 2 — Preflight the git state

CI builds the commit that is **pushed to GitHub**, not the working tree. Check all three:

```bash
git rev-parse --abbrev-ref HEAD     # the ref to dispatch
git status --porcelain              # must be empty
git rev-list --count @{u}..HEAD     # must be 0
```

If the tree is dirty or the branch is ahead of its upstream, **stop and tell Boris exactly what
would be left out**. Offer to commit/push, but never push on your own initiative. If the branch
has no upstream at all, say so — it cannot be dispatched.

## 3 — Compose the release notes

Skip this entirely if Boris supplied a note; use his text verbatim.

Otherwise find the last successful distribution and summarize what happened since:

```bash
gh run list --workflow=firebase-distribution.yml --status success --limit 1 --json headSha
git log <headSha>..HEAD --oneline
```

No prior successful run → use `git log --oneline -10` instead.

Write 1–3 short lines **for testers, not for developers**: what changed from the outside, in plain
language. Don't paste commit subjects verbatim, don't mention refactors or internal file names.
Keep it under ~300 characters.

## 4 — Dispatch

```bash
gh workflow run firebase-distribution.yml \
  --ref <branch> \
  -f variant=<variant> \
  -f groups=<groups> \
  -f notes="<notes>"
```

## 5 — Find the run

Dispatch is asynchronous, so the run does not exist immediately. Poll every few seconds until one
appears (give up after ~30s and say the dispatch didn't register):

```bash
gh run list --workflow=firebase-distribution.yml --branch <branch> --limit 1 \
  --json databaseId,status,url,createdAt
```

**Report the run URL to Boris right away**, before waiting — that way he has a link even if the
session ends.

## 6 — Watch it

```bash
gh run watch <databaseId> --exit-status
```

Run this in the background: a `dev` build takes ~5 minutes, `release` longer because of R8 and
resource shrinking, `both` longest.

## 7 — Report

**On success**, state: variant, branch, tester groups, the notes that were actually sent, and the
run URL. For `dev`, add that the app shows up as **"Dev Pados"** and installs alongside the real
one, so testers keep their production vault.

**On failure**, run `gh run view <databaseId> --log-failed` and report the real error. Common ones:

| Symptom | Cause | Fix |
| --- | --- | --- |
| `./gradlew: Permission denied`, exit 126, fails in <1 min | `gradlew` lost its executable bit (easy to do from Windows) | `git update-index --chmod=+x gradlew` and commit |
| `appDistributionUpload*` fails, `no JSON input found` | `FIREBASE_SERVICE_ACCOUNT` is unset, so the workflow wrote a zero-byte credentials file | Set the secret from a service-account key JSON |
| An early restore step fails, `base64: invalid input` | A repo secret is missing or empty | Check `gh secret list`; see "Required secrets" below |
| `Group ... not found` / `invalid group` | The `groups` value doesn't match a real Firebase group | Firebase Console → App Distribution → Testers & Groups |
| `App not found` / app id mismatch | `GOOGLE_SERVICES_JSON` secret is stale | Re-upload base64 of `app/google-services.json` |
| Release build unsigned | `KEYSTORE_*` secrets missing → `keystore.properties` absent → `app/build.gradle.kts:60` skips the signing config | Set the four `KEYSTORE_*` secrets |
| Testers must uninstall before a dev update | `DEBUG_KEYSTORE_*` secrets missing → runner-generated debug key | Set the four `DEBUG_KEYSTORE_*` secrets |

## Required secrets

Documented in the header of `.github/workflows/firebase-distribution.yml`. Ten total:
`GOOGLE_SERVICES_JSON`, `FIREBASE_SERVICE_ACCOUNT`, four `KEYSTORE_*` (release), four
`DEBUG_KEYSTORE_*` (dev). Verify with `gh secret list` — names only.

**Never print a secret value, a keystore password, or the contents of `keystore.properties` /
`keystore.debug.properties` into the transcript.** When setting a secret, pipe the value straight
into `gh secret set` so it never appears in output.

## Notes

- `versionCode` is not bumped per distribution. Firebase distinguishes builds by binary hash, so
  repeated uploads work, but testers see the same version string. Mention this if Boris asks why
  two builds look identical.
- Nothing here needs `app/build.gradle.kts` to change — `firebaseAppDistribution` (line 149) already
  reads groups and notes from the `FIREBASE_GROUPS` / `FIREBASE_RELEASE_NOTES` env vars that the
  workflow sets from the dispatch inputs.
