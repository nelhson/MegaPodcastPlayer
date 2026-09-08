# MegaPodcastPlayer — UX and design improvement plan

*7 September 2026. A code-level design review of every phone screen, the shared design system in
`:core:designsystem`, and the watch surfaces. Each item carries an impact estimate (H/M/L, to the
person using the app) and an effort estimate (S: under half a day, M: one to two days, L: three days
or more).*

> **Implementation status.** The plan is being worked through on the `ux-design-plan` branch. An
> id marked **✅** is implemented and covered by tests; **◑** is partly done, with what is left
> stated in the row; **✖** is decided against and will not be built, with the reason in the row;
> an unmarked id has not been started. §4's roadmap carries the same marks per
> phase. Nothing here is merged until it has been tried on the Fold 7 and the Watch Ultra 2.
>
> **Where it stands: 53 of the 73 items are done, 3 are decided against, none is half done, 17 are
> not started.** Eight items in the tail were an open choice rather than a task; all eight were
> decided on 8 September 2026 (§5.1) — three of them closed without code, and the other five stay
> on the list as work with the choice already made. Each row restates its own answer, so nothing has
> to be re-derived. The 46th done item was PL-10, which was built with PL-9 and never marked; the
> six since are the watch's — W-4, with W-3 and W-5 swept up on the way past — SYS-3, the
> launcher's four shortcuts, and then NAV-7 and DS-5 together: one app bar for all five top-level
> destinations, recorded the same day by a screenshot suite that renders every screen's own
> preview. NAV-7 went first on purpose — changing every app bar after the goldens are recorded
> means recording them twice.
>
> | Phase | State | Left |
> |-------|-------|------|
> | P0 — a day of polish | ✅ complete | — |
> | P1 — the listening loop | ✅ complete | — |
> | P2 — control and personalisation | ✅ complete | — |
> | P3 — reach | started | 5 of its 13 items |
> | Unscheduled (in no phase) | started | 12 items, 3 decided against, 1 found already built |
>
> §4.1 lists the 17 by name and §4.2 says which to take first. The one half-finished item is finished: PL-2's chapters now reach
> the notification, the lock screen and everything else that presses *next* through a media
> session. P3 has begun with the three smallest things in it — W-1, W-2 and DS-3 — and with
> NAV-2, which was a question rather than a task and is answered below. Three unscheduled items are
> now closed without code — PL-3, SYS-4 and SET-5 — for the reasons in §5.1, and a fourth, PL-10,
> turned out to have been built with PL-9 and never ticked.
> **8 September:** W-4 landed, and took W-3 and W-5 with it — the watch screen is two pages now, so
> the transport no longer scrolls away under a list of episodes, and the two small watch items were
> in the files it opened. The watch is finished as far as this plan goes; every remaining P3 item is
> the phone's or the build's. SYS-3, then NAV-7 and DS-5 together, then **NAV-3** — the Library tab
> is a list and a show side by side on the inner display, and `PodcastDetailRoute.showBackButton`,
> written three phases ago for a caller that never came, has one. See
> `docs/reports/2026-09-08-nav-3-two-pane-library.md`. PL-11, the other half of that Fold pair, is
> next.
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
| DS-5 ✅ | **Previews and screenshots.** `ThemePreviews` and `FontScalePreviews` exist in the design system, but every screen carries a single bare `@Preview` with no dark or large-font variant, and there is no screenshot test suite. Design regressions are invisible in CI; the palette-only-rendered-in-previews incident the theme's KDoc describes could recur. **Done, and the row's first half had quietly finished itself** — A11Y-1 and the phases since had already put `ThemePreviews` on every screen and `FontScalePreviews` on most, so what was actually missing was the suite. 114 goldens under `<module>/src/test/screenshots`, rendered by the Robolectric already on the classpath and compared by Roborazzi, in three renderings: light, dark, and light at 200 % text. **Each test renders the component's own `@Preview`** rather than building a state of its own, which is what makes the two halves of this row one thing: the preview is where the curated state lives, so a golden cannot drift from what a designer looks at, and adding a state to the suite is a preview plus one line. Animations are removed through the app's own reduce-motion switch, so a golden is a real still frame rather than one arbitrary tick of a loop. `docs/SCREENSHOT_TESTS.md` has the rest, including the two Gradle bugs the suite found by lying once about a re-record. | M | M |
| DS-6 ✅ | **First frame.** The launch theme's `windowBackground` is `@android:color/background_light` in both modes, so dark-mode launches flash white; `core-splashscreen` is a declared dependency but `installSplashScreen()` is never called and no `Theme.….Starting` exists. Define a splash theme on the ink ground with the icon, install it, and give the window background a night qualifier. | H | S |
| DS-7 ✅ | **Motion ignores the system's "remove animations" setting** — the now-playing bars, wavy hairline, morphing loader, scrubber wave and the watch waveform all loop unconditionally — and **the phone has no haptics at all**: no tick when a long press picks up a row, when a full swipe crosses its commit threshold, when the sheet snaps, or when a moment is saved. Both are cheap and both are the difference between "animated" and "physical". | M | S |
| DS-8 | **RTL.** `supportsRtl` is true, but `SwipeActionsRow` only opens leftwards and anchors its buttons at `CenterEnd`, so a mirrored layout puts the buttons on the left and the gesture pulling away from them. Mirror the gesture with `LayoutDirection`, or declare `supportsRtl="false"` honestly. **Decided (§5.1, D-5): declare it false.** This is a sideloaded build for one person who reads left to right; `supportsRtl="true"` is currently a claim the swipe rows do not honour, and a manifest that tells the truth costs an attribute where mirroring the gesture costs a day and would never be run. The attribute carries a comment saying it is a scope decision rather than an oversight, so an app that ever wants a second reading direction knows the work is `SwipeActionsRow`'s and not the manifest's. Effort drops from M to S. | L | S |
| DS-9 ✅ | **Components the roadmap will need and the system does not have:** a bottom-sheet container with a standard header (speed picker, episode details, show settings), a horizontal *shelf* of episode cards (for a Home surface), a sort/segmented control, and a chip row with persisted selection. Build them in the design system as the P1 items land rather than inline in features. **Done:** `MegaPodcastPlayerBottomSheet` (with the standard header), `EpisodeShelf`/`EpisodeCard` for the Listen tab, and — with SHOW-5 — the show page's persisted chip row and its order toggle. **Done with LIB-1:** the shared sort control, as `SortToggleChip` (two orders, flipped) and `SortMenuChip` (more than two, chosen from) in one file — one control with two arities. The show page's toggle is now the first of those, and the library's four-way menu the second. | M | M |

