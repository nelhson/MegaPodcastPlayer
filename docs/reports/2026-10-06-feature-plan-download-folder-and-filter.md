# Feature Plan: download folders and Audio/Video filter — 2026-10-06

Revised the same day. The first version saved real file copies to a folder picked with Android's
system picker. Boris chose instead to leave how downloads (video included) are stored and fetched
exactly as they are, and to build the folders in the UI and business layers only.

**Status (2026-10-06, end of day):** M1–M4 implemented, not yet committed. Departures from the plan
below: folder membership is one DataStore key, not three; there is no periodic prune; the swipe
snackbar *names* the folder but has no *Change* action (the move sheet lives in `:feature:downloads`
and the swipe in `:feature:podcast`; moving is one swipe away on the Downloads tab); the delete-folder
question is one dialog with an "also delete its downloads" checkbox rather than two variants; and the
folder-name dialog is tested through `folderNameProblem` rather than on screen, because a text field
in a dialog never lets Robolectric go idle.

## Summary & user value

1. **Folders for downloads.** The user creates named folders ("Commute", "Lectures", "Keep") and
   each download lives in one. A download goes to a folder when it is made, can be moved later, and
   the Downloads tab browses by folder. Audio and video of an episode share the episode's folder.
2. **A filter at the top of Downloads:** All · Audio · Video.

In the app, a folder behaves like one on disk: create, rename, delete, choose where a download goes,
move it, see each folder's size. Underneath, each folder is only a label on a download; the bytes
stay in Media3's cache, where they are now.

**What the user is not told by the UI, and should know:** these folders are not visible in Files or
on a PC, and they go when the app is uninstalled, like the downloads themselves. That is acceptable
for a personal build. The copy must still not claim otherwise: no "on your phone" or path-like text.

## Current-state analysis

- **Storage, unchanged:** one Media3 `SimpleCache` at `filesDir/episode_downloads`
  (`core/media/.../di/DownloadModule.kt:65, 164-174`). Audio is keyed by its URL or
  `youtube://video/<id>`. Video is keyed by `<episodeId>#video` over the `youtube://video-only/` sentinel
  (`core/media/.../download/VideoDownloads.kt:19-70`). Nothing here changes.
- **One row per episode:** the list comes from `EpisodeDao.observeDownloadsWithShow()`
  (`core/database/.../dao/EpisodeDao.kt:74-89`, audio `download_state`). Video reaches the screen as
  `DownloadsUiState.videoDownloads: Map<episodeId, VideoDownload>` (`DownloadsViewModel.kt:62-74,
  195-227`). A video never exists without its audio row: `downloadVideo` queues the audio too
  (`MediaDownloadRepository.kt:156-165`), and `remove()` drops both halves
  (`EpisodeDownloader.kt:226-229`). So a folder is a property of an **episode's download**, not of
  each half.
- **Precedent for per-download data kept outside Room:** the user's download order is a DataStore
  string list, `download_order` (`core/datastore/.../UserPreferencesDataSource.kt:131-150, 328, 471`).
  `MediaDownloadRepository.observeDownloads` combines it with the DAO (:79-92). Folder membership
  follows the same pattern.
- **Room is off the table for this:** schema version 2, `fallbackToDestructiveMigration(dropAllTables
  = true)` (`core/database/.../di/DatabaseModule.kt:33`). A new table or column would wipe the
  library **and the moments** on the daily-driver phone. DataStore costs nothing.
- **Downloads screen:** pinned top bar → `StorageCard` item → sections FAILED, DOWNLOADING, WAITING,
  READY (`feature/downloads/.../DownloadsScreen.kt:352-443`). Only READY rows can be dragged.
  Rows are `SwipeActionsRow`s, and a full swipe adds to the queue (:495-628).
- **Where downloads start:** the swipe-to-download on show lists (the `SwipeDownload` preference,
  `core/model/.../SwipeDownload.kt:13`), `EpisodeSheet` on the show page (`VideoDownloadSheet` at
  `feature/podcast/.../EpisodeSheet.kt:355`), the player's video screen (`VideoScreen.kt:229`),
  auto-download after a feed refresh, and the show page's "download and export".
