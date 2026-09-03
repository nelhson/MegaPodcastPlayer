# Remove old/new version compatibility logic

## Context

MegaPodcastPlayer is a personal, sideloaded app. The phone (`:app`) and watch (`:wear`) are always
installed together from one build, and the phone's database only ever meets the build that wrote
it. Yet the code carries machinery whose sole purpose is to let *different* versions coexist:
tolerant JSON decoding "so the two APKs can be updated independently", enum-name fallbacks for
"a row written by a newer build, then downgraded", four Room migrations with committed schema
snapshots and a 640-line migration test suite. None of it will ever run. The user wants it gone to
simplify the code.

Decisions already made by the user:
- **Room:** delete the migrations and reset the database to version 1. On-device data is lost once
  (re-subscribe shows). Accepted.
- **Tolerance:** remove *compatibility* tolerance only. Keep robustness against corrupt bytes over
  Bluetooth and truncated files (decode-returns-null, missing-file handling).

**Status: applied 2026-09-03.** All five steps below are done; `detekt`, `testDebugUnitTest test`,
`lintDebug` and `assembleDebug` all pass. Line numbers are as of the sweep that produced the plan
and no longer match the sources. Two things were done beyond the written steps: the four stale
`TopLevelPropertyNaming:Migrations.kt` entries were pruned from `core/database/detekt-baseline.xml`,
and `PhonePlayerClient.phoneNodeIds()` was folded into `capablePhoneNodeIds()` rather than renamed,
since the fallback was its only remaining body. Not yet done: the on-device check on the Fold and
the Watch (see *Verification*), and the first install of this build recreates the phone's database.

## Inventory of compatibility logic (verified)

### A. Wear protocol (`:core:wearprotocol`) — pure compat

| File | Line | What | Purpose |
|---|---|---|---|
| `core/wearprotocol/.../WearMessages.kt` | 12–14, 19–21 | `ignoreUnknownKeys = true` and KDoc "an older peer must survive fields added by a newer one; this is the whole reason the two APKs can be updated independently" | compat only |
| same | 22–23 | `encodeDefaults = true` "so that a peer whose defaults differ still reads the real value" | compat only |
| `core/wearprotocol/.../WearCommand.kt` | 14–15 | KDoc: explicit `@SerialName` "so that renaming a Kotlin class cannot break a watch running an older build" | comment is compat; the names themselves are harmless |
| `core/wearprotocol/src/test/.../WearMessagesTest.kt` | 128–133 | `a command variant this build does not know decodes to null` (`start_a_fire`) | compat only |
| same | 135–144 | `unknown snapshot fields from a newer app are ignored` (`chapterCount`) | compat only |

### B. Phone and watch Data Layer receivers — compat *comments* on code that also guards corruption

| File | Line | What |
|---|---|---|
| `app/.../wearsync/WearCommandService.kt` | 57–58 | "A payload this build cannot parse comes from a newer watch app; ignoring it is the whole of the compatibility policy" |
| `wear/.../data/PhonePlayerClient.kt` | 219–220 | "…which is how a watch survives meeting a phone running a newer version of the app" |
| `wear/.../data/WatchLibrary.kt` | 90–91 | same sentence |
| `wear/.../ongoing/NowPlayingChipService.kt` | 67–68 | same sentence |

The `?: return null` in each stays (corrupt payload); only the justification changes.

One entry in this area is real logic rather than a comment:

| File | Line | What |
|---|---|---|
| `wear/.../data/PhonePlayerClient.kt` | 180–189 | `phoneNodeIds()` falls back from capability-advertising nodes to *every* connected node, "which covers a phone running a build old enough not to advertise it". Compat only, and inconsistent: `currentLink()` (171–178) already reports such a phone as `APP_NOT_INSTALLED`, so sending and link status disagree. |

### C. Watch-side episode index (`:wear`) — compat

| File | Line | What |
|---|---|---|
| `wear/.../data/WatchEpisodeStore.kt` | 95–99 | `Json { ignoreUnknownKeys = true; encodeDefaults = true }` with comment "A watch downgraded to an older build must still read an index a newer one wrote" |
| same | 64–66 | `StoredIndex` wrapper "so the format can gain fields without becoming a list" — forward-compat shape; keep the wrapper (cheap, readable) but drop the justification |

### D. Room (`:core:database`) — versioned schema and migrations

