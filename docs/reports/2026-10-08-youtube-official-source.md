# YouTube source: Extractor or YouTube's own player (2026-10-08)

A settings choice between the two ways this app can read and play a YouTube show, and what each
one gives up.

## What was asked

A runtime switch between the behaviour that is against YouTube's terms (the extractor: whole
playlists, direct streams, background sound, downloads) and one that is within them. The compliant
variant shows ten videos; the extractor shows all of them. Switching deletes nothing.

Decisions taken with the user before the plan: the compliant player is YouTube's embedded IFrame
player in a `WebView`; the limit is a fixed ten; what the extractor had is hidden under the official
source and comes back when it is chosen again.

## What was built

| Layer | Change |
|---|---|
| `:core:model` | `YouTubeSource { EXTRACTOR, OFFICIAL }`, `DEFAULT = EXTRACTOR`, `OFFICIAL_EPISODE_LIMIT = 10`. `OpenPlayerAs.EMBEDDED`. `youTubeWatchUrl`. |
| `:core:datastore` | `youTubeSource` flow (by name, lenient, distinct) and `setYouTubeSource`. |
| `:core:network` | `YouTubeAtomParser` restored from `dd2c192^` (SAX via `untrustedXmlParserFactory`) and `YouTubeAtomFeedDataSource`, which reads `feeds/videos.xml?playlist_id=` with no validators. |
| `:core:youtube` | `OfficialFeedPlaylistFetcher` cuts the feed to ten and maps a parse failure to `YouTubePlaylistUnavailableException`. Both fetchers are bound only under `@ExtractorPlaylists` / `@OfficialPlaylists`. The module still does not depend on `:core:datastore`. |
| `:core:database` | `observeByPodcastOrderedLimited`. No schema change. |
| `:core:data` | `fetchFeed` picks the fetcher per fetch. `observeEpisodes` cuts a YouTube show to ten and masks its download fields under OFFICIAL; `observeEpisode` masks; `observeInProgressEpisodes` drops YouTube rows. `rebuild` merges instead of replacing for a YouTube show under OFFICIAL. `MediaDownloadRepository` declines any download of a YouTube episode, skips them in auto-download and filters them out of every list. `YouTubeSourceApplier` removes YouTube episodes from the player's queue when OFFICIAL is chosen, on start too. |
| `:core:media` | `YouTubeSourceGate` mirrors the preference for the Media3 thread; `YouTubeDataSpecResolver` refuses either sentinel under OFFICIAL with `YouTubeAudioUnavailableException`. |
| `:feature:player` | `embedded/`: page builder and code tables (pure), `EmbeddedPlayerBridge`, `EmbeddedVideoViewModel` (pauses the app's player on entry, writes position through `PlaybackProgressRecorder`, marks played at the end), `EmbeddedVideoScreen` (`WebView`, paused on `ON_PAUSE`, destroyed on dispose; offline and failure states with *Open in YouTube*). |
| `:feature:podcast` | `isOfficialYouTube` on the state; the route opens `EMBEDDED` for every way of starting such an episode and never asks the app's player; the view model refuses every download, queue and quality call for it; the row loses *Play video*, the download swipe and *Play next*; the sheet loses its video half and its download button; *Download and export* is disabled. |
| `:feature:moments` | A moment on such an episode opens the embed at the moment's second through a new `onOpenEmbedded` on `MomentsRoute`. |
| `:feature:settings` | A *YouTube* section with a two-chip row; description names the trade-off and the number. |
| `:app` | `Route.EmbeddedVideo(episodeId, startMs)`; the shell navigates to it for `EMBEDDED` and hides the bar and navigation under it; `YouTubeSourceApplier.start()`; a `-keepclassmembers` rule for the bridge. |

## Known wrinkles

- The toggle changes behaviour, not what ships. NewPipe is in the APK under either choice, so an
  install set to OFFICIAL is within the terms in what it does, not store-distributable in what it
  contains. A build flavour without `:core:youtube`'s extractor is separate work.
- A chapter tap on an official YouTube episode opens the embed at the episode's own position, not
  at the chapter: the shell's `openPlayer` carries no second. A moment does, by its own callback.
- The embed is YouTube's: no background sound, nothing in the notification, the widget, the bar or
  on the watch. The screen says so in one line.
- A video whose owner has turned embedding off (error 101/150) cannot play here under OFFICIAL;
  the screen offers YouTube itself.
- Switching back to EXTRACTOR and refreshing puts the older videos the official feed could not see
  *above* the newer ones, via `placeNewEpisodesOnTop`. That is what any refresh does with old
  entries; *Delete and reload all episodes* puts the playlist's order back.
- A YouTube download mid-transfer when OFFICIAL is chosen fails (the resolver refuses it) and
  shows as failed; nothing is deleted, and it retries once EXTRACTOR is chosen again.
- Library counts still count the hidden rows.

## Verification

Run on 2026-10-08, all passing:

```
.\gradlew.bat detekt --continue
.\gradlew.bat lintDebug testDebugUnitTest test assembleDebug --continue
.\gradlew.bat :feature:player:testDebugUnitTest :feature:settings:testDebugUnitTest -Pmegapodcastplayer.screenshots.record
```

New goldens: `feature/player/src/test/screenshots/embedded-video-{player,offline,not-allowed}-*.png`,
read before being kept. The settings goldens did not change: the new section sits below the
captured height.

Not verified on a device. What needs a phone: the embed actually playing (and pausing when the
screen leaves the front), a YouTube show added under OFFICIAL showing ten videos, the switch back
to EXTRACTOR bringing the rest back, and a queued YouTube episode leaving the queue when OFFICIAL is
chosen.