- **UI precedents:**
  - The dropdown `FilterChip` (`ShowChip`, `feature/moments/.../MomentsScreen.kt:395-430`) is the
    model for a folder chip.
  - The single-choice chip row on the show page (`PodcastDetailScreen.kt:1123-1162`) is the model
    for Audio/Video.
  - `SettingsChoiceRow<T>` (`core/designsystem/.../component/SettingsRows.kt:102`).
  - `DeleteDownloadDialog` (`core/designsystem/.../component/DeleteDownloadDialog.kt:33`).
- **Reorder hazard (applies to both features):** `move(visibleIds, …)` (`DownloadsViewModel.kt:396-400`)
  stores only the ids on screen, and `setDownloadOrder` replaces the whole list. Hidden ids become
  `UNPLACED` (`MediaDownloadRepository.kt:91, 279`). Any filtered view would scramble the order of
  everything it hides.

## Design

### Model (`:core:model`, pure JVM)

- `DownloadFolder(id: String, name: String)`. The id is a random UUID minted once and never derived
  from the name, so a rename keeps every membership.
- `DownloadFolders(folders: List<DownloadFolder>, membership: Map<episodeId, folderId>,
  defaultFolderId: String?)` with pure operations:
  - `folderOf(episodeId)` falls back to the **built-in "Downloads" folder** (id `null`). It always
    exists and can't be renamed or deleted, so no download is ever folderless.
  - `create(name)`, `rename(id, name)`, `delete(id)` (members return to Downloads), `move(ids, folderId)`.
  - `pruned(downloadedIds)` drops memberships of downloads that no longer exist.
  - Validation: names are trimmed, non-blank, at most 40 chars, and unique ignoring case.
    Validation failures are a result value, not an exception.
- `DownloadFilter { All, Audio, Video }` + `List<EpisodeWithShow>.filteredBy(kind, folderId,
  folders, videoDownloads)`:
  - **Video** = rows that have a video download in any state.
  - **Audio** = rows without a video ("audio only"), so Audio + Video partition All.
- `mergeVisibleOrder(fullOrder, visibleReordered)`: writes the reordered visible ids back into the
  slots they held in the full order; hidden ids don't move. With nothing hidden it reduces to the
  current behaviour.

### Storage (`:core:datastore`)

- One key, `download_folders`, holding the whole `DownloadFolders` value (folders, membership,
  default) as strict JSON via `DownloadFoldersCodec`. Implemented as one key rather than the three
  first planned, so deleting the default folder is a single atomic write.
- **Stored identities:** folder ids are new identities. Episode ids are reused as keys, as
  `download_order` already does. No URL spelling, cache key or podcast-id hash is touched.
- **Not** in the OPML settings export, which stays subscriptions-only. The DataStore is in Android
  backup, so after a restore the memberships point at episodes without downloads; `pruned()` cleans
  them up.

### Business layer (`:core:data`)

- New `DownloadFolderRepository` (interface + DataStore implementation, matching the
  `DownloadRepository`/`MediaDownloadRepository` split):
  - `observeFolders(): Flow<DownloadFolders>`
  - create, rename, delete, move, setDefault
  - `assign(episodeId, folderId?)`
- **Assignment on download:** `MediaDownloadRepository.download(…)` and `downloadVideo(…)` take an
  optional `folderId`. It is resolved in this order: the argument, then the folder the episode
  already belongs to (a video joining its audio stays put), then the default folder. It is recorded
  before the request is sent, so the row appears in the right folder while it is still WAITING.
  Auto-download uses the default folder, unchanged otherwise.
- **Forgetting:** `removeDownload`, every Media3 `NOT_DOWNLOADED` event in `recordDownloadStatus`
  (which is how delete-after-playing ends) and `recordAllDownloadsRemoved` drop the membership. No
  periodic prune: a stale entry left by a backup restore only names an episode with no download,
  and the screen lists downloads, not memberships.