### 3.2 Navigation and information architecture

| Id | Finding | Impact | Effort |
|----|---------|--------|--------|
| NAV-1 ✅ | **There is no "now" surface.** The four tabs are Library, Queue, Downloads, Moments. Finding something to play is Library → show → scroll → tap, and the two things most sessions actually want — *continue what I was listening to* and *what arrived since yesterday* — have no home. The data is already there (`Episode.isInProgress`, `isNew`, the durable queue). Proposal: a **Home** tab (or shelves at the top of Library) with *Continue listening*, *New episodes* across all shows, and *Up next*. This is the single highest-leverage change in the plan. Shipped as a `Listen` tab and the start destination (decision D-2), in a new `:feature:listen` module, with `EpisodeShelf`/`EpisodeCard` added to the design system (part of DS-9). | H | L |
| NAV-2 ✅ | **Downloads as a top-level tab** is a storage-management view promoted to a primary destination. Once Home exists, decide whether Downloads stays a tab, becomes a filter chip on Home, or lives under Settings › Storage with the card it already has. **Decided: it stays a tab**, and the bar keeps five. The objection was a taxonomic one — a storage view among four listening destinations — and the answer is that on this phone, with this library, what is downloaded *is* a listening destination: it is the list you read before a flight or a tunnel, and the one place a copy can be started, paused or thrown away. A screen that answers "what can I play with no signal" has earned a tab; the tidier arrangement would have cost a tap on the day the tap is hardest to make. No code change. | M | S |
| NAV-3 ✅ | **The open Fold is a phone with a rail.** `NavigationSuiteScaffold` swaps the bar for a rail, but Library → show is still a full-screen push, and `PodcastDetailRoute` already has a `showBackButton` parameter written for a two-pane layout nobody calls. A `ListDetailPaneScaffold` for Library/Show (and later Queue/Player) is what the inner display is for. **Done:** the Library tab is a `NavigableListDetailPaneScaffold`, and `showBackButton` has its caller — the arrow is drawn only when the list pane is *not* on screen. The detail pane carries a graph of its own rather than a `contentKey`, so `PodcastDetailViewModel` still reads its id out of a `SavedStateHandle` filled by a navigation argument, exactly as it says it does, and `PodcastDetailRoute` is the same call in both places it is now made. The outer `Route.PodcastDetail` stays full-screen at every width for the shows reached from a notification, a search result or a link: the list a pane would put beside those is the library, and the library is not where the user came from. `ShowRow` and `ShowTile` gained `isSelected` — a wash and Compose's own `Selected` property, set only where a pane exists to select for. Queue and Player are *not* taken: a queue is one list with no detail beside it, and the player is a sheet rather than a destination. See `docs/reports/2026-09-08-nav-3-two-pane-library.md`, including the two bugs the writing found and D-14 to D-16. | M | L |
| NAV-4 | **Re-tapping the current tab does nothing** (the guard returns early). Platform convention is scroll-to-top; with a long library it is the fastest way back. | L | S |
| NAV-5 | **Settings is reachable only from the Library bar.** From Queue, Downloads or Moments it is a tab switch plus a tap. Put the gear on every top-level bar, or in a consistent overflow. **Decided (§5.1, D-7): the gear on every top-level bar.** One tap from anywhere, and an overflow would be a menu holding a single item on four of the five screens — a container invented to hide the one thing in it. | L | S |
| NAV-6 ✅ | **Notification taps land short.** The new-episode notification opens the *show*; opening the episode (or Home with it highlighted) is what the tap means. The playback notification opens the app with the sheet collapsed; it should arrive expanded. The playback notification now carries `EXTRA_OPEN_PLAYER` and arrives with the sheet expanded; a new-episode notification that named exactly one episode opens that episode's sheet, and one naming several still opens the show. | M | S |
| NAV-7 ✅ | **Four tabs, two app-bar styles.** Library, Queue and Downloads argue in their comments for a pinned small bar; Moments uses the large collapsing one. Pick one for all top-level destinations. **Decided (§5.1, D-6): the pinned small bar, everywhere.** There are five top-level destinations now that Listen exists, and a collapsing bar on one of five reads as an accident rather than as emphasis. The three screens that already argued for pinned argued from the same fact — a list you came to scroll should not spend a third of the screen saying where you are. Moments loses its large title; if a screen ever earns a collapsing bar it will be a detail screen, where the title is the content's name and not the tab's. **Done**, and it was two screens rather than one: Listen had adopted the large bar as well by the time this was built. `MegaPodcastPlayerLargeTopAppBar` went with them — removed rather than kept for the detail screen that might one day earn it, on DS-3's rule that the design system describes the app, and Material's own `LargeTopAppBar` is one import away on the day a screen does. Taken immediately before DS-5, because goldens recorded over an app bar that is about to change are goldens recorded twice. | L | S |
| NAV-8 | The Library empty state uses the `GridView` glyph (a *layout* icon) for "no podcasts yet"; use the `Podcasts` glyph the artwork placeholder already uses. | L | S |

