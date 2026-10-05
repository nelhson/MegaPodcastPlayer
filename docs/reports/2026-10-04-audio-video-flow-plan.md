# Audio / video flow: reliability and UX plan

Scope: playing, downloading and managing an episode as audio or video on the phone, and switching
between the two. Nothing else in the app changes (no watch, moments, library, search, feed work).

This is a plan only, written on 2026-10-04. No code is written until a phase is asked for.

## Status — continue from here

| Phase | State |
|---|---|
| 1 — The black picture | **Done** on branch `video-black-picture`, 2026-10-04. Checked on the `Pixel_9a_2` emulator: rotation ×4, minimise and reopen, paused minimise, home and back, full screen. |
| 2 — Coming back after the app was closed | **Done**, 2026-10-04: 2.1–2.5 and 2.7 committed on `video-black-picture` (83a62f9), 2.6 and 2.8 in 21f6414. Checked on `Pixel_9a_2`: play #493, Play on #496, kill, reopen and #496 is in the bar; a skip made while paused is still there after a kill (58.0 s before and after); the session comes back with both queue entries, #496 current, with no restore from the UI; an offline cold start shows the bar at the saved position with no error, and Play starts from there once online; a media play key after the process was killed resumes at the saved position. For 2.6, each with the process killed in the background (`run-as … kill -9`, new pid) and the app reopened: the expanded sheet comes back expanded; the video screen comes back as the video screen, poster, paused at 0:15 where it was left, session state `NONE` (nothing prepared), and Play carries on with the picture from there; a minimised video comes back as the bar with its poster. |
| 3 — Switching modes | **Done**, 2026-10-05. Committed on `video-black-picture`: 3.1 (21f6414), 3.2 (84b5885), 3.3–3.5 (ba89671), 3.6–3.7 (172dca1). Checked on `Pixel_9a_2`, for 3.1: *Play* on a show's row opens the sheet; *Play video* opens the video screen; the media notification opens the video screen when the player was left in video and the sheet when it was left in audio; a moment tapped opens the sheet in audio, and the video screen when a video was minimised, at the moment's position. For 3.2: *Play audio* on the episode playing as video opens the sheet and it keeps playing (0:04 to 0:18 across the switch); *Play video* on the episode playing as audio opens the video screen with the position carrying on (81.8 s, 85.8 s, 92.0 s around the tap); with Wi-Fi and data off, *Play video* on a video that is not downloaded is disabled with its reason under it and the row's video button is dimmed, and both come back when the network does. For 3.3: on the sheet of a YouTube episode the control is in the header's right corner with *Audio* selected, a tap on *Audio* does nothing, and a tap on *Video* opens the video screen still playing (180.7 s, 182.8 s, 185.8 s around the tap); on the video screen it is in the same corner with *Video* selected, a tap on *Video* does nothing, and a tap on *Audio* opens the sheet still playing (132.5 s, 134.5 s, 137.7 s). For 3.4: a long press on the minimised video opens a menu above the bar; *Switch to audio* leaves the bar a bar, without the picture or its mark, still playing (127.6 s, 130.6 s, 133.3 s); *Stop playing and hide the player* removes the bar and the session goes to `NONE`; a long press on the audio bar opens no menu. For 3.5: with the page cleared, the first Back brings the controls back and the second minimises, the picture carrying on in the bar. For 3.6, with the emulator's display set to the unfolded Fold's window (`wm size 1764x1660`, `wm density 320`, so 882×830 dp): the video screen is the page, with the picture in its proportions between the names and the transport; *Fill the screen* there gives the overlay and *Leave full screen* the page again. At the phone's own size nothing moved: the page upright, the overlay on its side. For 3.7: the show page's rows draw the video button as before and the episode sheet has the download arrow on both *Download audio* and *Download video*, with the screen glyph on *Play video* only; the minimised video's bar exposes its mark as "Playing as video". |
| 4 — Downloading and managing | **Done**, 2026-10-05: 4.1–4.5 committed on `video-black-picture`; **4.6 dropped** (Boris, 2026-10-05: settings stay untouched). Checked on `Pixel_9a_2`: *Download video* on the show page opens the one picker, nothing picked and *Download* disabled; 360p picked and confirmed downloads, and the sheet's button follows it to "Video downloaded · 360p"; the row then wears the downloaded tick and the video mark. Downloads shows the row with both badges; the *Video* badge opens the video screen playing the 360p file. From the video screen the same picker opens with 360p picked, and *Delete downloaded video* asks "Delete the 360p video? The audio stays on this phone." (cancelled). The Downloads swipe reads *Delete audio and video*, and asks "Delete the audio and video of "…"? This frees 9 MB." (cancelled); the player sheet's download button is spoken "Audio and video downloaded, delete both from device" and asks the same. The picker opens fully expanded (half open, its delete button was below the fold). Not seen on the emulator: a failed download and a video waiting for Wi-Fi (the emulator's network is unmetered); both rest on their tests. |

### Prompt for the next session

Paste this to carry on:

> The audio/video flow plan in docs/reports/2026-10-04-audio-video-flow-plan.md is done on
> branch video-black-picture (4.6 dropped). Read its "Open for Boris" section: what is left is the
> Fold 7 checks listed there, and merging the branch.

Things the next session needs that are not in the steps:

- `feature/search/.../SearchScreen.kt` has Boris's uncommitted edit with two stray backticks (lines
  339 and 345) that stop `:feature:search` compiling, so `assembleDebug` fails with it in place.
  Until he fixes it: copy the file aside, `git checkout` it, build, and copy it back unchanged.
  Never commit it or `.idea/inspectionProfiles/Project_Default.xml`.
