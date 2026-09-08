# The unscheduled tail — LIB-4, SHOW-7, DL-3, ADD-4, ADD-5, SET-4, SET-6, COPY-2, COPY-4

*8 September 2026, branch `ux-design-plan`. The last nine items in
`docs/reports/2026-09-07-ux-design-plan.md`, taken in one pass. With them the plan is finished:
70 of its 73 items are built, 3 are decided against, none is left.*

Nine small findings that no phase claimed, each one belonging to a different screen, all estimated
S and all in fact S — with one exception that was S in the editor and half a day in the debugger.
They are reported together because they were taken together, ordered by the screen they belong to
rather than by id, so each screen was opened once.

---

## 1. LIB-4 — a context menu on a library tile, and the bug under it

**The finding.** The grid had no way to remove a show. The list has its swipe and the show's page
has its overflow; a library kept as covers had neither, and D-8 chose a context menu over the
alternative of explaining the gap in the removal dialog's copy — "a grid and a list showing the
same library should afford the same things, and copy that explains where a capability *isn't*
excuses a design instead of fixing it."

**What the row asked for, and what it got.** A press that moves still reorders; a press released in
place opens a menu. Both halves are one gesture, told apart by *distance* rather than by time:
accumulated travel against the same `touchSlop` that separates a tap from a drag everywhere else.
Time would have been the wrong axis — a finger inevitably drifts during half a second of holding,
and a hold that visibly moved the tile must never be answered with a menu.

**The menu holds one entry.** D-8 said it should hold what the show page's overflow holds, and it
holds a third of that. The reasoning is written up as D-23 in the plan; the short version is that
the other two entries need what the library has not got. A rebuild must clear the show's downloads
*before* it destroys the rows that name them, which means reading the show's episode list — a thing
the library does not observe and should not start observing for a menu — and the settings sheet is
the show's page in miniature, built on a per-show settings flow and the app-wide defaults it defers
to. Copying either into a second feature module is the second place to maintain that this plan
keeps declining to build. What D-8 was actually complaining about is answered in full: the grid and
the list can now do the same things to a show, because the list's swipe holds exactly one thing too.

**And then the interesting part.** The menu did not open. The gesture fired, the state was set, and
nothing appeared — and the reason turned out to be a fault that every list in this app has had
since the reorder was generalised.

`detectDragGesturesAfterLongPress` consumes the release on the **main** pass. Pointer events reach
the main pass from the inside out, and a row's or tile's own `clickable` sits *inside* the modifier
the call site passes in. So the click always saw a clean release and always fired: holding a queue
row for half a second and putting it back down opened the episode sheet; holding a library tile
opened the show. Nobody had noticed, because "I picked it up and put it back, and it opened" reads
as a mis-tap rather than as a bug. In the library grid it stopped reading that way, because a menu
that opens behind the show it has just navigated to is not a menu.

The detector is hand-rolled now — `awaitFirstDown`, `awaitLongPressOrCancellation`, then a loop
that consumes on `PointerEventPass.Initial`, which runs outside-in and therefore before any inner
node's main pass. Three consequences, all of them wanted:

- a tap still taps, because a cancelled long press consumes nothing;
- a held release is not a tap, in **every** list, not only the library's (D-25);
- a scroll or a horizontal swipe still wins, because it claims the pointer before the long press
  fires.

One trap on the way, worth writing down: `positionChange()` returns `Offset.Zero` once the change
has been consumed, so the delta has to be read *before* `consume()`. The first version consumed
first and silently stopped reordering anything — with every test still passing except the one that
drove a real drag.

`ReorderableGestureTest` is new and covers what `ReorderableStateTest` structurally cannot: it
needs a real pointer and a real `clickable` on the same node.

**Also:** the drag and the menu are withdrawn by different things. The grid loses its drag whenever
what is on screen is not the stored order — a computed sort, an active filter — and it keeps its
menu through all of it, because removing a show means the same thing in every order. That is what
`enabled` is for on the modifier, and it is tested from both sides.

## 2. SHOW-7 — three things a show's page did not say