### 3.3 Library

| Id | Finding | Impact | Effort |
|----|---------|--------|--------|
| LIB-1 ✅ | **Manual order is the only order.** Offer *Recently updated*, *A–Z* and *Most unplayed* alongside *Manual*, persisted like the layout toggle. Drag stays available in Manual. **Done:** `LibrarySort` in `:core:model` with the ordering rule beside it (nulls last for a feed that dates nothing, every computed order broken on title so the list cannot reshuffle itself between emissions), stored like the layout, drawn with the design system's new `SortMenuChip`. Dragging withdraws for every order but *My order* — and for a narrowed list too, since a drag there would be read as positions in the whole library. | M | M |
| LIB-2 ✅ | **No way to narrow a large library.** A filter field, or a *Has new episodes* chip, for libraries past a screenful. **Done:** both, and only past a screenful — eight shows. The field matches the author as well as the title, because half the shows in a library are remembered by who makes them. Not stored, deliberately: a library that opened showing four of its shows because of a chip tapped last week would look like data loss. | M | S |
| LIB-3 ✅ | **Two meanings of one badge.** The library badge counts `isNew` (arrived since last seen); the show page's *Unplayed* filter means *never started*. Same colour, different facts. Decide the vocabulary once — *New* = arrived since you last looked, *Unplayed* = never started — and show both consistently. **Done:** the badge stays *new* and says so, the counts line under a row gained *N unplayed* beside the episode and download counts, and the DAO computes that number with `EpisodeFilter.UNPLAYED`'s own rule in SQL so the number the library sorts by and the list the show page's chip produces are the same episodes. The one shared colour now has one meaning written down — *there is something here you have not heard* — and the words carry the difference. | M | S |
| LIB-4 | The grid has no way to remove a show (long press is reorder); only the list's swipe and the show page's overflow do. Acceptable, but say so in the Remove dialog copy, or add the show page's overflow to the tile via a context menu that opens if the press is released without moving. **Decided (§5.1, D-8): the context menu.** Two things make it the better half. A grid and a list showing the same library should be able to do the same things to it, and copy that explains where a capability *isn't* is a note excusing the design rather than fixing it. The press that moves still reorders; only a press released in place opens the menu, and the menu holds what the show page's overflow holds so there is one list of things that can be done to a show. Note that this does *not* claim `EpisodeRow.onLongClick` — that pair belongs to a row of episodes and the library grid is a grid of shows — but it does settle the interaction the pair was kept for, which is the evidence §4.2's note asked for. | L | S |

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
| PL-3 ✖ | Show notes in the player itself, below the controls or behind a *Notes* segment, so a link mentioned mid-episode is one scroll away. **Decided against (§5.1, D-9).** This was written before SHOW-1 existed; the episode sheet in `:feature:podcast` now renders those notes, with tappable links and seeking timestamps. Rendering the same HTML a second time inside the player means two places to keep a link tappable, two places for a timestamp to seek from, and two answers to "where do show notes live", against §7's rule that a fact looks the same wherever it is seen. The player has no route to that sheet today — the sheet opens from a row on Listen or the show page — so if reaching the notes mid-episode ever bites, the fix is a door from the player to the sheet that exists (a tap on the title, an S) and not a second copy of the text (an M). That door is the successor to this item, and it is not scheduled until the want is felt on a device. | M | — |
| PL-4 ✅ | **Sleep timer.** The end-of-episode bell is a good idea for the falling-asleep case but it is the only case covered. Add a timer (15/30/45/60 minutes, end of episode, end of chapter) with fade-out and shake-to-extend; the bell becomes one option of it. | H | M |
| PL-5 ✅ | **Speed is a cycle button.** Eight steps that wrap from 3× back to 0.8× make the fastest setting a trap. Tap should open a sheet: a 0.5–3.0 slider in fine steps, the presets as chips, and a per-show override (SHOW-6). Trim-silence and volume-boost toggles belong there later. Shipped as `SpeedSheet`: the presets as chips, a continuous 0.5–3.0 slider snapped to 0.05, and −/+ buttons for the same search by someone who cannot hit a 0.05 target. Dragging is *heard* — the rate reaches the player on every change and the preference is written once, on release — because choosing a speed is done by ear. The per-show override lives in SHOW-6's sheet rather than here; the player's speed button carries D-4's dot when the rate in force is the show's. Trim-silence and volume-boost are still later. | M | M |
| PL-6 ✅ | **Time labels.** Tap the right-hand label to toggle total ↔ remaining, and show *ends at 22:41* at the current speed; the tabular numeric style is already there for it. Shipped as a three-state cycle on the right-hand label: time left, total length, and the clock time it ends at — computed at the current speed, which is the part that is easy to get wrong. Not persisted: it is a glance, not a setting. | M | S |
| PL-7 ✅ | **The mini player.** (a) Its skip button is a fixed `Forward30` glyph while the setting allows 10/15/30/45/60 s, so it lies for four of five values; use the same `skipForwardIcon` the expanded player does. (b) It has no skip-back. (c) It cannot be dismissed — a swipe down or a swipe away to stop playback is the convention. (d) Its height is a fixed 64 dp holding two text lines: at 150 % font scale the lines alone outgrow it and clip. Either size it from content, or drop the show line at large scales. Verify on device. | H | S |
| PL-8 ✅ | **Playback errors are raw.** *Playback problem: <exception message>*. Map the common cases (no connection, file gone, unsupported format, YouTube extraction failed) to sentences that say what to do. A `PlaybackError` enum in `:core:media`, classified from Media3's error *codes* rather than its messages, with YouTube checked first because its URLs expire and fail through the same codes as a dead link. The unknown case keeps the player's own words. | M | S |
| PL-9 ✅ | **Queue screen context.** By design it lists only *up next*, so nothing on it says what is playing. A slim *Now playing* header (artwork, title, position) anchors the list; add total remaining time in the bar's subtitle, *Clear queue*, and an *Add episodes* action on the empty state that leads somewhere. | M | S |
| PL-10 ✅ | The *Up next* link vanishes when the queue is empty, so the player never mentions the queue exists. Show *Up next · nothing queued* with a way to add. **This landed with PL-9 and was never marked here.** `UpNextLink` is drawn at a count of zero, reads *Up next · nothing queued* in the muted colour and still opens the queue — the comment above its call site gives the reason, that the user who most needs telling a queue exists is the one who has put nothing in it. The *way to add* is at the other end: the queue's own `EmptyState` carries a *Browse your shows* action, on the stated rule that an empty state with nothing to press is a dead end. No work left; the row was stale, not open. | L | S |
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
| ADD-5 | **Discovery when the field is empty:** recent searches and Apple's top charts by genre. Low priority for a personal build. **Decided (§5.1, D-11): recent searches yes, top charts no.** A recent-searches list is a handful of strings in DataStore and it answers the thing that actually happens here — the same show looked up twice because the first attempt was made on the wrong device. Top charts are a browsing surface for a store, fetched from an endpoint that would have to be kept working, on a screen whose whole job in this app is to find a show already decided on. The scoped item is an S; the unbuilt half is written down as declined so the next audit does not re-propose it. | L | S |

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
| SET-5 ✖ | The notification permission is asked for on launch with no context. A one-line pre-prompt on first launch (*We'll tell you when a show publishes, and keep the player controls on your lock screen*) turns a blind system dialog into a choice. **Decided against (§5.1, D-12).** A pre-prompt earns its place by raising the grant rate across a population of strangers who have to guess what an app will do with the permission. This app has one user, who wrote it, has already granted it, and would meet the extra screen exactly once — on a reinstall, after wiping the data, knowing the answer. SET-3's row into the system notification page is the durable half of this concern and it is built. If the app is ever handed to someone else, this row is the first thing to reopen. | L | — |
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
| SYS-3 ✅ | **Static app shortcuts:** Resume, Queue, Downloads, Add a show. **Done**, and the four divide two ways rather than one. Three name a place and are answered where the graph is, by the same `navigateToTopLevel` a tab tap uses, so arriving from the launcher and arriving by thumb leave the same back stack behind. *Resume* names no place at all: it is playback, answered before there is a composition, and built out of the two things that already happen when the app is opened by hand and the mini player's play button is pressed — the cold-start queue restore, then play. Resolving "the last episode" a second way here would have been a second answer to a question `resumableQueue()` already answers. Nothing to resume opens the app and nothing else: an empty sheet is a blank screen in reply to *carry on*. | L | S |
| SYS-4 ✖ | Android Auto needs a media browser tree; noted as a possible later phase, not planned here. **Decided against (§5.1, D-10): out of scope, and now closed rather than left hovering.** It was the one item in the plan with no impact and no effort estimate, which is what an unmade decision looks like in a table. The work is not a screen but a second information architecture — a browsable tree, its own validation, and a driving-safety review — for a car this user does not listen in. PL-2's `ChapterAwarePlayer` already gives a car's *next* button the meaning it should have if one is ever plugged in, which is the part that would have been hard to add later. | — | — |

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
| W-3 ✅ | Stored-episode rows omit the duration for an unstarted episode; *will this fit my walk* needs it. **Done:** the second line now answers that question on every row that can — what is left of a part-heard episode, the whole length of one nobody has started, *Played* for a finished one. One rule, three tenses of the same sentence. A copy that arrived without a duration still says only the show: a `0m` invented for it would be the one thing worse than saying nothing. | L | S |
| W-4 ✅ | With many stored episodes the transport controls scroll out of reach. A two-page layout (now playing · library) is the Wear convention for exactly this. **Done:** `HorizontalPagerScaffold` over two pages — the transport on one, every episode list on the other — so play sits where it sat when the watch was carrying nothing, however much it is carrying now. The pager appears only when there is something to control: with the phone idle there is no first page worth the swipe, so the screen collapses back to the single list it always was and the sentence about the idle phone stays above the episodes it points at. The two notes about the phone link are drawn on *both* pages, because the question they answer is asked from whichever page the thumb was on. Paging is off while the bar is held, so a scrub that starts a few pixels wide of it moves the playhead rather than turning the page. | L | M |
| W-5 ✅ | The tile's play button wears the show's colour; when nothing is playing it could wear the brand citron so the tile still reads as this app. **Done, and the row was one step out:** there *is* no play button when nothing is playing — the idle tile deliberately draws no dead controls — so the citron goes on the one thing that state does draw, its heading. What the tile follows now is a single rule: colour here means *which show is this*, and the one state with no show borrows the app's own colour rather than the white every other tile in the carousel is already wearing. | L | S |

---

## 4. Prioritised roadmap

| Phase | Theme | Items |
|-------|-------|-------|
| **P0 — a day of polish** ✅ | First impressions and the brand on the player | SYS-1 lock-screen skip buttons (confirmed) · DS-6 splash and first frame · PL-7 mini player (height, glyphs, skip-back) · PL-1 adopt scrubber, play button, backdrop · SHOW-2 download state on rows · SET-2 confirm remove-all · A11Y-1 font-scale pass · DS-7 haptics and reduce-motion · DS-4 player tokens |
| **P1 — the listening loop** ✅ | From "a list of shows" to "what do I listen to now" | NAV-1 Home (continue, new, up next) · SHOW-1 episode sheet with show notes · SHOW-3 mark played · PL-2 chapters · PL-4 sleep timer · ADD-1 share target and link handling · SHOW-4 continue button · PL-9 queue header and clear · NAV-6 notification landing. All landed. The phase set out to answer "what do I listen to now" and ends up having answered a second question with it — *where am I in this*: an episode is a list of named segments on the scrubber, in the sheet, and now under a thumb on a lock screen, rather than a bar with a number at each end. |
| **P2 — control and personalisation** ✅ | Settings that follow the show, not the app | PL-5 speed sheet · SHOW-5 sort and persisted filter · SHOW-6 show settings · SET-1 appearance · SET-3 notifications · LIB-1/LIB-2 sort and filter · LIB-3 badge vocabulary · DL-1 sections · DL-2 download now · ADD-2 preview before subscribe · COPY-1 localised formatters · PL-6 time labels · PL-8 error copy · MOM-1. All landed. The phase's theme came out truer than the list reads: a show can now disagree with the app about speed, downloads and notifications; the library can be ordered and narrowed rather than only arranged; the downloads screen says which of four things each row is doing; and the app can be looked at in the palette, the language and the brightness the user chose rather than the ones the code was written in. |
| **P3 — reach** ◑ | Larger screens, other surfaces, migration | NAV-3 two-pane on the Fold · PL-11 wide player · SYS-2 widget · SYS-3 shortcuts · ADD-3 OPML · MOM-2 · W-1/W-2/W-4 · DS-5 screenshot tests · DS-8 RTL · DS-3 dead vocabulary · NAV-2 Downloads tab decision. Eight done, NAV-3 the latest — the Library tab is two panes on the inner display, and the parameter written for it three phases ago finally has a caller. Seven before it: W-1 and W-2, which make the watch answer the hand; W-4, which gives the transport a page nothing can push it off; DS-3, which stops the design system describing an app that no longer exists; NAV-2, answered in its row — Downloads stays a tab; SYS-3, the launcher's four shortcuts; and DS-5, the screenshot suite, with NAV-7 taken from the unscheduled list immediately before it so the app bars were settled before any golden was recorded — which paid for itself inside NAV-3, where a clip on the library tile trimmed two marks out of its corners and the *unselected* goldens caught it. Of the five left, DS-8 shrank from M to S on 8 September when it was decided rather than designed (D-5), and the order the rest are taken in changed once: the Fold now goes before the widget (D-13). PL-11, the second half of that Fold pair, is next. |

Rough sizing: P0 fits in one to two days; P1 is the bulk of the work at roughly two weeks, with
NAV-1 and PL-2 the two large pieces; P2 and P3 are each a week or so of independent, schedulable
items.

### 4.1 What is left

Every id in the two tables below is unmarked in §3; each row says the whole of what remains, so
nothing has to be re-derived by reading the section it came from. The third table is the opposite —
ids that are finished, by a decision or by code — and is here so that an id remembered from an
earlier reading can be found in one place.

Nothing is half built any more; every id in the first two tables is one that has not been started.
Where §5.1 settled
how an item should be built, the row says which way it goes — the choice is not open any more, only
the work. Four ids have left these tables — three decided against (PL-3, SYS-4, SET-5) and one,
PL-10, found already built — and all four are listed under them, so a reader who remembers an id can
see it was answered rather than lost. Four more left on 8 September by being built: W-4, with W-3
and W-5 alongside it, and SYS-3.

**P3 — reach.** Larger screens, other surfaces, migration. Five left; W-1, W-2, W-4, DS-3, NAV-2,
SYS-3, DS-5 and NAV-3 are done.

| Id | Item | Impact | Effort |
|----|------|--------|--------|
| DS-8 | RTL: declare `supportsRtl="false"`, with a comment saying it is scope and not oversight (D-5) | L | S |
| PL-11 | The expanded player on the inner display: cap the hero, lay artwork and controls side by side | M | M |
| SYS-2 | A Glance widget: now playing with transport, and a *Continue listening* row | M | M |
| ADD-3 | OPML import and export | M | M |
| MOM-2 | Moments: filter by show, search notes, group by show, swipe to edit a note | L | M |

**Unscheduled.** Real findings that no phase claimed — mostly small, mostly independent, and each
worth doing on the day the screen it belongs to is open for another reason. Three have gone that
way already: W-3 and W-5 were both in files W-4 opened, and both were cheaper to do there than to
remember; NAV-7 was pulled forward on its own schedule rather than an accident of proximity — it
had to precede DS-5 or the goldens would have been recorded twice.

| Id | Item | Impact | Effort |
|----|------|--------|--------|
| NAV-4 | Re-tapping the current tab should scroll to top | L | S |
| NAV-5 | Settings: put the gear on every top-level bar, not in an overflow (D-7) | L | S |
| NAV-8 | The library empty state uses a layout glyph for "no podcasts yet" | L | S |
| LIB-4 | The grid has no way to remove a show: give the tile a context menu, opened by a press released in place (D-8) | L | S |
| SHOW-7 | Header details: *Show more* when it is not needed, no feed link, no *Share show* | L | S |
| DL-3 | The storage card could say why an episode disappeared (keep-limit, delete-after-playing) | L | S |
| ADD-4 | Clipboard hint when Search opens on a URL | L | S |
| ADD-5 | Discovery when the field is empty: recent searches only; top charts declined (D-11) | L | S |
| SET-4 | About: version, font licences, whether crash reporting is on | L | S |
| SET-6 | Now that PL-5 and SHOW-6 have landed, the speed row here should read *default speed* and list the shows that override it | L | S |
| COPY-2 | Terminology drift around deletion: *Remove* a show, *Delete* a file | L | S |
| COPY-4 | Write the copy rules down in `docs/` | L | S |

**Closed without code.** Three by decision on 8 September (§5.1), one by a row that was stale rather
than open.

| Id | Item | Why it is closed |
|----|------|------------------|
| PL-3 ✖ | Show notes in the player itself | SHOW-1's episode sheet renders them; a second copy is a second place to maintain (D-9). A door from the player to that sheet is the successor, unscheduled. |
| SYS-4 ✖ | Android Auto media browser tree | A second information architecture for a car this user does not listen in (D-10). |
| SET-5 ✖ | Pre-prompt before the notification permission dialog | Raises grant rates among strangers; this app has one user, who granted it (D-12). |
| PL-10 ✅ | *Up next* when the queue is empty | Already built with PL-9 — the link is drawn at zero and the queue's empty state carries *Browse your shows*. The row was out of date. |

### 4.2 Where to pick this up

The tables above say what is left. This says what to do on the next morning of work, and why in
that order. Nothing here is a new finding; it is the reading order for the ones already listed.

**Do these two before writing any more feature code.** Both are debts the branch has been carrying
and both get more expensive the longer the branch runs.

1. **The device pass.** Not one item on this branch has been seen on the Fold 7 or the Watch Ultra 2
   — every phase's acceptance bar in §6 asks for one and none has happened. Three things are
   entirely unverified by anything a JVM can run: **W-1**'s thumb and hint (Robolectric rendered
   them; a round 45 mm screen is what decides whether the sentence fits under the bar without
   pushing the times off it), **W-4**'s swipe (a page turn on a round screen shares an edge with the
   system's swipe-to-dismiss, and whether the two feel distinct is a thing a wrist decides, not a
   test) and **PL-2**'s notification buttons (a lock screen, a headset and, if there is one to hand,
   a car). The `install_on_devices` skill puts both APKs on both devices from one build, which is
   the only way they may be installed — see the pairing constraint in `CLAUDE.md`.
