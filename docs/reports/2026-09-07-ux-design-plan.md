# MegaPodcastPlayer — UX and design improvement plan

*7 September 2026. A code-level design review of every phone screen, the shared design system in
`:core:designsystem`, and the watch surfaces. Each item carries an impact estimate (H/M/L, to the
person using the app) and an effort estimate (S: under half a day, M: one to two days, L: three days
or more).*

> **Implementation status.** The plan is being worked through on the `ux-design-plan` branch. An
> id marked **✅** is implemented and covered by tests; **◑** is partly done, with what is left
> stated in the row; an unmarked id has not been started. §4's roadmap carries the same marks per
> phase. Nothing here is merged until it has been tried on the Fold 7 and the Watch Ultra 2.
>
> **Where it stands: 45 of the 73 items are done, none is half done, 28 are not started.**
>
> | Phase | State | Left |
> |-------|-------|------|
> | P0 — a day of polish | ✅ complete | — |
> | P1 — the listening loop | ✅ complete | — |
> | P2 — control and personalisation | ✅ complete | — |
> | P3 — reach | started | 9 of its 13 items |
> | Unscheduled (in no phase) | not started | 19 items |
>
> §4.1 lists the 28 by name and §4.2 says which to take first. The one half-finished item is finished: PL-2's chapters now reach
> the notification, the lock screen and everything else that presses *next* through a media
> session. P3 has begun with the three smallest things in it — W-1, W-2 and DS-3 — and with
> NAV-2, which was a question rather than a task and is answered below.
> P2's own items, in the order they landed: PL-5, PL-6, PL-8, SHOW-5, SHOW-6, MOM-1, then
> LIB-1, LIB-2, LIB-3, DL-1, DL-2, SET-1, SET-3, ADD-2 and COPY-1. Three items from other phases
> were finished by work done for these: DS-9 by LIB-1 (which needed the second sort control the row
> was waiting for), DS-1 by PL-1 and PL-6, and A11Y-4 by SHOW-3. The branch leaves `detekt`,
> `lintDebug` and both test tasks green, checked after each item.
>
> Two things P2's last items are worth knowing about before the code is read. **The preview a
> search result now opens stores nothing** — not the show, not its episodes, not a refresh
> timestamp — and the ids it carries are the ones a subscription would write, so an episode played
> from the sheet and the same episode played after subscribing are one episode as far as the player
> and the download cache are concerned. And **"Download now" on a row waiting for Wi-Fi is wider
> than the row it is asked from**: Media3 enforces one network requirement for the whole download
> manager and has no per-download equivalent, so everything else waiting starts too. The snackbar
> says so rather than implying one episode was singled out, and the stored rule comes back as soon
> as nothing is left downloading.
>
> One thing worth knowing before the PL-2 code is read: the session is no longer built on the
> `ExoPlayer` but on a `ChapterAwarePlayer` wrapping it, and that choice is what makes one rule
> reach every surface. A notification button, a headset, a car and the Assistant all arrive as
> `COMMAND_SEEK_TO_NEXT` on whatever player the session was given, so the meaning of *next* belongs
> to the player rather than to any of them. It is a `ForwardingSimpleBasePlayer` and not a
> `ForwardingPlayer` because the button has to be *offered* as well as obeyed: a chaptered episode
> alone in the queue is one the real player says has nothing after it, and only the state-rebuilding
> base class can add the command and emit the event that says so.
>
> One thing to look at before merging: `gradle/verification-metadata.xml` was regenerated, because
> the new `:feature:listen` module resolves two artifacts nothing else did
> (`androidx.collection:collection-jvm`, `androidx.profileinstaller`). The diff also carries the
> Firebase and Crashlytics entries that the in-flight crash-reporting work needed and had not
> regenerated yet. `docs/DEPENDENCY_VERIFICATION.md` asks for a human to read that diff, and this
> is the note saying it has not been read by one.
>
> One decision worth knowing about before reading the SHOW-5/SHOW-6 code: per-show settings are
> stored in DataStore as one JSON map, not as a `show_settings` table. The database here has no
> migrations and a version bump wipes the library, and `MegaPodcastPlayerDatabase`'s own KDoc asks
> that anything needing the schema wait for a deliberate bump. A remembered sort order is not worth
> the user's library, and these values are preferences in the ordinary sense. A blob this build
> cannot read decodes to an empty map, which puts every show back to its defaults rather than
> throwing out of a flow the show page is not prepared to catch.

---

## 1. Headline assessment

**What is already strong, and should be protected.** The app has a real brand rather than a
theme: a citron-on-ink palette with every Material role defined and contrast asserted by a test,
Bricolage Grotesque for headlines and Inter for text, tabular figures for anything that ticks, and
named spacing, elevation and motion tokens. The Expressive components (morphing play button,
cookie-to-clover loader, wave scrubber, wavy refresh hairline) are built on stable APIs rather than
alphas. The interaction model is thought through and consistent: a two-tier swipe on every list,
long-press reorder everywhere, every gesture mirrored by a named accessibility action, one merged
TalkBack node per row, confirmations that count what is at stake, undo for anything reversible. The
player is one continuous sheet with predictive back and a single piece of travelling artwork. Every
string is a resource, plural-aware, and written in one voice.

**Where it falls short, in one paragraph.** The signature components are not on the screen the
brand was designed around: the player uses a stock `Slider` and `FilledIconButton`, and the wave
scrubber, morphing play button and artwork backdrop go unused. The app has no "what should I
listen to now" surface; the library is an inventory of shows, so every session starts with
show → scroll → tap. An episode can only be played or queued, never *read*: show notes are parsed
and stored but displayed nowhere, chapters are parsed and stored but displayed nowhere, and nothing
in a list can be marked played. The show page hides download state entirely. And a handful of
first-impression details (a white flash on launch in dark mode, the mini player clipping at large
font sizes, a skip glyph that says 30 whatever the setting) undercut the polish everywhere else.

---

## 2. Scope and method