Three unrelated complaints in one row.

**"Show more" under a description that fits.** The button was drawn under every description
including the one-liners it had nothing to expand. Whether four lines were enough is a question
only the layout can answer, so it is asked of the layout: `onTextLayout` records
`hasVisualOverflow`, and only while collapsed — reading it expanded would take the button away at
the moment it is the only way back.

*A note for whoever writes the next screenshot-adjacent test.* The obvious test — a long paragraph,
assert the button — does not work. Robolectric's text layout is not the device's, and a paragraph
that wraps to eight lines on the Fold wrapped to one in the test runner, so the test was measuring
the runner's font metrics. The fixture is six hard line breaks instead: lines the publisher wrote
are lines whatever measures them.

**No counts line.** The library's row has said "412 episodes · 2 downloaded" since it was written
and the page *about* the show did not, which is the wrong way round. It is the same two facts in
the same order with the same separator, assembled from the episode list this screen already holds,
and *downloaded* is named only when there is something downloaded.

**No way to hand the show to anybody.** The episode sheet could share an episode; the show could
not be shared at all. The overflow gained *Share show* and *Copy feed link*, both at the top, being
the only two entries in that menu that leave everything exactly as it was. Both carry the feed URL
rather than a web page, because the feed URL is the string that means this show to every other
podcast app — it is what this app's own add field takes and what the moments export already writes
under each show heading. `showShareText` sits next to `episodeShareText` in `:core:model` for that
reason.

Copying needs no confirmation of its own: from Android 13 the system draws one, and minSdk here is
34.

The row also asks for a *website* link. Declined, and written down as D-24 rather than left
unstated: there is no such field on `Podcast`, and adding one is a model field, a column, a mapper,
a line in `RssParser` and another wherever an iTunes result is converted — four modules for a link
most feeds already put in the description the page renders.

## 3. DL-3 — why did that episode disappear?

An episode that was on the device on Monday and gone on Tuesday was removed by one of two rules the
user set once and has not thought about since, and the screen it vanished from said nothing about
either. The storage card now carries one more line under the bar — the keep-limit and the
delete-after-playing rule, whichever are in force — and the line is a door into Settings, where
they are changed. A sentence naming a setting the user then has to go and find is half an answer.

It says something when **neither** rule is on, rather than disappearing: "nothing is deleted
automatically" answers the same question, and a line that came and went with the settings would
make the card's height depend on a preference.

## 4. ADD-4 and ADD-5 — what the search field offers before it is typed into

Taken together because they are the same empty space.

**ADD-4, the clipboard.** A link is on the clipboard because it was copied in another app a moment
ago and the user came here to paste it; offering the paste is shorter than the paste. Read once, on
arrival — the user has just asked for this screen, which is the focus the disclosure guidance wants,
and reading it where it is drawn would announce a clipboard read on every recomposition. The chip
appears only for text `PodcastLinkParser` recognises, so a clipboard holding a shopping list
produces nothing at all.

**ADD-5, recent searches.** D-11 took half of the row: recent searches yes, Apple's top charts no.
A handful of strings in DataStore, most recent first, case-insensitively de-duplicated, capped at
eight.

The part worth stating is *when* a term is recorded. Not on every debounced query — a pause in the
middle of typing "podlodka" is not a search for "podl", and recording what the debounce settles on
would fill the list with the prefixes of one word. It is recorded when a search **led somewhere**:
the keyboard's search key, opening a result's preview, adding one. A link is never recorded; it is
not a search, it is the show, and the show is in the library a moment later.

## 5. SET-4 and SET-6 — what Settings could not say

**SET-6, the default speed.** Since PL-5 the playback rate has been per-show as well as per-app,
and this row still called itself "Playback speed" — which is to say, it named itself as the answer
while quietly not being it for any show that had overridden it. It reads *Default speed* now, and
lists the shows that depart from it. A default that never names its exceptions is
indistinguishable from a setting being ignored.

The list is a join — rates in preferences, titles in the database — so it is assembled in the view
model, alphabetically, and an override whose show has been removed is dropped rather than drawn as
a blank name at a rate. (Settings outlive the library: a removed show leaves its preferences entry
behind.)