2. **Read the `gradle/verification-metadata.xml` diff.** It has been waiting since `:feature:listen`
   landed and has grown since: the Firebase and Crashlytics entries, `androidx.collection:collection-jvm`
   and `androidx.profileinstaller`. `docs/DEPENDENCY_VERIFICATION.md` asks for a human, and no human
   has read it. This is the one item on the list that a person other than the author cannot do
   later.

**Then P3, in this order** (D-13, and one step moved on 8 September — see the note under 5).

3. **W-4 — done, 8 September.** The watch screen is two pages: the transport on one, every episode
   list on the other. **W-3** and **W-5** came with it, exactly as this step predicted they would —
   both were in files it opened. That is the end of the watch as far as this plan is concerned;
   everything below is the phone's or the build's.
4. **SYS-3 — done, 8 September.** Four `<shortcut>` entries, and the estimate was right about the
   size and wrong about the shape: the intents did *not* already exist. `EXTRA_OPEN_PLAYER` opens
   the sheet but starts nothing, so *Resume* needed a way to say "carry on" that could not go stale
   — `PlaybackConnection.play()`, which leaves a running player alone where reading the state and
   then toggling would have paused it. It also turned the cold-start queue restore's read-then-set
   guard into a lock: two callers arriving together used to load the queue twice, which was harmless
   while both loaded it paused and stopped being harmless the moment one of them pressed play
   afterwards. DS-5 followed it the same day, with NAV-7 in front of it — see step 5.
