# Watch a YouTube episode as video

*30 September 2026. Feasibility analysis, the decisions it led to, and what was built.*

## The question

How hard is it to add video playback to an audio-only podcast player, either as a separate screen
or inside the player sheet?

## What the code already had (verified before designing)

- **The player was nearly ready.** `PlaybackService` builds a stock `ExoPlayer` with default
  renderers and no track restrictions; the session player `ChapterAwarePlayer` is a
  `ForwardingSimpleBasePlayer`, which forwards surface calls; and `MediaController` forwards a
  `setVideoSurfaceView` to the service's player. Queue, positions, chapters, the sleep timer and
  the watch key on episode id and position and never look at the media kind.
- **What blocked it.** `PlaybackConnection` kept the controller private and `PlaybackState` had no
  video fields; `NewPipeAudioResolver` read only `audioStreams`; YouTube publishes nothing sharper
  than 360p with sound and picture in one file, so 720p means a video-only stream merged with the
  audio stream; and nothing handled immersive mode, keep-screen-on or orientation.
- **What would have cost far more, and was left out.** RSS video podcasts (the parser drops
  `video/*` enclosures and `Episode` has no media type; a schema change wipes the library). Offline
  video (the download cache is keyed by the audio sentinel, never evicts, and the exporter assumes
  one audio file per episode). A DASH manifest (a new Media3 artifact and a verification refresh,
  a manifest built from extractor types the module forbids leaking, and a base URL that expires and
  bypasses the sentinel-keyed cache and invalidation).

## Decisions

| Question | Decision |
|---|---|
| Sources | YouTube shows only |
| Quality | 720p or better, with a picker on the screen, remembered in settings; a video with nothing that tall plays at the best it has |
| Placement | A separate full-screen route, not the player sheet |
| On leave (back, home, screen off) | Playback continues as sound at the same position |
| Fullscreen | Follows rotation; landscape hides the system bars; no picture-in-picture |
| Entry | A Watch button in the expanded player, shown only for YouTube episodes |
| Offline | Streaming only; downloads stay audio |

Overall effort: **medium**. No new Gradle artifact, no schema change, no download change.

## Design

A YouTube episode has two flavours of `MediaItem` with one `mediaId`. The stored
`youtube://video/<id>` sentinel plays as sound. A new, never-stored
`youtube://video-only/<id>?h=<height>` sentinel plays as a picture merged with that same sound:
`YouTubeMediaSourceFactory` turns it into a `MergingMediaSource` of two `ProgressiveMediaSource`s,
one per sentinel. Both halves go through the existing data source chain — read-only cache, 8 MB
chunking, invalidation, `ResolvingDataSource` — so the audio half of a downloaded episode still comes
off disk while its picture streams, one extraction serves both halves, and one 403 invalidates both.

Entering and leaving video is a custom session command, granted only to this app's own package,
that swaps the current item in place: add the other flavour after the current one, seek across to
it at the same position, remove the old one. That order is what keeps the position — removing the
item under the playhead would send ExoPlayer to the start of what follows — and it leaves every
other queue entry alone. The command is idempotent, so a rotation costs no re-buffer.

The screen attaches a plain `SurfaceView` through `PlaybackConnection`; no `media3-ui` artifact is
needed. It shows the picture on start and hands back to sound on stop or dispose, except when the
activity is only changing configuration.