**SET-4, About.** Three rows, and the third is the one that mattered.

*Version* is read from the installed package rather than a `BuildConfig`, because this is a feature
module and its `BuildConfig` describes the library's variant, not the APK.

*Crash reporting* states which of two builds this is, rather than offering a switch with nothing
behind it: whether anything is sent was decided by whether `google-services.json` was present when
the APK was built. `CrashReporter` gained an `isReporting` property, which is a better question to
ask than `reporter !is NoOpCrashReporter`.

*Font licences* is the one that was actually wrong rather than merely missing. The OFL requires the
licence text to travel with the fonts, and for a user that means the APK. It lived in
`docs/FONT_LICENSES.md`, which satisfies nobody who has not cloned the repository. The text is now
`core/designsystem/src/main/res/raw/font_licenses.txt`, read verbatim into a dialog, and the doc is
a pointer at it — one copy, not two, because two would drift.

## 6. COPY-2 and COPY-4 — the vocabulary, then the rules

COPY-4 could not be written first. A rules document that describes an app which does not follow it
is a wish list, so COPY-2 went ahead of it.

**COPY-2.** The distinction, settled:

- **Remove** — the thing leaves a *collection* and still exists. A show leaves the library; an
  episode leaves the queue.
- **Delete** — the thing leaves the *device*. A file, a downloaded episode, a moment.

What changed. The show page's overflow said *Delete* for removing a show while the library said
*Remove* for the same act on the same show; it says *Remove show* now. One download was *Delete
download* in the episode sheet and *Remove download* on the swipe two files away; there is one
string now. Everything that deletes audio says so: the downloads screen, its confirmation, its
snackbar, the design system's download button, and both of the watch's.

One thing the row did not ask for and got anyway. The downloads swipe was a single label over two
different acts: on a finished episode it deletes a file, on a running transfer it calls the
transfer off. Both read *Remove*, which is the word that says neither. It is *Delete* and *Cancel
download* now, chosen by the row's own state.

And one outlier the survey turned up: two of the three snackbars naming an episode quoted the
title and the queue's did not. Quoted now — the rule is in the document.

**COPY-4.** `docs/COPY_RULES.md`. Eleven sections: case, naming a control, the remove/delete rule
and how a destructive action asks, quoting titles, numbers and units, the `·` separator and why it
is a resource, the ellipsis, empty states, snackbars, accessibility copy, and what a comment in a
`strings.xml` is for. Every rule is read off the app rather than invented; where the app disagreed
with itself the disagreement is named and the settling is in the same commit. `CLAUDE.md` points at
it from the line that already said strings live in `strings.xml`.

---

## What else changed on the way

**The downloads screenshot golden was showing an empty screen.** The preview sets `downloads`; the
screen draws `sections`. Three goldens had been recorded from a preview whose comment says "one row
per state, because the states are the whole point of this screen" and which rendered none of them.
Fixed by grouping through the same function the view model uses, which is also what stops the
preview and the screen disagreeing again. The four sections are in the goldens now.

**The screenshot suite otherwise moved as expected.** Eleven goldens re-recorded: the podcast
detail's six (a counts line gained, a *Show more* correctly lost), the downloads' three, and the
settings' two (*Default speed*). All eleven were looked at before being kept, as
`docs/SCREENSHOT_TESTS.md` asks. The About section is below the fold in the settings preview and so
is not in a golden; it is covered by three screen tests instead.

## Verification

`detekt`, `lintDebug`, `testDebugUnitTest` and `test` all green.

Two detekt findings were worth the fix rather than the suppression: `SettingsScreen` crossed the
cyclomatic complexity ceiling and gave up its About rows to `AboutRows`, and the hand-rolled
pointer loop had two exits and now has one.

**Still not done, and not an item:** the device pass on the Fold 7 and the Watch Ultra 2, and the
`gradle/verification-metadata.xml` review. Both are in §4.2 of the plan, both need a person, and
neither can be closed from here.