5. **DS-5 — done, 8 September**, with **NAV-7** immediately before it as §5.1 said it should be.
   114 goldens, three renderings of each state, every one of them recorded from the component's or
   the screen's own `@Preview` — which is what collapsed this item's two halves into one and what
   makes the next state cost a preview and a line. Two things were learnt that are not about
   design. Robolectric renders text with a Skia built for the machine it runs on, so the comparison
   carries a small deliberate tolerance and `docs/SCREENSHOT_TESTS.md` says what to do if CI still
   disagrees. And the suite lied once: a re-record with unchanged inputs was answered from Gradle's
   cache, reporting success having written no pixel, so recording is now never up to date and the
   goldens are declared as task inputs. **This is what NAV-3 and PL-11 were waiting for**: those two
   rearrange every screen at a second size, and there is now a picture of what every screen looked
   like before they did.
6. **NAV-3 — done, 8 September.** `showBackButton` has its caller. The estimate was right about
   the size and wrong about where the difficulty would be: the scaffold and the nested graph were
   the easy half, and the two bugs worth the day were both a fact being claimed where it is not
   true — a library row left washed on a folded phone after the show beside it had been backed out
   of, and a tap on the highlighted row throwing away the screen it was pointing at, because
   `launchSingleTop` cannot reuse an entry the `popUpTo` beside it has just removed. Both are in
   `docs/reports/2026-09-08-nav-3-two-pane-library.md` with the three decisions the plan left open.
   **PL-11** is the other half of this step and is untouched: the expanded player still draws its
   hero at full width on the inner display.