- The emulator has no root and `am kill` does nothing while the playback service is in the
  foreground. To kill the process with the activity's saved state kept: Home, then
  `adb shell run-as md.borisveriga.megapodcastplayer kill -9 <pid>`. Always pass
  `-s emulator-5554`: the Fold is usually connected too.
- The emulator's library has a feed show (Podlodka) and a YouTube one, "Uploads from Fireship",
  added from `https://www.youtube.com/playlist?list=UUsBjURrPoezykLs9EqgamOA` through a VIEW
  intent. An `@handle` link is not accepted; a playlist link is.
- The first time the video page is cleared on a fresh emulator, the system lays its own "Viewing
  full screen" hint over the window and takes the Back key until *Got it* is tapped. Dismiss it
  before reading anything into what Back did.
- The emulator now holds one downloaded video: "Did a 50 year old military secret…" at 360p, with
  its audio. Useful for checking Downloads and the delete questions; delete it to see the empty
  states again.
- A wide window can be had on the phone emulator: `adb shell wm size 1764x1660` and
  `wm density 320` make it 882×830 dp, the Fold opened out; `wm size reset` and `wm density reset`
  put it back. The emulator's auto-rotate was turned off for the 3.6 check and left off
  (`settings put system accelerometer_rotation 0`); turn it with `user_rotation 1` / `0`.

### Open for Boris

- **Search is not through the door** (see 3.1 below). Leave it, or open the player as
  `REMEMBERED` when a preview episode is played?
- **Queue rows and moments now open the player when tapped.** The plan asked for it; it has not
  been tried by hand on the phone. Easy to take back for either.
- **The bar's *Switch to audio* leaves the bar a bar** (3.4). It stops the picture and remembers
  audio, and does not open the sheet, where the same words on the video screen do. Opening the
  sheet from the bar would have been its own detour; say if it should.
- **The bar's menu says *Stop playing and hide the player***, the words the close button and the
  bar's spoken action already use, not the plan's shorter *Stop playing*. One wording for one act
  until 3.7 settles the set.
- **A long press is new on the bar**, and only on the minimised video's. Not tried by hand on the
  phone: whether it gets in the way of the pull-down or the swipe up.
- **Full screen on a large window is the overlay, without a turn** (3.6). *Fill the screen* on the
  unfolded Fold now changes the screen's shape as well as asking for landscape; before, on a window
  that size, it could only ask for a turn. Whether the Fold then turns at all is the system's.