- **Deleting a folder** has two variants:
  - **Delete folder**: members move to Downloads, and nothing is deleted from the device.
  - **Delete folder and its downloads**: calls `remove(id)` for each member, behind a confirm dialog
    that counts the downloads and bytes (COPY_RULES §"Delete means leaving the device").
- Folder sizes are summed from `sizeOrDownloaded` plus completed video bytes, the same arithmetic as
  `DownloadsViewModel.totalBytes` (:211-212). That arithmetic moves into `:core:model` so both use it.

### UX

**Downloads tab**
- First list item: a chip row.
  - `[📁 Commute ▾]`: a dropdown folder chip. It lists All folders, Downloads, each folder with its
    count, a divider, *New folder…* and *Manage folders*.
  - Then `All · Audio · Video`. These show only while at least one video exists; with no videos they
    could only ever answer "everything" or "nothing".
- **StorageCard:** with a folder chosen, the card reads that folder's count and size, still against
  the device's free space. With "All folders" it is today's card.
- **Rows:** a long-press or an overflow item *Move to folder…* opens a bottom sheet listing the
  folders and *New folder…*. When viewing All folders, the row's metadata line names its folder.
  The " · " separator is its own string resource.
- **Multi-select:** none exists today, so "move many" is out of scope for v1.
- **Empty states (COPY_RULES §8):**
  - An empty folder gets "Nothing in Commute" + "Go to your library".
  - A kind filter that matches nothing gets "No downloads match this filter" + "Clear filter".
  - Neither reuses "Nothing downloaded".
- **Selection is not persisted** (Library/Moments precedent), with one exception: the folder chip
  remembers the last folder for the session (`rememberSaveable`), so unfolding the Fold keeps it.
- **Manage folders:** a sheet or dialog listing folders with rename, delete and *Make default*.

**Where downloads start**
- `VideoDownloadSheet` and the `EpisodeSheet` download action gain a "Save to: Commute ▾" row.
  It defaults to the default folder and is shown only once a folder other than Downloads exists.
- Swipe-to-download stays one gesture and uses the default folder. A snackbar says "Downloading to
  Commute", with "Change" opening the move sheet.

**Settings → Downloads**
- New row: *Save new downloads to*, a `SettingsChoiceRow` or a dropdown over the folders.

**Watch:** no change.

### Kind filter, folder filter and reorder

- Both narrow the list before `groupIntoSections()`. `move()` always goes through
  `mergeVisibleOrder`.
- One global order is kept; each folder shows its members in that order. Per-folder orders would
  need a second ordering store and buy little.

## Implementation milestones

**M1. Audio/Video filter**
- `core/model/.../DownloadFilter.kt` (new): `DownloadFilter`, `filteredBy`, `mergeVisibleOrder`.
- `feature/downloads/.../DownloadsViewModel.kt`:
  - filter state and `showKindFilter`;
  - reset to All when the last video goes;
  - `move()` through the merge.
- `DownloadsScreen.kt`: chip row item, filtered-empty state, previews.
- `feature/downloads/src/main/res/values/strings.xml`.

**M2. Folder model and storage**
- `core/model/.../DownloadFolders.kt` (new), plus the size arithmetic moved out of the VM.
- `core/datastore/.../UserPreferencesDataSource.kt`: three keys.
- `core/data/.../repository/DownloadFolderRepository.kt` + `DataStoreDownloadFolderRepository.kt`
  (new), with the Hilt binding.
- `MediaDownloadRepository.kt` + `DownloadRepository.kt`:
  - `folderId` on download and downloadVideo;
  - pruning on remove, removeAll and delete-after-playing.
- `:core:testing`: a fake `DownloadFolderRepository`.

**M3. Browse and manage folders in Downloads**
- `DownloadsViewModel.kt`:
  - inject `DownloadFolderRepository`;
  - add folder selection to the combine.
- `DownloadsScreen.kt`:
  - folder dropdown chip;
  - per-folder `StorageCard`;
  - folder name in the row metadata;
  - *Move to folder…* sheet.