7. **SYS-2** — the Glance widget. *Continue listening* is now a real query rather than something to
   invent, because `:feature:listen` already asks it; the widget is that shelf and a transport row.
   It also gets easier for being later: DS-5's screenshots and the Fold work will have settled what
   a *Continue listening* row looks like at a second size before the widget has to draw one.
8. **ADD-3**, **MOM-2**, **DS-8** — independent, and each fine to take on the day its screen is open
   for another reason. **DS-8 is now an attribute and a comment** rather than a day of mirroring, so
   it can be swept up with anything that touches the manifest.
9. **The small, decided ones** — **NAV-5**, **LIB-4**, **ADD-5** — each one an S with the design
   question already answered in §5.1, and each fine to take on the day its screen is open.
   **NAV-7 has gone**, taken on 8 September in the hour before DS-5 for the reason this step gave:
   changing every top-level app bar after the screenshots are recorded means recording them twice.

**A note for whoever does DS-3's neighbour.** `EpisodeRow.onLongClick` and `longClickLabel` are the
last limb of the multi-selection DS-3 removed: no screen passes either. They were left because a
labelled long-press is an accessibility action rather than a mark on the screen, and because the
next feature that wants a row context menu will want exactly them. **LIB-4 (D-8) is not that
feature** — it puts a context menu on a *show tile* in the library grid, and `EpisodeRow` is a row of
episodes — but it does decide the gesture: a press released in place opens a menu, a press that
moves reorders. If an episode row ever wants a menu it now has a rule to copy and these two
parameters to do it with. That is a reprieve, not a caller; the note stands, and the pair should go
the way of `SelectionToolbar` if the next audit still finds nothing passing them.