- **A delete asks first whenever a video goes with it, and only then** (4.2), on the show page and
  in the player: removing a sound-only download still happens straight from the swipe or the
  button, as before. Downloads asks for every finished episode, as it already did, and now also
  for a transfer that would take a video with it.
- **The Downloads *Video* badge is now a button** (4.3) that plays the episode as video, and wears
  the screen glyph again, since 3.7's rule is that the screen means watching. The row's own tap
  opens the player as last used when its video is on the phone, as sound otherwise. Like the row's
  tap, the badge starts the episode even when the player already holds it (Downloads has no view
  of the player); 3.2's "never start it again" holds on the show page only.
- **The library's YouTube badge still wears the screen glyph** (`SourceBadge` in
  `:core:designsystem`). 3.7 says that glyph means watching only; the badge names where a show
  comes from, and the library is outside this plan, so it was left. A different glyph there changes
  the library's goldens.
- **The bar's menu keeps *Stop playing and hide the player*** (3.7 kept it): it is the close
  button's and the bar's spoken action's wording, and one act has one name.
- **Still to check on the Fold 7:** the video page unfolded, and *Fill the screen* there (seen only
  on a resized emulator); the Audio | Video control in the unfolded sheet's header and in
  landscape over the picture (no golden of either); fold and unfold during a video; the "Could not show the video"
  state on a real failure; Bluetooth play after the app was swiped away; the 2.8 reconnect, which
  the emulator could not provoke.

Where phase 1 ended up differing from the steps below:

- 1.0: the proof is a `VideoViewModelTest` case of the sequence (screen attached, bar attached, bar
  removed), not an activity-recreation test; `:app` has no Hilt test harness for the shell.
- 1.1: the newest view offered wins, not "the screen always wins", so minimising still hands the
  picture to the bar at once. The survivor is given the picture again when the newest leaves.
- 1.6: the refusal snackbar is gone; the sentence is in the frame for as long as it is true. The
  YouTube error text was left alone, since a failed picture falls back to sound and says so there.

Still to check for phase 1, on the Fold 7: fold and unfold during a video; the "Could not show the
video" state on a real failure; offline.

Seen on the way, not part of any phase yet:

- Launched from a share intent, the activity re-reads the intent on recreation, so a rotation
  reopens the search screen with the shared link.
- In full screen on the emulator the transport sits right of the picture's centre; not checked
  whether that is the cutout or the layout.
- `feature/search/.../SearchScreen.kt` has an uncommitted indentation change that fails
  `:feature:search:detekt`; it is not part of any phase.

Where phase 2 ended up differing from the steps below:

- 2.1: `resumePoint()` replaces `resumableQueue()` on `PlaybackQueueSource` rather than sitting
  beside it, and returns a `ResumePoint` (in `:core:media`) whose `keeping` re-resolves the index
  when the service drops an unplayable entry.
- 2.2: a seek within an episode stores where it landed; a seek to another episode (next, previous,
  a tap in the queue, Play on another row) stores where the one being left had got to and does not
  write the one arrived at, so its stored place is not replaced by the zero it starts at.
- 2.3: the service restores in `onCreate` (`restoreIfEmpty` in `QueueRestore.kt`), every time it is
  created with an empty player, not once per process. `PlayerViewModel` no longer asks for anything;
  `EpisodePlayer.restoreQueue` is gone, and `resume()` waits on a new session command,
  `AWAIT_RESTORE`, before pressing play. Not closed: a *Play* on a row that reaches the player
  before the database read returns still wins over the restore, and the stored queue is then
  replaced by that one episode. It was so before as well.