- **Reviewed:** the theme and every component in `:core:designsystem`; the app shell and
  navigation; all seven feature screens and their `strings.xml`; the manifest and launch theme; the
  formatters in `:core:common`; the watch screen, tile, complication and their strings.
- **Not done:** no device session and no usability testing. Findings come from reading the UI code
  and the rationale in its comments, with one exception: the media notification (SYS-1) was
  confirmed from screenshots of the shade and lock screen. The other visual P0 items in §4 should
  be confirmed on the Fold 7 before they are scheduled.
- **Out of scope by request:** code quality and refactoring. Where an item needs a new component,
  that is noted as effort, not proposed as a refactor.

---

## 3. Findings

### 3.1 Design system: the vocabulary exists, the screens do not speak it

| Id | Finding | Impact | Effort |
|----|---------|--------|--------|
| DS-1 ✅ | **The player uses stock controls instead of the signature ones.** The expanded player draws a Material `Slider`, a `FilledIconButton` and a `CircularProgressIndicator` for buffering; the collapsed bar uses a plain `IconButton`. `WaveScrubber`, `LabelledWaveScrubber`, `PlayPauseButton` (all three sizes, including `Small` documented as "inside a list row") and `PlayPauseSize` are used by nothing. The scrubber's designed remaining-time label (`-30:06`) is likewise absent; the player shows total duration. **Done with PL-1 and PL-6:** the expanded player draws `LabelledWaveScrubber` and a `Hero` `PlayPauseButton` over an `ArtworkBackdrop`, the collapsed bar a `Medium` one, and the show page a `Small` one — all three sizes are in use, and the countdown label is on the scrubber. | H | S |
| DS-2 ✅ | **`ArtworkBackdrop` is documented as sitting behind the expanded player and does not.** The sheet is a flat `surfaceContainerHigh`. Add the blurred backdrop with its scrim; keep the transport row on a solid strip at the bottom so contrast never depends on the cover. | M | S |
| DS-3 ✅ | **Dead vocabulary.** `MegaPodcastPlayerPolygons.Heptagon` ("masks the artwork of the episode currently playing") is applied nowhere; `SelectionToolbar` and `EpisodeRow.isSelected` survive from a multi-select that no screen has any more. Either give each a job (the heptagon on the queue's now-playing artwork is a good one) or remove it, so the design system stays a description of the app. **All three removed, rather than employed.** The heptagon's suggested job was the queue's now-playing artwork, and by the time it could have taken it that row already said *this one is playing* three ways — a tint, an equaliser and a heading — against §7's rule of one mark per fact. The other two are half of a feature the app decided against; the comment left where the heptagon was says so, so the next reader does not re-derive it. `EpisodeRow.onLongClick` is the one limb of that amputation still standing, unused by any screen and left alone because it is a labelled accessibility action rather than a mark on the screen. | L | S |
| DS-4 ✅ | **Token leakage in the player module.** The collapsed bar hard-codes 12/8 dp padding and the expanded body uses 20 dp horizontal padding against the tokenised 16 dp `screenHorizontal` every other screen uses. The player is the one screen whose edges visibly disagree with the rest. | L | S |
| DS-5 | **Previews and screenshots.** `ThemePreviews` and `FontScalePreviews` exist in the design system, but every screen carries a single bare `@Preview` with no dark or large-font variant, and there is no screenshot test suite. Design regressions are invisible in CI; the palette-only-rendered-in-previews incident the theme's KDoc describes could recur. | M | M |
| DS-6 ✅ | **First frame.** The launch theme's `windowBackground` is `@android:color/background_light` in both modes, so dark-mode launches flash white; `core-splashscreen` is a declared dependency but `installSplashScreen()` is never called and no `Theme.….Starting` exists. Define a splash theme on the ink ground with the icon, install it, and give the window background a night qualifier. | H | S |
| DS-7 ✅ | **Motion ignores the system's "remove animations" setting** — the now-playing bars, wavy hairline, morphing loader, scrubber wave and the watch waveform all loop unconditionally — and **the phone has no haptics at all**: no tick when a long press picks up a row, when a full swipe crosses its commit threshold, when the sheet snaps, or when a moment is saved. Both are cheap and both are the difference between "animated" and "physical". | M | S |
| DS-8 | **RTL.** `supportsRtl` is true, but `SwipeActionsRow` only opens leftwards and anchors its buttons at `CenterEnd`, so a mirrored layout puts the buttons on the left and the gesture pulling away from them. Mirror the gesture with `LayoutDirection`, or declare `supportsRtl="false"` honestly. | L | M |
| DS-9 ✅ | **Components the roadmap will need and the system does not have:** a bottom-sheet container with a standard header (speed picker, episode details, show settings), a horizontal *shelf* of episode cards (for a Home surface), a sort/segmented control, and a chip row with persisted selection. Build them in the design system as the P1 items land rather than inline in features. **Done:** `MegaPodcastPlayerBottomSheet` (with the standard header), `EpisodeShelf`/`EpisodeCard` for the Listen tab, and — with SHOW-5 — the show page's persisted chip row and its order toggle. **Done with LIB-1:** the shared sort control, as `SortToggleChip` (two orders, flipped) and `SortMenuChip` (more than two, chosen from) in one file — one control with two arities. The show page's toggle is now the first of those, and the library's four-way menu the second. | M | M |

### 3.2 Navigation and information architecture

| Id | Finding | Impact | Effort |
|----|---------|--------|--------|
| NAV-1 ✅ | **There is no "now" surface.** The four tabs are Library, Queue, Downloads, Moments. Finding something to play is Library → show → scroll → tap, and the two things most sessions actually want — *continue what I was listening to* and *what arrived since yesterday* — have no home. The data is already there (`Episode.isInProgress`, `isNew`, the durable queue). Proposal: a **Home** tab (or shelves at the top of Library) with *Continue listening*, *New episodes* across all shows, and *Up next*. This is the single highest-leverage change in the plan. Shipped as a `Listen` tab and the start destination (decision D-2), in a new `:feature:listen` module, with `EpisodeShelf`/`EpisodeCard` added to the design system (part of DS-9). | H | L |
| NAV-2 ✅ | **Downloads as a top-level tab** is a storage-management view promoted to a primary destination. Once Home exists, decide whether Downloads stays a tab, becomes a filter chip on Home, or lives under Settings › Storage with the card it already has. **Decided: it stays a tab**, and the bar keeps five. The objection was a taxonomic one — a storage view among four listening destinations — and the answer is that on this phone, with this library, what is downloaded *is* a listening destination: it is the list you read before a flight or a tunnel, and the one place a copy can be started, paused or thrown away. A screen that answers "what can I play with no signal" has earned a tab; the tidier arrangement would have cost a tap on the day the tap is hardest to make. No code change. | M | S |
| NAV-3 | **The open Fold is a phone with a rail.** `NavigationSuiteScaffold` swaps the bar for a rail, but Library → show is still a full-screen push, and `PodcastDetailRoute` already has a `showBackButton` parameter written for a two-pane layout nobody calls. A `ListDetailPaneScaffold` for Library/Show (and later Queue/Player) is what the inner display is for. | M | L |
| NAV-4 | **Re-tapping the current tab does nothing** (the guard returns early). Platform convention is scroll-to-top; with a long library it is the fastest way back. | L | S |
| NAV-5 | **Settings is reachable only from the Library bar.** From Queue, Downloads or Moments it is a tab switch plus a tap. Put the gear on every top-level bar, or in a consistent overflow. | L | S |
| NAV-6 ✅ | **Notification taps land short.** The new-episode notification opens the *show*; opening the episode (or Home with it highlighted) is what the tap means. The playback notification opens the app with the sheet collapsed; it should arrive expanded. The playback notification now carries `EXTRA_OPEN_PLAYER` and arrives with the sheet expanded; a new-episode notification that named exactly one episode opens that episode's sheet, and one naming several still opens the show. | M | S |
| NAV-7 | **Four tabs, two app-bar styles.** Library, Queue and Downloads argue in their comments for a pinned small bar; Moments uses the large collapsing one. Pick one for all top-level destinations. | L | S |
| NAV-8 | The Library empty state uses the `GridView` glyph (a *layout* icon) for "no podcasts yet"; use the `Podcasts` glyph the artwork placeholder already uses. | L | S |

### 3.3 Library

| Id | Finding | Impact | Effort |
|----|---------|--------|--------|
| LIB-1 ✅ | **Manual order is the only order.** Offer *Recently updated*, *A–Z* and *Most unplayed* alongside *Manual*, persisted like the layout toggle. Drag stays available in Manual. **Done:** `LibrarySort` in `:core:model` with the ordering rule beside it (nulls last for a feed that dates nothing, every computed order broken on title so the list cannot reshuffle itself between emissions), stored like the layout, drawn with the design system's new `SortMenuChip`. Dragging withdraws for every order but *My order* — and for a narrowed list too, since a drag there would be read as positions in the whole library. | M | M |
| LIB-2 ✅ | **No way to narrow a large library.** A filter field, or a *Has new episodes* chip, for libraries past a screenful. **Done:** both, and only past a screenful — eight shows. The field matches the author as well as the title, because half the shows in a library are remembered by who makes them. Not stored, deliberately: a library that opened showing four of its shows because of a chip tapped last week would look like data loss. | M | S |
| LIB-3 ✅ | **Two meanings of one badge.** The library badge counts `isNew` (arrived since last seen); the show page's *Unplayed* filter means *never started*. Same colour, different facts. Decide the vocabulary once — *New* = arrived since you last looked, *Unplayed* = never started — and show both consistently. **Done:** the badge stays *new* and says so, the counts line under a row gained *N unplayed* beside the episode and download counts, and the DAO computes that number with `EpisodeFilter.UNPLAYED`'s own rule in SQL so the number the library sorts by and the list the show page's chip produces are the same episodes. The one shared colour now has one meaning written down — *there is something here you have not heard* — and the words carry the difference. | M | S |
| LIB-4 | The grid has no way to remove a show (long press is reorder); only the list's swipe and the show page's overflow do. Acceptable, but say so in the Remove dialog copy, or add the show page's overflow to the tile via a context menu that opens if the press is released without moving. | L | S |

### 3.4 Show page

| Id | Finding | Impact | Effort |
|----|---------|--------|--------|
| SHOW-1 ✅ | **An episode cannot be read.** Tapping a row plays it and expands the player; there is no episode detail anywhere. `Episode.description` (show notes, often HTML with links and timestamps) is stored and never displayed. Add an **episode sheet**: artwork, title, date and duration, rendered show notes, chapter list (PL-2), and actions — Play, Play next, Add to queue, Download, Mark played, Share. See decision D-1 in §5 for what the row's tap should do. | H | M |
| SHOW-2 ✅ | **Download state is invisible on the show page.** `EpisodeRow` is called without `isDownloaded`, and a running download shows no ring. The only ways to know are the *Downloaded* filter chip or swiping to see what the backdrop says. Pass `isDownloaded`, and show the `DownloadButton` ring (or `DownloadedMark`) while a transfer runs. | H | S |
| SHOW-3 ✅ | **Nothing in any list can be marked played or unplayed.** Only the player can (`markCurrentPlayed`). Add *Mark played* to the short-swipe tier beside *Play next* and to the episode sheet; it is the second most common list action in a podcast app after play. | H | S |
| SHOW-4 ✅ | **"Play latest" ignores progress and the filter.** If an episode of the show is in progress the button should read *Continue · 12 min left*; if the newest is already played it should not replay it silently. | M | S |
| SHOW-5 ✅ | **Newest-first is the only order, and the filter forgets itself.** Serialised shows want oldest-first; the filter chips reset to *All* on every visit. Add a sort toggle and persist both per show. Both are now `ShowSettings`, stored per show. The order toggle is labelled with the order that is *on*, so the row reads as a description of the list under it, and it is offered only for a show that is not arranged by hand — a YouTube playlist is dragged into shape, and reversing it would leave the drag computing positions against an order nobody can see. `EpisodeFilter` moved to `:core:model` to make this possible: the rule is a fact about episodes, the chip caption is a fact about the screen. | M | M |
| SHOW-6 ✅ | **No per-show settings.** The overflow holds *Delete and reload* and *Delete* only. Auto-download on/off, default playback speed, new-episode notifications, and skip-intro seconds are all per-show decisions in practice. Add *Show settings* as a sheet. All four shipped, and all four are wired rather than stored: auto-download and speed have the app-wide value as a named third option ("App speed (1.2×)"), because an override is only a decision if the thing being overridden is visible. The speed is applied by `ShowSpeedApplier`, an application-scoped collector of what the player has loaded — the queue advancing on its own is the transition the setting exists to survive, and it happens with no screen open. Skipping the intro applies to an episode that has never been started and never to one in progress. Muting a show filters that show out of the notification rather than suppressing the run. | M | M |
| SHOW-7 | **Header details.** *Show more* renders even when the description fits in four lines (use `onTextLayout` overflow to hide it); there is no website or feed link and no *Share show*; the counts line the library row has (episodes, downloaded) is missing from the page about the show. | L | S |

### 3.5 Player

| Id | Finding | Impact | Effort |
|----|---------|--------|--------|
| PL-1 ✅ | Adopt `LabelledWaveScrubber`, `PlayPauseButton` (Hero in the sheet, Medium in the bar) and `ArtworkBackdrop` (DS-1, DS-2). Buffering then stops replacing the glyph with a spinner; the morph carries it. | H | S |
| PL-2 ✅ | **Chapters exist and are shown nowhere.** `:core:model` parses four chapter sources (remote JSON, ID3, inline PSC, description timestamps), stores them on the episode, and ships navigation helpers (`indexOfCurrent`, `nextStartAfter`, `previousStartBefore`); no screen, notification or watch surface uses any of it. Add chapter markers on the scrubber, the current chapter's title under the episode title, previous/next-chapter buttons, a chapter list in the episode sheet, and chapter artwork when a chapter carries it. Wire the notification's previous/next to chapters when an episode has them. **Done:** a `ChapterResolver` in `:core:data` that reads the stored `psc:chapters` list, fetches and caches the publisher's `podcast:chapters` document, and falls back to timestamps in the description; chapter markers on the scrubber; the current chapter's title under the episode title; the outer transport buttons moving between chapters when an episode has them; the chapter list on the episode sheet; chapter artwork on the player. **Also done:** the notification's previous/next, by way of `ChapterAwarePlayer` — the session is built on the `ExoPlayer` wrapped in one, so a chaptered episode makes `COMMAND_SEEK_TO_NEXT` mean the next chapter for every surface that issues it at once: the shade, the lock screen, a headset, a car, the Assistant. ID3 `CHAP` frames are still not read, and that is now written down in `ChapterResolver`'s KDoc as a decision rather than an omission. | H | M–L |
| PL-3 | Show notes in the player itself, below the controls or behind a *Notes* segment, so a link mentioned mid-episode is one scroll away. | M | M |
| PL-4 ✅ | **Sleep timer.** The end-of-episode bell is a good idea for the falling-asleep case but it is the only case covered. Add a timer (15/30/45/60 minutes, end of episode, end of chapter) with fade-out and shake-to-extend; the bell becomes one option of it. | H | M |
| PL-5 ✅ | **Speed is a cycle button.** Eight steps that wrap from 3× back to 0.8× make the fastest setting a trap. Tap should open a sheet: a 0.5–3.0 slider in fine steps, the presets as chips, and a per-show override (SHOW-6). Trim-silence and volume-boost toggles belong there later. Shipped as `SpeedSheet`: the presets as chips, a continuous 0.5–3.0 slider snapped to 0.05, and −/+ buttons for the same search by someone who cannot hit a 0.05 target. Dragging is *heard* — the rate reaches the player on every change and the preference is written once, on release — because choosing a speed is done by ear. The per-show override lives in SHOW-6's sheet rather than here; the player's speed button carries D-4's dot when the rate in force is the show's. Trim-silence and volume-boost are still later. | M | M |
| PL-6 ✅ | **Time labels.** Tap the right-hand label to toggle total ↔ remaining, and show *ends at 22:41* at the current speed; the tabular numeric style is already there for it. Shipped as a three-state cycle on the right-hand label: time left, total length, and the clock time it ends at — computed at the current speed, which is the part that is easy to get wrong. Not persisted: it is a glance, not a setting. | M | S |
| PL-7 ✅ | **The mini player.** (a) Its skip button is a fixed `Forward30` glyph while the setting allows 10/15/30/45/60 s, so it lies for four of five values; use the same `skipForwardIcon` the expanded player does. (b) It has no skip-back. (c) It cannot be dismissed — a swipe down or a swipe away to stop playback is the convention. (d) Its height is a fixed 64 dp holding two text lines: at 150 % font scale the lines alone outgrow it and clip. Either size it from content, or drop the show line at large scales. Verify on device. | H | S |
| PL-8 ✅ | **Playback errors are raw.** *Playback problem: <exception message>*. Map the common cases (no connection, file gone, unsupported format, YouTube extraction failed) to sentences that say what to do. A `PlaybackError` enum in `:core:media`, classified from Media3's error *codes* rather than its messages, with YouTube checked first because its URLs expire and fail through the same codes as a dead link. The unknown case keeps the player's own words. | M | S |
| PL-9 ✅ | **Queue screen context.** By design it lists only *up next*, so nothing on it says what is playing. A slim *Now playing* header (artwork, title, position) anchors the list; add total remaining time in the bar's subtitle, *Clear queue*, and an *Add episodes* action on the empty state that leads somewhere. | M | S |
| PL-10 | The *Up next* link vanishes when the queue is empty, so the player never mentions the queue exists. Show *Up next · nothing queued* with a way to add. | L | S |
| PL-11 | **The expanded player on the inner display.** Hero artwork is 72 % of width, which on the open Fold is a very large square with the controls pushed to the bottom edge. Cap the hero by the smaller of width and height, and at the expanded width class lay artwork and controls side by side. | M | M |

### 3.6 Downloads

| Id | Finding | Impact | Effort |
|----|---------|--------|--------|
| DL-1 ✅ | **One mixed list.** Failures, transfers in progress, episodes waiting for Wi-Fi and finished episodes share one order that is "whatever the download stack was doing". Section it (*Downloading*, *Waiting*, *Failed*, *Ready*) with the problems at the top; manual reorder applies to *Ready*. **Done:** *Failed*, *Downloading*, *Waiting*, *Ready*, in that order, grouped by `groupIntoSections` in `:core:model`; a single section draws no heading, since it would name the only thing on screen. The drag is offered in *Ready* alone and reports positions among those rows. | M | M |
| DL-2 ✅ | A row *Waiting for Wi-Fi* should offer *Download now anyway* on the short swipe. **Done**, with one caveat the copy carries: Media3's network requirement is per *manager*, not per download, so anything else waiting starts too. The stored "Wi-Fi only" rule is untouched and is re-applied as soon as nothing is left downloading. | M | S |
| DL-3 | The storage card could carry one more line — the keep-limit and delete-after-playing status, linking to Settings — so the answer to "why did that episode disappear" is on the screen it disappeared from. | L | S |

### 3.7 Adding shows

| Id | Finding | Impact | Effort |
|----|---------|--------|--------|
| ADD-1 ✅ | **No share target or link handling.** The manifest has only `MAIN/LAUNCHER`. A podcast link in a browser or a chat has to be copied, the app opened, the add button tapped, and the link pasted. Handle `ACTION_SEND` (text/plain) and `ACTION_VIEW` for feed, Apple Podcasts and YouTube playlist URLs, landing on Search with the link card already filled. | H | S |
| ADD-2 ✅ | **Subscribe is the only way to look.** Tapping a result subscribes immediately. Add a preview sheet (artwork, description, the latest few episodes, a *Subscribe* button) that also lets one episode be played without subscribing. **Done:** `PodcastRepository.preview` fetches and parses the feed and stores none of it, and `EpisodePlayer.playUnsubscribed` plays one episode of a show that has no rows — nothing queued, no position kept. The sheet names the show from the search result while the feed is still in flight, so it never opens blank. | M | M |
| ADD-3 | **OPML import and export.** The JSON backup is private to this app; OPML is how a library moves in from, or out to, anything else. | M | M |
| ADD-4 | **Clipboard hint.** When Search opens and the clipboard holds a URL, show a *Paste link* chip under the field. Reading on user focus is permitted; Android's own toast is the disclosure. | L | S |
| ADD-5 | **Discovery when the field is empty:** recent searches and Apple's top charts by genre. Low priority for a personal build. | L | M |

### 3.8 Moments

| Id | Finding | Impact | Effort |
|----|---------|--------|--------|
| MOM-1 ✅ | The player shows only a *count* of this episode's moments. Tapping the count should list them, each tappable to jump. The count is now a control: it opens a sheet listing this episode's moments, earliest first, each one a tap that seeks there. Read-only — editing and deleting stay on the Moments screen. | M | S |
| MOM-2 | The Moments screen: filter by show, search notes, a group-by-show toggle, and swipe-to-edit the note (it is currently three taps via the overflow). | L | M |
| MOM-3 ✅ | The bookmark glyph in the player is `BookmarkAdd`; on the watch, the button is labelled *Moment*. Use one word — *Save moment* — in both places. | L | S |

### 3.9 Settings

| Id | Finding | Impact | Effort |
|----|---------|--------|--------|
| SET-1 ✅ | **No Appearance section.** Theme (system/light/dark), an opt-in for dynamic colour (the no-dynamic-colour default is right and should stay), and a pure-black option for the Fold's OLED. **Done:** all three, stored beside the library layout in `UiPreferencesRepository` and applied above the navigation graph. The splash is held for the length of the read rather than flashing the wrong palette at anyone who chose one. Pure black takes `background` and `surface` and leaves the surface *containers* alone, or every card would dissolve into the page it sits on. | M | S |
| SET-2 ✅ | **"Remove all downloads" has no confirmation.** It is the only destructive action in the app that fires on a single tap; every other one counts what is at stake first. | M | S |
| SET-3 ✅ | **Notifications.** A row linking to the system channel settings, and a per-show toggle for new-episode notifications (SHOW-6). **Done:** the per-show half arrived with SHOW-6; the row now opens Android's own notification page for the app, which lists all four channels. A device with no such screen is handled by doing nothing — nothing was lost, and there is nothing to say about it. | M | S |
| SET-4 | **About.** Version, font licences (`docs/FONT_LICENSES.md` exists), and whether crash reporting is on — an app with Firebase on its classpath should say so somewhere the user can read. | L | S |
| SET-5 | The notification permission is asked for on launch with no context. A one-line pre-prompt on first launch (*We'll tell you when a show publishes, and keep the player controls on your lock screen*) turns a blind system dialog into a choice. | L | S |
| SET-6 | Once PL-5 lands, the speed chips here become *default speed* and a list of per-show overrides; the skip chips stay. | L | S |

### 3.10 System surfaces

| Id | Finding | Impact | Effort |
|----|---------|--------|--------|
| SYS-1 ✅ | **Media notification buttons — confirmed on device.** Screenshots of the One UI shade and lock screen (7 Sept 2026, Podlodka #492 loaded, paused at 0:01) show exactly two controls: *previous episode* and *play*. No skip back, no skip forward, and no *next* because the queue holds one episode, so the row is a lone ⏮ with an empty slot beside it. This is Media3's default provider with no button preferences: it draws previous / play-pause / next from whichever session commands are available, and the app never asks for the seek-back and seek-forward buttons even though the player is configured with skip increments. The lock screen is the surface a podcast is controlled from most — walking, driving, half asleep — and it currently cannot skip an ad or replay a sentence. See the target layout below. | H | S |

**Target notification layout (SYS-1).**

- *Compact (shade collapsed, lock screen, Now Bar):* skip back · play/pause · skip forward, in the
  back/forward slots, with the numbered glyph matching the configured interval (Media3 ships
  5/10/15/30 variants; anything else falls back to the plain glyph, the same rule the app's player
  uses).
- *Expanded:* the same three plus previous episode and next episode; next is omitted, not greyed,
  when nothing follows, so the row is symmetric either way.
- *When the episode has chapters (PL-2):* the previous/next pair becomes previous/next chapter, and
  the chapter title takes the subtitle line under the episode title.
- *Optional custom action:* *Save moment* as a session command button in the expanded view. It is
  the one app-specific action worth doing without unlocking, and it is what the watch's
  full-width button already exists for.
- *Unchanged:* artwork, the system's progress bar and seek, the output switcher and the title
  marquee are all the platform's and already correct in the screenshots.
| SYS-2 | **A home-screen widget** (Glance): now playing with transport, and a *Continue listening* row. | M | M |
| SYS-3 | **Static app shortcuts:** Resume, Queue, Downloads, Add a show. | L | S |
| SYS-4 | Android Auto needs a media browser tree; noted as a possible later phase, not planned here. | — | — |

### 3.11 Accessibility and inclusive design

The baseline is unusually good: merged row nodes with state descriptions, custom actions for every
gesture, live regions on loading and selection, heading semantics, contrast tested. The gaps:

| Id | Finding | Impact | Effort |
|----|---------|--------|--------|
| A11Y-1 ✅ | **Font scale.** The mini player's fixed height (PL-7d) is the confirmed break; the sheet's fixed 56 dp header strip, grid tile titles and the settings chip rows are the likely next ones. Add `FontScalePreviews` to every screen and fix what 200 % breaks. | H | S–M |
| A11Y-2 ✅ | Respect the system reduce-motion setting (DS-7). | M | S |
| A11Y-3 ✅ | Haptics on pick-up, commit threshold, sheet snap and moment saved (DS-7). | M | S |
| A11Y-4 ✅ | *Mark played* is missing from the row actions (SHOW-3), which means a TalkBack user cannot do it from a list either. **Done with SHOW-3:** it is a swipe action on the show page's rows and, like every gesture here, published as a custom accessibility action beside it. | M | S |

### 3.12 Copy and localisation

| Id | Finding | Impact | Effort |
|----|---------|--------|--------|
| COPY-1 ✅ | **The formatters speak English from Kotlin** — *Today*, *3 days ago*, *1 h 23 min*, *12 min left*, *MB* — while every other word is a resource, and the preview data is Russian. Move relative dates to `DateUtils.getRelativeTimeSpanString` or plurals, and units to resources, so a non-English locale is not half translated. **Done with plurals** rather than `DateUtils`, so the wording stays the app's; the absolute dates are left to `DateTimeFormatter`, which knows every locale's month names already. Each of the four affected formatters takes the `Resources` it says its words with, read from `LocalResources` at the call site — which also fixed the two `ConstantLocale` findings that were the whole of `:core:common`'s lint baseline, so the file is gone. | M | M |
| COPY-2 | **Terminology drift around deletion:** the library row says *Remove*, the show overflow says *Delete*, a download says *Remove download* here and *Removed … from this device* there. Settle on *Remove* for a show (it leaves the library) and *Delete* for a file (it leaves the device), and use each everywhere. | L | S |
| COPY-3 ✅ | *Play latest* → *Play newest* (latest reads as "most recent I played"), or *Continue* when there is progress (SHOW-4). | L | S |
| COPY-4 | Write the copy rules down in `docs/` — sentence case, the `·` separator, numerals, how a destructive action is named — so the next screen matches without re-deriving them. | L | S |

### 3.13 Watch

The watch is deliberately its own design (no artwork, a colour per show, one scrolling list) and
that holds up. Improvements are small:

| Id | Finding | Impact | Effort |
|----|---------|--------|--------|
| W-1 ✅ | **Scrub mode is undiscoverable by sight.** Tap-to-enter is explained only in the TalkBack label. Draw a thumb once entered and show a one-time *Turn the bezel to seek* hint. **Both done:** the bar grows a thumb while it is held — which `WatchPlayerUiState.isScrubbing` had claimed in its own KDoc for as long as it has existed — and the first scrub on a watch says what the bezel now does. The sentence goes as soon as the bar moves, because by then it is covering the times it was explaining, and it is written down as shown the moment it appears rather than when it is dismissed: a wearer who taps in and straight back out has read it. See `WatchHints`. | M | S |
| W-2 ✅ | **Haptic on moment saved.** The vibrator is used only for the bell; the button pressed blind through a sleeve is the one that needs a tick. **Done**, keyed on the confirmation rather than on the press: a mark that could neither be delivered nor queued sets nothing, and a wrist that buzzed anyway would have said the moment was kept when it was not. | M | S |
| W-3 | Stored-episode rows omit the duration for an unstarted episode; *will this fit my walk* needs it. | L | S |
| W-4 | With many stored episodes the transport controls scroll out of reach. A two-page layout (now playing · library) is the Wear convention for exactly this. | L | M |
| W-5 | The tile's play button wears the show's colour; when nothing is playing it could wear the brand citron so the tile still reads as this app. | L | S |

---

## 4. Prioritised roadmap

| Phase | Theme | Items |
|-------|-------|-------|
| **P0 — a day of polish** ✅ | First impressions and the brand on the player | SYS-1 lock-screen skip buttons (confirmed) · DS-6 splash and first frame · PL-7 mini player (height, glyphs, skip-back) · PL-1 adopt scrubber, play button, backdrop · SHOW-2 download state on rows · SET-2 confirm remove-all · A11Y-1 font-scale pass · DS-7 haptics and reduce-motion · DS-4 player tokens |
| **P1 — the listening loop** ✅ | From "a list of shows" to "what do I listen to now" | NAV-1 Home (continue, new, up next) · SHOW-1 episode sheet with show notes · SHOW-3 mark played · PL-2 chapters · PL-4 sleep timer · ADD-1 share target and link handling · SHOW-4 continue button · PL-9 queue header and clear · NAV-6 notification landing. All landed. The phase set out to answer "what do I listen to now" and ends up having answered a second question with it — *where am I in this*: an episode is a list of named segments on the scrubber, in the sheet, and now under a thumb on a lock screen, rather than a bar with a number at each end. |
| **P2 — control and personalisation** ✅ | Settings that follow the show, not the app | PL-5 speed sheet · SHOW-5 sort and persisted filter · SHOW-6 show settings · SET-1 appearance · SET-3 notifications · LIB-1/LIB-2 sort and filter · LIB-3 badge vocabulary · DL-1 sections · DL-2 download now · ADD-2 preview before subscribe · COPY-1 localised formatters · PL-6 time labels · PL-8 error copy · MOM-1. All landed. The phase's theme came out truer than the list reads: a show can now disagree with the app about speed, downloads and notifications; the library can be ordered and narrowed rather than only arranged; the downloads screen says which of four things each row is doing; and the app can be looked at in the palette, the language and the brightness the user chose rather than the ones the code was written in. |
| **P3 — reach** ◑ | Larger screens, other surfaces, migration | NAV-3 two-pane on the Fold · PL-11 wide player · SYS-2 widget · SYS-3 shortcuts · ADD-3 OPML · MOM-2 · W-1/W-2/W-4 · DS-5 screenshot tests · DS-8 RTL · DS-3 dead vocabulary · NAV-2 Downloads tab decision. Four done: W-1 and W-2, which make the watch answer the hand; DS-3, which stops the design system describing an app that no longer exists; and NAV-2, answered in its row — Downloads stays a tab. |

Rough sizing: P0 fits in one to two days; P1 is the bulk of the work at roughly two weeks, with
NAV-1 and PL-2 the two large pieces; P2 and P3 are each a week or so of independent, schedulable
items.

### 4.1 What is left

Every id below is unmarked in §3; each row says the whole of what remains, so nothing has to be
re-derived by reading the section it came from.

Nothing is half built any more; every id below is one that has not been started.

**P3 — reach.** Larger screens, other surfaces, migration. Nine left; W-1, W-2, DS-3 and NAV-2
are done.

| Id | Item | Impact | Effort |
|----|------|--------|--------|
| NAV-3 | Two-pane list/detail on the open Fold; `PodcastDetailRoute.showBackButton` already exists for it | M | L |
| DS-5 | Theme and font-scale previews on every screen, and a screenshot suite | M | M |
| DS-8 | RTL: mirror the swipe, or declare `supportsRtl="false"` honestly | L | M |
| PL-11 | The expanded player on the inner display: cap the hero, lay artwork and controls side by side | M | M |
| SYS-2 | A Glance widget: now playing with transport, and a *Continue listening* row | M | M |
| SYS-3 | Static app shortcuts: Resume, Queue, Downloads, Add a show | L | S |
| ADD-3 | OPML import and export | M | M |
| MOM-2 | Moments: filter by show, search notes, group by show, swipe to edit a note | L | M |
| W-4 | Watch: a two-page layout, so the transport does not scroll out of reach | L | M |

**Unscheduled.** Real findings that no phase claimed — mostly small, mostly independent, and each
worth doing on the day the screen it belongs to is open for another reason.

| Id | Item | Impact | Effort |
|----|------|--------|--------|
| NAV-4 | Re-tapping the current tab should scroll to top | L | S |
| NAV-5 | Settings is reachable only from the Library bar | L | S |
| NAV-7 | Four tabs, two app-bar styles; pick one | L | S |
| NAV-8 | The library empty state uses a layout glyph for "no podcasts yet" | L | S |
| LIB-4 | The grid has no way to remove a show | L | S |
| SHOW-7 | Header details: *Show more* when it is not needed, no feed link, no *Share show* | L | S |
| PL-3 | Show notes in the player itself | M | M |
| PL-10 | *Up next* vanishes when the queue is empty, so the player never mentions the queue | L | S |
| DL-3 | The storage card could say why an episode disappeared (keep-limit, delete-after-playing) | L | S |
| ADD-4 | Clipboard hint when Search opens on a URL | L | S |
| ADD-5 | Discovery when the field is empty: recent searches, top charts | L | M |
| SET-4 | About: version, font licences, whether crash reporting is on | L | S |
| SET-5 | A one-line pre-prompt before the notification permission dialog | L | S |
| SET-6 | Now that PL-5 and SHOW-6 have landed, the speed row here should read *default speed* and list the shows that override it | L | S |
| SYS-4 | Android Auto media browser tree — noted, not planned | — | — |
| COPY-2 | Terminology drift around deletion: *Remove* a show, *Delete* a file | L | S |
| COPY-4 | Write the copy rules down in `docs/` | L | S |
| W-3 | Watch: stored-episode rows omit the duration of an unstarted episode | L | S |
| W-5 | Watch: the tile's play button could wear the brand citron when nothing is playing | L | S |

### 4.2 Where to pick this up

The tables above say what is left. This says what to do on the next morning of work, and why in
that order. Nothing here is a new finding; it is the reading order for the ones already listed.

**Do these two before writing any more feature code.** Both are debts the branch has been carrying
and both get more expensive the longer the branch runs.

1. **The device pass.** Not one item on this branch has been seen on the Fold 7 or the Watch Ultra 2
   — every phase's acceptance bar in §6 asks for one and none has happened. Two things are entirely
   unverified by anything a JVM can run: **W-1**'s thumb and hint (Robolectric rendered them; a
   round 45 mm screen is what decides whether the sentence fits under the bar without pushing the
   times off it) and **PL-2**'s notification buttons (a lock screen, a headset and, if there is one
   to hand, a car). The `install_on_devices` skill puts both APKs on both devices from one build,
   which is the only way they may be installed — see the pairing constraint in `CLAUDE.md`.
2. **Read the `gradle/verification-metadata.xml` diff.** It has been waiting since `:feature:listen`
   landed and has grown since: the Firebase and Crashlytics entries, `androidx.collection:collection-jvm`
   and `androidx.profileinstaller`. `docs/DEPENDENCY_VERIFICATION.md` asks for a human, and no human
   has read it. This is the one item on the list that a person other than the author cannot do
   later.

**Then P3, in this order.** The reasoning is that the two watch items left are cheap and the watch
is already open; the widget and the shortcuts are the app's remaining unreached surfaces; and the
Fold work is one large piece best started fresh.

3. **W-4** — the watch's two-page layout, so the transport stops scrolling out of reach. The natural
   next thing while `WatchPlayerScreen.kt` is still in mind, and it collects **W-3** and **W-5**
   (both unscheduled, both small, both in files it touches) on the way past.
4. **SYS-3** — static app shortcuts. The smallest item in P3 and the only one that needs no design:
   four `<shortcut>` entries and the intents already exist, `EXTRA_OPEN_PLAYER` among them.
5. **SYS-2** — the Glance widget. *Continue listening* is now a real query rather than something to
   invent, because `:feature:listen` already asks it; the widget is that shelf and a transport row.
6. **DS-5** — previews and a screenshot suite. Worth doing *before* NAV-3 and PL-11 rather than
   after: those two rearrange every screen at a second size, and a screenshot suite is what makes
   that rearrangement reviewable instead of a thing to be spotted by eye.
7. **NAV-3**, then **PL-11** — the open Fold. The two large pieces, and the ones this app has been
   waiting for since `PodcastDetailRoute.showBackButton` was written for a caller that never came.
8. **ADD-3**, **MOM-2**, **DS-8** — independent, and each fine to take on the day its screen is open
   for another reason.

**A note for whoever does DS-3's neighbour.** `EpisodeRow.onLongClick` and `longClickLabel` are the
last limb of the multi-selection DS-3 removed: no screen passes either. They were left because a
labelled long-press is an accessibility action rather than a mark on the screen, and because the
next feature that wants a row context menu will want exactly them. If nothing has claimed them by
the time the next audit is written, they should go the way of `SelectionToolbar`.

---

## 5. Decisions to make before P1

> **All four are decided and implemented.** D-1: the row tap opens the episode sheet and a
> `PlayPauseButton(Small)` on the row plays it, as recommended. D-2: *Listen* is a fifth tab and the
> start destination, as recommended; Downloads stayed where it was, so NAV-2 is still open. D-3: the
> bell is folded into the sleep timer as its *End of episode* option, as recommended. D-4: badged,
> as recommended — the player's speed button wears a dot, and says so aloud, whenever the rate in
> force is not the app's.

**D-1 — What does tapping an episode row do?** Today: play now, and the player expands. This is
fast for the common case and it is a deliberate, documented decision. But it is also why an episode
can never be read, and the industry default (Apple Podcasts, Pocket Casts, Overcast) is *tap opens
the episode, a play button on the row plays it*. The design system already anticipates this:
`PlayPauseSize.Small` is documented as "inside a list row" and is unused.

Recommendation: the row tap opens the episode sheet (SHOW-1); a `PlayPauseButton(Small)` at the
trailing end of every row keeps play at one tap *and* shows the now-playing and buffering state in
the list, replacing the three-bar indicator. The swipe tiers stay as they are. The earlier objection
to per-row controls was about a *download* button per row; a play button is the one control a list
of episodes exists for. The alternative — keep tap-to-play and put *Details* on the short swipe —
keeps the current speed but hides show notes behind a gesture nobody will find.

**D-2 — Home as a fifth tab, or shelves on Library?** A fifth tab is the cleaner model (Library
stays an inventory) but pushes the bar to five items. Shelves at the top of Library keep four tabs
and cost a scroll before the shows. Recommendation: a *Listen* tab as the start destination, with
Library second; four remains possible if Downloads moves (NAV-2).

**D-3 — Sleep timer and bell.** Fold the bell into the timer as its *end of episode* option, or
keep both controls. Recommendation: one control, one icon, the bell's behaviour preserved as an
option; two "stop later" buttons on a transport row is one too many.

**D-4 — Speed per show.** Whether a per-show speed overrides the global default silently, or is
shown as a badge on the speed button when active. Recommendation: badge it; a speed that changes
between shows with no visible reason reads as a bug.

---

## 6. How to validate

- **Previews:** every screen gets `ThemePreviews` and `FontScalePreviews`; a preview at 200 % is
  where most of A11Y-1 will be found without a device.
- **Screenshot tests (DS-5):** Roborazzi over the design-system components and each screen state,
  in light and dark and at 100 % and 200 %, so P0's visual work cannot regress silently.
- **On-device pass, per phase:** Fold 7 closed and open, light and dark, TalkBack on, for each
  changed screen; Watch Ultra 2 for the tile, complication and scrub mode.
- **A first-sixty-seconds self-test** as the acceptance bar for P1: from a cold launch, resume the
  last episode in at most two taps; see what arrived today in one; read an episode's show notes in
  at most two; add a show from a link shared out of a browser without typing.

---

## 7. What not to change

These are decisions the codebase has already earned, and every item above is meant to fit them:

- **No dynamic colour by default.** The brand is the point; offer Material You as an opt-in only.
- **Every gesture has a spoken twin.** A new swipe, drag or long press ships with its custom
  accessibility action or it does not ship.
- **Confirm what cannot be undone; undo what can.** Dialogs count what is at stake in numbers;
  reversible actions get a snackbar with *Undo*, never a dialog.
- **Automatic work is a hairline; requested work gets a spinner and an answer.**
- **The player is a sheet, not a route,** with one piece of artwork that travels.
- **Copy lives in resources, plural-aware, and design-system components carry no screen copy.**
- **One artwork placeholder, one downloaded mark, one now-playing indicator** — a fact looks the
  same wherever it is seen.

---

## Appendix — surfaces reviewed

Phone: app shell and navigation, Library (grid and list), Search/Add, Show page, Player (collapsed,
expanded, sheet state), Queue, Downloads, Moments, Settings (including backup), launch theme and
manifest, formatters, every `strings.xml`. Design system: theme (colour, type, shape, spacing,
elevation, motion, previews) and every component, shape and the reorder helper. Watch: player screen, UI state, show
accent, tile layout, complication, strings and theme.