---

## 5. Decisions

> **D-1 to D-4 were the decisions to make before P1, and all four are decided and implemented.**
> D-1: the row tap opens the episode sheet and a
> `PlayPauseButton(Small)` on the row plays it, as recommended. D-2: *Listen* is a fifth tab and the
> start destination, as recommended; Downloads stayed where it was, and NAV-2 has since answered
> that question in its own row — it stays a tab. D-3: the
> bell is folded into the sleep timer as its *End of episode* option, as recommended. D-4: badged,
> as recommended — the player's speed button wears a dot, and says so aloud, whenever the rate in
> force is not the app's.
>
> **§5.1 adds D-5 to D-13, decided on 8 September 2026.** They are the choices left open in the tail
> of the plan — nothing blocking a phase, everything blocking a morning. Three of them are a *no*,
> and the items they close have left §4.1's tables.

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

### 5.1 Decisions of 8 September 2026

D-1 to D-4 were the questions P1 could not start without. These are the ones left in the tail of the
plan: eight items whose rows offered a choice and did not make it, held open by nothing but the fact
that nobody had answered them. They are answered here, each row in §3 and §4.1 restating its
answer, so a morning of work never begins with a decision. The reasoning is written down because
the reason is the part that expires — an answer whose grounds have changed should be reopened, and
the grounds are not recoverable from the choice alone.

Three of the eight are *no*. That is the point of the exercise: a plan whose declined items stay on
the list as unstarted work is a plan that grows a debt it never owed. An item closed by decision is
finished in the same sense as one that shipped.