| Path | What |
|---|---|
| `core/database/.../MegaPodcastPlayerDatabase.kt` (v=5, `exportSchema = true`, KDoc lines 16–17) | version pinned at 5 for migration chain |
| `core/database/.../migration/Migrations.kt` | `MIGRATION_1_2`, `MIGRATION_2_3`, `MIGRATION_3_4`, `MIGRATION_4_5` |
| `core/database/.../di/DatabaseModule.kt` lines 34–36 | `.addMigrations(...)` and the "deliberately no fallbackToDestructiveMigration" comment |
| `core/database/schemas/…/{1..5}.json` | exported schema snapshots |
| `core/database/src/test/.../migration/MigrationTest.kt` | 640 lines of upgrade tests |
| `core/database/.../model/PodcastEntity.kt` lines 39–43, 52–54 | KDoc tying `defaultValue` to the migration DDL |
| `core/database/.../model/EpisodeEntity.kt` line 63 | same |
| `core/database/.../MegaPodcastPlayerTypeConverters.kt` lines 18–27, 32–41 | enum-name fallback "a row written by a newer build, then downgraded" |
| `build-logic/convention/src/main/kotlin/AndroidRoomConventionPlugin.kt` lines 9–10, 20, 27 | schema export arg and `room-testing` test dependency, both only for migrations |
| `gradle/libs.versions.toml` line 115 | `androidx-room-testing` (unused once MigrationTest is gone) |

### E. DataStore (`:core:datastore`) — compat fallbacks

| File | Line | What |
|---|---|---|
| `core/datastore/.../UserPreferencesDataSource.kt` | 35–38 | speed clamp "a value written by an older build (or a corrupt file)" — **keep** the clamp (ExoPlayer throws on ≤0), reword comment |
| same | 47–53, 58–60 | keep-limit clamp justified by "a value from a future build" — clamp itself is harmless, reword |
| same | 65–76 | `libraryLayout` enum-name lookup with fallback "a build that offered a third layout would otherwise crash" — compat only |
| `core/datastore/src/test/.../UserPreferencesDataSourceTest.kt` | 153–164 | `a layout this build does not know falls back rather than throwing` ("What a downgrade looks like") — compat only |

### Not compat (leave alone)

- `core/network/.../di/NetworkModule.kt:48` `ignoreUnknownKeys` — tolerates the *iTunes* API, an external service.
- `NotificationCompat`, `ContextCompat`, `sourceCompatibility` etc. — AndroidX/JVM, unrelated.
- `versionCode = 1` / `versionName = 1.0` in `libs.versions.toml` — one constant, nothing branches on it; no `BuildConfig.VERSION_CODE` use anywhere.
- No protocol version constants, versioned paths, capability negotiation or peer-version checks exist in `WearPaths` or anywhere else; the compat policy was entirely "decode leniently".
- `core/model/.../YouTubeUrls.kt:27–39` `PLAYLIST_FEED_PREFIX` "no longer fetched, survives as an identity": still minted by `youTubePlaylistFeedUrl` for every new YouTube show and hashed into the podcast id. It is the id format, not a compat shim; leave it.
- Kotlin defaults on `NowPlayingSnapshot`, `OfflineEpisode`, `StoredEpisode` fields: they double as the "nothing playing" identity values and as ordinary constructor defaults; leave them.
- Storing enum *names* rather than ordinals (`setLibraryLayout`, Room type converters): readable on disk; keep the writes, only the lenient *reads* go.

### Stale documentation of the policy

| File | Line | Claim |
|---|---|---|
| `docs/REFACTORING_PLAN.md` | 593–594 | "It is versioned by `@SerialName`, tolerates unknown fields on both sides" |
| `.claude/skills/feature-plan/SKILL.md` | 79–80, 106 | "a change here is a two-sided release, because the phone and watch update independently" |
| `core/media/src/test/.../MediaItemsTest.kt` | 105–106 | "a row written before the allowlist shipped, or one the migration has not swept yet" |

## Removal plan (to execute later, in this order)

Each step is independently buildable and testable; run `detekt`, unit tests and `lintDebug` after each.

### Step 1 — Wear protocol becomes strict
1. `WearMessages.kt`: `Json { ignoreUnknownKeys = true; encodeDefaults = true }` → `Json` default (or
   `Json { encodeDefaults = true }` only if a test proves a default is needed; it is not — both sides
   share the classes). Rewrite the object KDoc: decoding returns null only for a corrupt or
   truncated payload.
2. `WearCommand.kt`: keep `@SerialName`s (they make `dumpsys` readable); replace lines 14–15 with
   that reason.
3. `WearMessagesTest.kt`: delete the two "newer app" tests (lines 128–144). Keep
   `garbage decodes to null rather than throwing`. Add one test that an unknown field now fails
   decoding (returns null), to pin the new strictness.
4. Comments in `WearCommandService.kt:57`, `PhonePlayerClient.kt:219`, `WatchLibrary.kt:90`,
   `NowPlayingChipService.kt:67`: replace the "newer version" sentence with "a corrupt payload".
5. `PhonePlayerClient.kt:180–189`: `phoneNodeIds()` becomes `capablePhoneNodeIds()`; delete the
   `ifEmpty { connectedNodes }` fallback and its KDoc. A phone that does not advertise the capability
   is one without the app, exactly as `currentLink()` already says. (No unit test exists for this
   class; none needs adding for a deletion.)

### Step 2 — Watch episode index becomes strict
1. `WatchEpisodeStore.kt:95–99`: drop `ignoreUnknownKeys`; keep `encodeDefaults` only if the
   round-trip test needs it (it does not: reader and writer are the same build). Delete the comment.