The `isPlayableMediaUrl` allowlist rejects the video-only sentinel by construction (its prefix does
not match the audio sentinel's), so a feed cannot mint one and nothing that stores a URL will accept
one.

## What changed

- `:core:model` — `VideoQuality`; the video-only sentinel helpers in `YouTubeUrls.kt`.
- `:core:youtube` — `YouTubeVideoResolver`, implemented by the same `NewPipeAudioResolver` from one
  cached extraction per video; `selectVideoCandidate` (MP4 first, then the lower frame rate).
- `:core:media` — `VideoMode.kt` (flavours, the swap, the mode outcomes), `YouTubeMediaSourceFactory`,
  `VideoQualitySource`; the data spec resolver resolves both sentinels and chunking covers both;
  the persistence listener de-duplicates the queue mid-swap; `PlaybackState` gains the video fields;
  `PlaybackConnection` gains `enterVideo`, `exitVideo` and the surface calls; `PlaybackService`
  handles the two commands.
- `:core:datastore`, `:core:data` — the remembered rendition.
- `:feature:player` — `video/` package (`VideoViewModel`, `VideoScreen`, `QualitySheet`), the Watch
  button in the expanded player, `PlayerSheetScaffold.hidden`, a `video-screen` golden.
- `:app` — `Route.Video`; the navigation bar and the sheet step aside for it.
- `:wear` — nothing; `NowPlayingSnapshot` carries no media kind.

## Verification

### Local checks, as of writing

- Unit tests pass for `:core:model`, `:core:datastore`, `:core:youtube`, `:core:media`,
  `:core:data` and `:feature:player`, including the new `video-screen` golden (recorded, opened
  and checked at light, dark and 200 % text; no existing golden changed) and `:app` compiles.
- Detekt ran on every changed module and raised three findings, each fixed afterwards: a
  when-branch spacing in `PlaybackService`, a return count in `youTubeVideoOnlyRefOrNull`, and a
  `core/youtube/detekt-baseline.xml` entry re-keyed for `extract`'s new return type.
- **Still to run** after those three fixes, because the full gate was stopped by the machine
  running out of memory: `detekt --continue`, `testDebugUnitTest test --continue` (the untouched
  modules and the re-run of the three fixed files), `lintDebug --continue` and `assembleDebug`.

### On-device checklist (Fold 7)

- Watch from the expanded player starts video at the same position; the notification still controls it.
- The quality sheet lists the video's heights; changing one keeps the position with a single re-buffer.
- Seek, 1.5x speed and the sleep-timer fade all stay in sync in video mode.
- Back, home, screen off: sound continues without a gap; the sheet and the watch show it.
- Rotate the closed phone: landscape goes immersive, a swipe reveals the bars, no re-buffer.
- Unfold mid-video: no re-buffer; both orientations look right on the inner screen.
- A downloaded YouTube episode: the audio half comes from the cache.
- Wi-Fi to mobile mid-play: the 403 recovers through re-extraction of both halves.
- The next queued episode is RSS: the video screen pops itself and sound carries on.
- Airplane mode, then Watch: a clear error, and sound resumes on back.

### Risks to watch

- YouTube throttling on the larger video-only transfer; chunking now covers both sentinels.
- Seeking in merged video-only MP4 depends on the stream carrying an index; OTF streams are filtered out.
- An episode downloaded but offline: the picture cannot load, the merged source errors, back
  restores cached audio.

## Addendum: offline video (30 September 2026, later)

The *Offline* decision above is reversed: a YouTube episode's video can now be downloaded at any
quality the quality picker offers.

- **How.** The picture is downloaded as a second Media3 download of the same episode, content id
  `<episodeId>#video`, whose URI is the video-only sentinel for the chosen height. That sentinel is
  exactly what the player mints when it shows that height, so the merged source finds the picture
  in the download cache the same way the audio half already found the audio. No schema change: the
  download index is the only record of a video download.
- **The audio comes along.** The picture plays merged with the audio, so downloading a video also
  downloads the audio when it is not already on the device.
- **One quality per episode.** Choosing another height removes the old picture and queues the new
  one under the same id; Media3 runs the add once the removal has deleted the old bytes.
- **Removal.** Every existing removal path (the downloads screen, the episode sheet's audio
  button, delete-after-playing, remove all) removes the video with the audio; the episode sheet's
  button says so once a video is kept. The video screen's sheet and the episode sheet's quality
  dialog can delete just the video. Removing a show removes no downloads at all, audio or video —
  a gap that predates video and now leaks larger files.
- **Playback.** Entering video on an episode with a finished video download asks for the downloaded
  height rather than the remembered one; offline, the quality picker offers the downloaded height.
- **UI.** A download button beside speed and quality on the video screen opens a sheet with the
  same quality chips, the download's state, and *Delete downloaded video* / *Cancel download*.
- **Episode sheet.** *Play audio* / *Play video* side by side, then *Download audio* and
  *Download video*; the video download asks for a quality in a dialog first.
- **Downloads screen.** Finished rows carry *Audio* and *Video · 720p* badges.
- **Not done.** The export is still audio only; removing a show leaves its downloads behind.