**D-5 — RTL (DS-8).** Mirror `SwipeActionsRow` with `LayoutDirection`, or declare
`supportsRtl="false"`. **Declare it false.** Today the manifest claims a capability the swipe rows
do not deliver, which is the worst of the three states: a mirrored layout would put the buttons on
one side and the gesture pulling away from them. Mirroring is a day of work for a reading direction
this build will not meet. The attribute carries a comment saying it is scope and not oversight, so
the day someone wants RTL they know the work is `SwipeActionsRow`'s. Both manifests declare it —
`app/src/main/AndroidManifest.xml:40` and `wear/src/main/AndroidManifest.xml:32` — and both flip,
even though the watch has no swipe rows: two halves of one app installed from one build should not
disagree about which directions they support, and neither has ever been read right to left.
Effort M → S.

**D-6 — One app-bar style (NAV-7). Built 8 September.** The pinned small bar, on all five top-level
destinations; Moments gives up its large collapsing title — and so, it turned out, does Listen,
which had taken the large bar for itself since this was written. The component they shared is gone
rather than parked. Five destinations exist now that Listen is one, and one
bar behaving differently among five reads as an oversight rather than as emphasis. Library, Queue
and Downloads already argued for pinned in their own comments, from the same fact: a screen you came
to scroll should not spend a third of its height naming the tab you just tapped. A collapsing bar is
for a detail screen, where the large title is the content's own name.

**D-7 — Reaching Settings (NAV-5).** The gear on every top-level bar, not an overflow. An overflow
would be a menu holding one item on four screens out of five — a container invented to hide the
single thing inside it. The gear is one glyph in a corner that is otherwise empty on those screens.

**D-8 — Removing a show from the grid (LIB-4).** Build the tile context menu; do not settle for
explaining the gap in the Remove dialog's copy. A grid and a list showing the same library should
afford the same things, and copy that explains where a capability *isn't* excuses a design instead
of fixing it. A press that moves still reorders; a press released in place opens the menu, which
holds what the show page's overflow holds. This is also the app's first answer to "how does a menu
open on a pressable item", and §4.2's note about `EpisodeRow.onLongClick` is amended rather than
closed by it: the gesture is settled, but the caller for those two parameters is still missing.

**D-9 — Show notes in the player (PL-3). No.** Written before SHOW-1 existed; the episode sheet now
renders the notes with live links and seeking timestamps. A second rendering inside the player is a
second place to keep a link tappable and a second answer to where notes live, against §7. The player
has no route to the sheet today, so the successor — should reaching notes mid-episode ever bite — is
a door from the player to the sheet that already exists, an S, not a copy of it, an M.

**D-10 — Android Auto (SYS-4). No, and closed.** It was the only row in the plan with neither an
impact nor an effort estimate, which is what an unmade decision looks like in a table. The work is
not a screen but a second information architecture — a browsable tree, its own validation, a
driving-safety review — for a car this user does not listen in. The part that would have been
expensive to retrofit is already done: PL-2's `ChapterAwarePlayer` gives a car's *next* button the
right meaning through the media session, the day one is ever plugged in.

**D-11 — Discovery on an empty search field (ADD-5). Half.** Recent searches yes: a few strings in
DataStore, answering the thing that actually happens here, which is the same show looked up twice
because the first attempt was made on the wrong device. Apple's top charts no: a browsing surface
for a store, a third-party endpoint to keep working, on a screen whose job in this app is to find a
show already decided on. Effort M → S, and the declined half is written down so the next audit does
not re-propose it as a finding.

**D-12 — Notification pre-prompt (SET-5). No.** A pre-prompt earns its place by lifting grant rates
across a population of strangers guessing what an app will do with a permission. This app has one
user, who wrote it, has granted it, and would meet the extra screen once, on a reinstall, knowing
the answer. SET-3's row into the system notification page is the durable half of the concern and is
built. If the app is ever handed to someone else, this is the first row to reopen.

**D-13 — The order of what is left (§4.2).** The sequence stands, with the Fold moved ahead of the
widget: W-4, SYS-3, DS-5, then NAV-3 and PL-11, then SYS-2, then the independents. W-4, SYS-3, DS-5
and NAV-3 are done, NAV-7 with DS-5; **PL-11 — the wide player, the other half of the Fold — is
where this picks up.** The earlier order
took the cheaper surface first; the correction is that the inner display is the one surface this app
has never used, on the device in the pocket every day, while a home-screen widget is a surface its
user may never look at. Between two pieces of similar size, the one that pays out daily goes first.
DS-5 stays ahead of both, because a screenshot suite is what makes rearranging every screen at a
second size reviewable rather than something to be spotted by eye — and NAV-7 (D-6) should land
before DS-5 for the same reason in reverse: changing every top-level app bar after the screenshots
are recorded means recording them twice.

**Unchanged, and worth restating: the two things that come before any of it.** The device pass and
the `gradle/verification-metadata.xml` diff, as §4.2 lists them. Neither is a decision. The first is
the acceptance bar §6 sets for every phase and has never once been run; the second is the only item
in this plan that a person other than the author cannot do later. The branch merges after the device
pass, not before — everything above is unverified on hardware until then.

---

## 6. How to validate

- **Previews:** every screen gets `ThemePreviews` and `FontScalePreviews`; a preview at 200 % is
  where most of A11Y-1 will be found without a device.
- **Screenshot tests (DS-5): built.** Roborazzi over the design-system components and each screen
  state, in light, dark and 200 % text — three renderings rather than the four this line asked for,
  because dark at 200 % is a second copy of a failure the third already shows. `testDebugUnitTest`
  verifies them; `docs/SCREENSHOT_TESTS.md` says how to record and what not to do with the
  tolerance.
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