2. Line 64–66: reword `StoredIndex` KDoc to "the index file's contents".
3. `WatchEpisodeStoreTest.kt`: confirm no test seeds extra fields (grep shows none); add nothing.

### Step 3 — Room back to version 1, no migrations
1. Delete `core/database/.../migration/Migrations.kt` and `.../migration/MigrationTest.kt`.
2. `DatabaseModule.kt`: remove `.addMigrations(...)` and the comment at 34–35. Add
   `.fallbackToDestructiveMigration(dropAllTables = true)` with a one-line comment: "personal build;
   a schema change wipes and recreates rather than migrating".
3. `MegaPodcastPlayerDatabase.kt`: `version = 1`, `exportSchema = false`; drop KDoc lines 16–17.
4. Delete `core/database/schemas/` (all five JSON files).
5. `AndroidRoomConventionPlugin.kt`: remove the `room.schemaLocation` KSP arg and the
   `androidx-room-testing` `testImplementation`; update KDoc lines 9–10.
6. `libs.versions.toml`: remove `androidx-room-testing` (line 115). Dependency verification: a
   removed artifact leaves a stale entry in `gradle/verification-metadata.xml`, which is harmless;
   optionally prune per `docs/DEPENDENCY_VERIFICATION.md`.
7. `PodcastEntity.kt` (39–43, 52–54) and `EpisodeEntity.kt` (63): keep the `defaultValue`s (they are
   still what a fresh `CREATE TABLE` uses and what `INSERT`s omitting the column rely on) but delete
   the "must match MIGRATION_x_y" sentences.
8. `MegaPodcastPlayerTypeConverters.kt`: `stringToDownloadState` → `DownloadState.valueOf(value)`,
   `stringToPodcastSource` → `PodcastSource.valueOf(value)`; delete the "newer build" KDoc. A row
   can only hold a name this build wrote. Check `core/database/src/test` for a converter test that
   seeds an unknown name and delete it.
9. Install note for the user: the first install of this build on the Fold recreates the database.
   Downloaded audio in the Media3 cache is keyed by URL and is untouched, but it will be orphaned
   until the show is re-added; either accept that or clear app data before installing.

### Step 4 — DataStore reads stop defending against other builds
1. `UserPreferencesDataSource.kt:72–76`: `LibraryLayout.valueOf(name)` instead of the
   `entries.firstOrNull` lookup; drop KDoc 65–71. Keep the `?: LibraryLayout.DEFAULT` for the
   absent key.
2. Lines 35–36: reword to "clamped on read as well as on write so a corrupt file can never hand
   ExoPlayer a non-positive speed". Lines 47–53: drop the "future build" sentence; the
   `coerceAtLeast(KEEP_ALL)` clamp stays as input validation.
3. `UserPreferencesDataSourceTest.kt:153–164`: delete the "downgrade" test.

### Step 5 — Docs
1. `CLAUDE.md` gains one bullet under "Constraints that features break": *"No version
   compatibility. Phone and watch are always installed from the same build and the database is
   never migrated; a schema or protocol change is free, and a destructive Room fallback is on."*
2. `docs/REFACTORING_PLAN.md:593–594`: rewrite the out-of-scope bullet (protocol is no longer
   tolerant; it is simply shared source). Lines 60 and 536 mention the v2→v3 sweep migration; mark
   as superseded by the reset.
3. `.claude/skills/feature-plan/SKILL.md:79–80, 106`: the watch contract is *not* a two-sided
   release any more; a change there needs both APKs reinstalled from one build, which is already
   how `install_on_devices` works.
4. `core/media/src/test/.../MediaItemsTest.kt:105–106`: keep the test (defence in depth behind the
   parser guard is still valid), drop the "migration has not swept yet" clause.

## What stays, and why

- `runCatching { decode }.getOrNull()` in `WearMessages` and the `?: return null` in the four
  receivers: a Bluetooth payload can be truncated; a crash inside a Play Services callback is worse.
- `WatchEpisodeStore.load()` treating a missing/unreadable index as empty: truncated file on a
  watch that lost power mid-write.
- Speed and keep-limit clamps in `UserPreferencesDataSource`: ExoPlayer input validation.
- `@SerialName` on `WearCommand` variants: readability of `dumpsys` output, not compat.
- `@ColumnInfo(defaultValue)`: still the schema's `DEFAULT`, needed for partial inserts.

## Verification (when executing)

```
.\gradlew.bat detekt --continue
.\gradlew.bat testDebugUnitTest test --continue
.\gradlew.bat lintDebug --continue
.\gradlew.bat assembleDebug
```

Then the `install_on_devices` skill onto both the Fold and the Watch from the same build; confirm
the watch remote still controls playback, an episode still copies to the watch, and the phone opens
with an empty library that can be repopulated. Grep afterwards for `ignoreUnknownKeys`,
`addMigrations`, `Migration(`, `older build`, `newer build`, `downgrade` — only
`NetworkModule.kt` should still match `ignoreUnknownKeys`.