- New `DownloadFolderSheets.kt` in `:feature:downloads`: move sheet, new/rename dialog, manage sheet,
  delete-folder dialog.
- Strings.

**M4. Choose a folder when downloading, and the default**
- `core/designsystem/.../component/VideoDownloadSheet.kt`: an optional folder row (state hoisted;
  the sheet takes folders and a callback).
- `feature/podcast/.../EpisodeSheet.kt` and `PodcastDetailViewModel.kt`; `feature/player/.../VideoScreen.kt`
  and `VideoViewModel`: pass the folder through.
- The swipe-download path's snackbar with "Change".
- `feature/settings/.../SettingsScreen.kt` + `SettingsViewModel.kt`: *Save new downloads to*.

M1 ships alone. M2 has no UI and is safe to merge. M3 is the visible feature, and M4 is polish on top.

## Test plan

Run `.\gradlew.bat testDebugUnitTest test --continue`, then detekt and lint.

**`:core:model` (`test`)**
- `filteredBy` for kind × folder.
- `mergeVisibleOrder`: no-op under All; hidden ids keep their positions; ids missing from the stored
  order; empty lists.
- `DownloadFolders`:
  - create, rename and delete;
  - delete returns members to Downloads;
  - name validation (blank, too long, duplicate ignoring case);
  - `pruned`;
  - `folderOf` falls back to Downloads.

**DataStore and repositories**
- `UserPreferencesDataSourceTest`: the three keys round-trip, and strict JSON rejects an unknown
  field.
- `DataStoreDownloadFolderRepositoryTest`: every operation; the default folder resets to Downloads
  when its folder is deleted.
- `MediaDownloadRepository` tests:
  - assignment order: argument, then existing folder, then default;
  - a video joins its audio's folder;
  - membership pruned on remove and removeAll.

**Downloads tab**
- `DownloadsViewModelTest`:
  - the folder and kind filters change the sections;
  - storage totals per folder and for all;
  - a drag inside a folder keeps the order of rows in other folders;
  - the kind filter resets when the last video goes.
- `DownloadsScreenTest`:
  - dropdown contents and selection;
  - the move sheet;
  - delete-folder dialog copy and counts;
  - both empty states;
  - chip state descriptions.

**Settings and podcast**
- `SettingsViewModelTest` and `SettingsScreenTest` for the default-folder row.
- Podcast/EpisodeSheet tests for the folder row being passed through.

**Screenshot goldens**
- Existing `downloads-*.png` will change (the chip row is drawn); the Settings golden will change too.
- New previews: a folder chosen with the dropdown open, the move sheet, Video selected, no match.
- Re-record through Bash with `-Pmegapodcastplayer.screenshots.record` and open every image.

**Checked on the device by hand:** the dropdown and sheets on the Fold, both folded and unfolded.
Rotation and unfold keep the chosen folder.

## Risks & open questions

1. **Honesty of the metaphor.** The user may expect to find the folder in Files. Mitigation: the copy
   never names a path or "phone storage", and the plan stays open to adding the file-copy option from
   the first version of this plan later.
2. **Assigning at swipe time.** One gesture can't also ask for a folder. The default plus a "Change"
   snackbar is the proposal. The alternative is asking every time once there are two or more folders.
3. **Two chip groups in one row** may crowd the 360 dp outer screen of the Fold. Fallback: put the
   folder chip on its own row, or put the folder picker in the top bar's title.
4. **"Audio" meaning:** audio only (proposed), or "has audio", which is the same as All.
5. Stale KDoc mentions `MIGRATION_2_3`/`MIGRATION_4_5` (`MediaUrls.kt:62`, `MediaItems.kt:17`,
   `PodcastDao.kt:20`), but no migrations exist. Worth fixing nearby. The project profile was
   corrected today.

## Out of scope

- Any change to how audio or video is downloaded, cached, keyed or played.
- Real files: a folder in shared storage, export to a folder picked with Android's system picker, an SD card.
- Nested folders, per-folder ordering, multi-select move.
- Folders on the watch, or in the OPML/settings export.