- 2.4: the restore does not call `prepare()`. `PlaybackState.durationMs` falls back to the feed's
  duration (already on the item's metadata) until the player has measured one, so the scrubber is
  drawn and enabled on an unprepared player. The system's session state is `NONE` until Play.
- 2.5: the receiver is declared in `:core:media`'s manifest. The emulator check killed the process
  with `kill -9` and the system restarted it at once (new pid) before the key was sent, so it shows
  a fresh process resuming from a media key, not that the receiver was what started it. Still to do
  on the Fold 7: Bluetooth play after the app was swiped away.
- 2.6: the state is `PlaybackState.isRestoring`, true until the service has answered
  `AWAIT_RESTORE` for the first time; `PlaybackConnection` sends no snapshot before that. The sheet
  shuts on `isEmptied` (idle and not restoring), and `VideoViewModel` holds its ask for the picture
  until the restore is in. The face that comes back is the one the *activity* saved, so this covers
  a process the system took in the background; after a swipe-away or a force-stop there is no saved
  state, and the app opens on the library with the bar, whose tap opens the remembered mode. The
  face is not persisted. Showing the poster without the network needed one more thing: a swap to
  the picture's flavour no longer prepares an idle player unless it stopped on an error.
- 2.7: `playbackSettings` is de-duplicated in `UserPreferencesDataSource`, and the service follows
  the speed apart from the other settings, so a changed skip interval or auto-play switch does not
  put the app's speed back over a show's either.
- 2.8: one loop, `followSession` in `:core:media`: a failed connect is said as disconnected and
  tried again after 1 s, doubling to 30 s, for as long as anything collects the state; a
  controller that is disconnected ends its flow, the state goes back to restoring, and the next
  connect recreates the service, which restores. Before this a failed first connect also ended
  the `callbackFlow` without `awaitClose`. **Not seen on the emulator**: the app's own controller
  keeps the service bound, so neither a failed connect nor a disconnection could be provoked
  (`am stopservice` leaves it up). It rests on `SessionFollowingTest`.

Where phase 3 ended up differing from the steps below:

- 3.1: the door is `openPlayer(episodeId, OpenPlayerAs)` in `MegaPodcastPlayerApp`, and the
  decision behind it is `PlayerViewModel.faceFor`, which replaces `awaitOpensAsVideo`. The screens
  still start the episode themselves and then say how to open the player; the door does not play.
  Queue and Moments, which opened nothing before, now open the player as `REMEMBERED` — a visible
  change: a tap on a queue row or a moment brings the player up. Downloads stays `AUDIO` until 4.3.
  **Search is not through the door**: its preview plays from inside the preview sheet with nothing
  stored, and opening the player over that sheet was not something to decide in passing. A picture
  asked for an episode that turns out to have none now opens the sheet instead of the video screen
  that then left. Queue rows were not tapped on the emulator (nothing was queued); the same path
  was checked through Moments.
- 3.2: the table is `playTransition(control, isLoaded)` in `:core:model`, with `PlayTransitionTest`
  as the parameterised test, and the show page's three controls all go through one `press` in
  `PodcastDetailViewModel`. An episode the player holds is never started again: *Play audio* and
  *Play video* on it send `play()` — so a paused one starts — and change the face. The sheet's
  play button no longer shares the row button's toggle, which also changes a feed episode's
  sheet: *Continue* on the episode playing used to pause it and now opens the player. The row's
  own play button is still its pause. The unstarted video says *Couldn't start the video. Try
  again.*; that wait could not be made to run out on the emulator and rests on its test. Offline
  is `NetworkStatus.observeOnline()`, a flow beside the existing question, read as
  `PodcastDetailUiState.canPlayVideo`. Only the show page has *Play video*, so only it changed.

- 3.3: the control is `ModeSwitch` in `:feature:player`, two text segments on a ground of its own,
  since both of its homes put it over imagery nobody chose. On the sheet it is in the header strip
  (`SheetHeader` in `PlayerSheet.kt`), not in `ExpandedPlayer`, because that corner is the
  header's; `ExpandedPlayer` lost its *Watch* button and its `onWatch`. The header is a small
  custom layout now: at 200 % text the control reaches the grabber, which is then not drawn. The
  selected segment does nothing; the other is spoken as *Switch to video* / *Switch to audio*. The
  *Switch to audio* button in the frame of a picture that cannot be shown is unchanged. Goldens:
  `expanded-player-video` and `mode-switch` are new, `video-screen` and `video-screen-unavailable`
  changed, and the audio-only `expanded-player` ones did not.
- 3.4: the long press opens a `DropdownMenu` anchored on the bar, and the same two things are
  custom accessibility actions on the bar — *Switch to audio* is new there, the stop was already
  one. *Switch to audio* is `becomeAudio` in `MegaPodcastPlayerApp`, the first half of the door's
  answer to `AUDIO`, without the sheet. The audio bar has no long press. The menu itself has no
  golden (it is a popup); `PlayerSheetTest` presses it.
- 3.5: a `BackHandler` in the page shape only, enabled while the page is cleared. Landscape is
  left alone: its controls hide on a timer, so Back there would be spent undoing something nobody
  did, and it minimises as before.

- 3.6: the rule is `videoShowsOverlay(windowHeight, fullscreen)` in `VideoScreen.kt`: the overlay
  when the window is shorter than Material's medium height (480 dp) or full screen was asked for,
  the page otherwise. Height alone, not width against height, so a phone's half of a split screen
  gets the overlay too. The window is read from `LocalWindowInfo`, once, for the route's effects
  and the layout both. Asking for full screen had to become part of the rule: on a window that is
  already large nothing turns, and the button would have done nothing. The page's picture is no
  longer told to fill the width; it takes the largest 16:9 that fits the room, which on a phone is
  the same frame as before (the phone goldens did not change) and on the unfolded Fold at 200 %
  text is narrower than the window. `PortraitVideo` and `LandscapeVideo` are `PageVideo` and
  `OverlayVideo`. New golden: `video-screen-wide`, the second recorded at 882×830 dp. The sheet
  already chose its shape from the measured width, so only the video screen changed.

- 3.7: the verbs are *Play audio* / *Play video* to start, *Switch to audio* / *Switch to video*
  to change, and *Open the video* for the minimised video's tap (it starts and changes nothing,
  so it takes the audio bar's *Open the player* verb); "Show the video" is gone, and
  `video_listen` is `player_switch_to_audio`. The glyphs: the screen (`SmartDisplay`) is on the
  three things that play a picture — *Play video* in the sheet, the row's video button, and the
  minimised bar's mark — and the Downloads badge for a kept video is a film (`OndemandVideo`)
  instead. The episode sheet's *Download video* wore the film and *Download audio* a
  `FileDownload` arrow; both are the `Download` arrow now, the video one in the three states the
  video screen's button already used. One quality label: `formatVideoQuality` in `:core:common`,
  a numeric format like `formatSpeed`, replacing three `%1$dp` resources. The row's video button
  keeps its 40 dp circle inside a 48 dp target. Spoken labels: the bar's mark is "Playing as
  video", and the Downloads badges are "Audio on this phone" / "Video on this phone, 720p".
  Goldens changed: `podcast-detail-youtube` (the button's target) and `downloads` light and dark
  (the badge glyph). The code's own names (`onWatch`, `WatchButton`, `canWatch`) were left.

Where phase 4 ended up differing from the steps below:

- 4.1: the one picker is `VideoDownloadSheet` in `:core:designsystem`, with its own strings, used
  by the episode sheet and the video screen; `VideoDownloadDialog` and `DownloadVideoSheet` are
  gone. Pick then confirm (from the dialog), one status line with a bar while downloading, and a
  question before a finished file is deleted (from the sheet). *Download* and the delete/cancel
  button are stacked, because side by side *Download* broke mid-word at 200 % text. It opens fully
  expanded. `formatVideoQuality` moved from `:core:common` to `:core:model` (`core.model.format`),
  since the design system sees that module and not the other. New golden: `video-download-options`
  (the body; the sheet itself is a window a capture cannot see).
- 4.2: the question is `DeleteDownloadDialog` in `:core:designsystem`, shared by the show page,
  Downloads and the player; `removalTakesVideo(audio, video)` is the rule. It names the video when
  it goes and says how much comes back (sound plus picture). Swipe labels with a video:
  *Delete audio and video downloads* / *Cancel audio and video downloads* on the show page (the
  sheet's existing words), *Delete audio and video* / *Cancel audio and video* in Downloads. The
  player's `DownloadButton` gained `removesVideo`, and `EpisodeDownload` carries the episode's
  video. Deleting only the video stays in the picker.
- 4.3: `VideoDownload.bytes` (Media3's `bytesDownloaded`). The storage total, a row's size and the
  question's "This frees" include finished videos. A row's line adds "Video 42%", "Video waiting
  for Wi-Fi", "Video waiting to download" or "Video download failed" until the video is on the
  phone. The Downloads badges are spoken ("Audio on this phone", "Video on this phone, 720p").
- 4.4: failed downloads read "Download failed · Try again" / "Audio download failed · Try again" /
  "Video download failed · Try again" in the episode sheet, *Try again* on the show page's swipe,
  and "Video download failed; tap to try again" on the video screen's button. A video queued behind
  the Wi-Fi rule says so in the snackbar (show page and video screen), the episode sheet's button
  and the picker's status line.
- 4.5: `EpisodeRow(isVideoDownloaded)` draws a film mark (`DownloadedVideoMark`) beside the tick,
  spoken "Video downloaded"; only the show page passes it. `episode-row` golden changed.

## Context

Three complaints, all in one flow:

1. Starting a video sometimes shows a black picture.
2. Playback does not come back properly after the app was closed.
3. Switching between audio and video, and managing the two kinds of download, is uneven.

Both bugs were reproduced on the `Pixel_9a_2` emulator with the current `main` debug build:

- **Black picture.** Play video in portrait: picture is fine. Rotate: the picture goes black while
  sound and the scrubber carry on, with no spinner and no message. Rotating back keeps it black.
  Only minimise-and-reopen brings it back. The video screen forces sensor rotation when it opens, so
  opening a video with the phone held sideways rotates at once: that is the "sometimes".
- **Wrong episode after a restart.** Playing #493, then Play on #496, kill the app, reopen: the bar
  shows #493 again.

Causes found by reading the code (the first is consistent with the emulator result but not yet
proven by a log; step 1.0 proves it):

- The video screen's `SurfaceView` and the mini bar's `TextureView` are each attached once, when
  created. After an activity recreation the bar is composed for one frame (`onVideo` reads `null` on
  the first frame), takes the player's output, and clears it when it is disposed. Nothing ever
  attaches the `SurfaceView` again.
- The saved queue keeps episodes before the current one, but the current index is not saved; both
  restore paths start at index 0 and then overwrite `last_played_episode_id` with that wrong head.

## Target behaviour (the design in one page)

One player with two faces. Four states: **A** audio bar, **A+** audio sheet, **V** video screen,
**V−** video in the bar.

- **Start** actions say *Play audio* / *Play video*. **Change** actions say *Switch to audio* /
  *Switch to video*. "Watch the video" and "Show the video" are retired.
- The switch is one two-segment control, **Audio | Video**, in the same corner of both faces (sheet
  header and video top bar), shown only when the episode has a picture. It replaces the headphones
  icon on V and the Watch button on A+.
- Switching never pauses, restarts or loses position. Asking for the mode already playing does nothing.
- The picture area is never an unexplained black box. It shows one of: poster + spinner (loading),
  the picture, or poster + a sentence + *Try again* / *Switch to audio* (unavailable).
- Reopening the app shows the episode, position, mode and face it was closed on, paused, without
  touching the network until Play.
- Every delete names what leaves the phone and asks first when a video goes with it.

## Phase 1 — The black picture

1.0 **Prove the cause.** Add a Robolectric test that recreates the activity on `Route.Video` and
    asserts the last surface call on the controller is the screen's `set…`, not the bar's `clear…`.
    It must fail on `main`.
1.1 **One owner for the output.** `VideoViewModel` keeps both registered views and decides which is
    current: the screen wins while it exists, otherwise the bar. When either leaves, the remaining
    one is attached again. Attach and detach run through one ordered queue on the main dispatcher
    (today detach hops to `applicationScope` and lands late).
    Files: `feature/player/.../video/VideoViewModel.kt`, `core/media/.../PlaybackConnection.kt`.
1.2 **The bar never draws a picture while the video route exists.** Derive `onVideo` synchronously
    from the back stack so the first frame after recreation is correct.
    Files: `app/.../ui/MegaPodcastPlayerApp.kt`, `feature/player/.../PlayerSheet.kt`.
1.3 **Shutter follows the first rendered frame.** Add `onRenderedFirstFrame` to the snapshot as
    `PlaybackState.pictureReady`, reset on item swap, quality change and surface change. `VideoFrame`
    and `MiniPicture` use it instead of `videoSize`. The cover is the episode artwork, not black.
1.4 **Enter and exit cannot cross.** Replace the two scopes with one desired state ("picture wanted
    for episode E at quality Q", or "not wanted") that a single collector reconciles. `ENTER_VIDEO`
    carries the episode id and the service refuses it if another item is current.
    Files: `VideoViewModel.kt`, `KeepPictureEffect.kt`, `core/media/.../VideoMode.kt`, `PlaybackService.kt`.
1.5 **Only renditions the phone can decode.** `VideoCandidate` gains the codec; `selectVideoCandidate`
    takes a "can decode" predicate supplied by `:core:media` (so `:core:youtube` stays free of
    `MediaCodecList`). A video track that ends up unselected is treated as a fallback, not silence.
    Files: `core/youtube/.../NewPipeAudioResolver.kt`, `VideoFallbackListener.kt`.
1.6 **Failure is a state, not a spinner.** `VideoFrame` gets Loading / Playing / Unavailable. The
    video screen gets its own snackbar host so player errors show there; the YouTube error text
    gets a video wording. A failed surface attach is retried once and then reported.
1.7 **Rotation is the user's.** Follow the system auto-rotate setting instead of forcing `SENSOR`,
    and add a full-screen button that locks landscape until pressed again.

## Phase 2 — Coming back after the app was closed

2.1 **Restore the episode that was playing.** One `resumePoint()` in `DefaultPlaybackRepository`
    returns queue, index of `last_played_episode_id` (0 if absent) and that episode's position. Both
    `EpisodePlayer.restoreQueue` and `PlaybackService.onPlaybackResumption` use it. Update the test
    that pins `startIndex = 0`. The widget shows the same episode.
2.2 **Save the position on a seek** and on skip while paused (`PlaybackPersistenceListener`,
    `onPositionDiscontinuity`).
2.3 **One restore path.** The service restores when it starts with an empty player; the view model
    only waits for it. Removes the race where the UI restore pauses what a widget, headset or watch
    just started.
2.4 **No network at launch.** Restore sets the items and position without `prepare()`; Play prepares.
    No error snackbar on an offline cold start.
2.5 **Hardware and system resume.** Declare Media3's `MediaButtonReceiver` so a headset, car or the
    system media card can restart playback after process death.
2.6 **Restore the face.** Add a "restoring" state distinct from idle so the sheet is not collapsed
    and `VideoRoute` does not leave or call `enter()` before the episode is loaded. Reopening on the
    video screen shows the poster, paused, at the saved position.
2.7 **Speed survives.** `distinctUntilChanged` on `playbackSettings` so a per-show speed is not reset
    by every unrelated preference write.
2.8 **Retry and reconnect.** Restore is retried when the first connect fails; `PlaybackConnection`
    rebuilds its controller and re-emits state after a disconnect.

## Phase 3 — Switching modes

3.1 **One door into the player.** A single shell function `openPlayer(episodeId, as: Audio | Video |
    Remembered)` replaces the scattered `openAudio` / `openVideo` calls. Library, Podcast, Downloads,
    Queue, Moments, Search and the notification all use it.
3.2 **Fix the asymmetric cases.**
    - *Play audio* on the episode playing as video switches to audio (today it pauses).
    - *Play video* on the episode playing as audio switches in place (today it reloads).
    - A *Play video* that cannot start within the wait says so instead of doing nothing.
    - Offline with no downloaded video: *Play video* is disabled with a reason.
3.3 **The Audio | Video control** described above, as one composable in `:feature:player`, used by
    `ExpandedPlayer` and `VideoScreen`.
3.4 **From the bar (V−).** Tap still opens the video. Long-press, and a TalkBack action, offers
    *Switch to audio* and *Stop playing*, so neither needs a detour through the video screen.
3.5 **Back and clear.** With the page cleared, Back brings the controls back first; a second Back
    minimises.
3.6 **Fold and tablets.** Choose the portrait or overlay layout from the window size, not from
    `Configuration.orientation`, so the unfolded Fold gets the page layout with a larger picture.
3.7 **Words and icons.** One string set for the verbs above; `SmartDisplay` means watch only,
    `Download` means download only; one shared quality label; 48 dp target for the row's video button;
    spoken labels for the bar's video mark and the badges. Wording per `docs/COPY_RULES.md`.

## Phase 4 — Downloading and managing

4.1 **One video-download picker.** Merge `VideoDownloadDialog` (podcast) and `DownloadVideoSheet`
    (player) into one sheet: pick a quality, confirm, same progress line, same delete confirmation.
4.2 **Honest deletes.** Row swipe, the player's download button, the Downloads swipe and its dialog
    say "audio and video" when both go, and ask first. Deleting only the video stays possible.
4.3 **Downloads screen tells the whole story.** `VideoDownload` gains a byte count (Media3 already
    has it). Rows show video progress and failure; sizes, the storage card and "This frees X" include
    video. Tapping a row with a downloaded video opens it per 3.1 (`Remembered`), and the video badge
    is a *Play video* button.
4.4 **Failures are named.** A failed audio or video download reads "Download failed · Try again"
    rather than falling back to "Download". Video gets the "waiting for Wi-Fi" message audio has.
4.5 **Rows show video on the phone.** A second mark beside the downloaded tick in `EpisodeRow`.
4.6 **Settings, small.** A *Video* group: default quality, and "Open episodes with video as: last
    used / audio / video". Optional; drop it if settings should stay untouched.

## Order and size

| Phase | Why this order | Rough size |
|---|---|---|
| 1 | The picture has to be trustworthy before the UI around it changes | medium |
| 2 | Independent of 1; 2.1 alone fixes the reproduced bug | medium |
| 3 | Builds on the single output owner (1.1) and the restoring state (2.6) | medium |
| 4 | Mostly UI and copy; touches the download model once (4.3) | medium |

Each phase is one branch and one merge. 1.0–1.2 and 2.1 are the two smallest changes with the
largest effect and can go first on their own.

## Constraints kept

- `youtube://video/<id>` and `youtube://video-only/<id>?h=<height>` spellings are untouched.
- New persisted fields (if any) rely on the destructive fallback; no migration.
- `suspendRunCatching` plus `CrashReporter` for every swallowed failure; strings in `strings.xml`;
  KDoc and unit tests on everything changed; no `TODO`, no `!!`.
- Nothing crosses to the watch; `:core:model` stays pure JVM.

## Verification

- **Unit / Robolectric**, added with each step: activity recreation on the video screen (1.0),
  output ownership hand-offs (1.1), enter/exit ordering (1.4), codec selection (1.5),
  `resumePoint()` with items before the current one (2.1), seek persistence (2.2), the transition
  table of 3.1–3.2 as one parameterised test, download byte totals (4.3).
- **Goldens** (new): video frame loading and unavailable, the Audio | Video control on both faces,
  the merged download sheet, Downloads rows with video progress and failure, the unfolded layout.
  Record with the flag through Bash, then open the images.
- **Checks**: `detekt`, `testDebugUnitTest test`, `lintDebug`.
- **Emulator**, the two reproductions above must now pass: rotate on the video screen four times and
  the picture stays; play A, play B, kill, reopen and B is in the bar at its position. Also: open a
  video holding the device sideways; minimise and reopen; home and back; airplane mode then
  *Play video*; kill on the video screen and reopen.
- **Fold 7**: fold and unfold during video, Bluetooth play after the app was swiped away.

## Out of scope

Picture-in-picture, casting, video on the watch, moments or chapters on the video screen, the
settings export, and anything outside this flow.
