# MOM-2 — finding one moment among ninety

*8 September 2026. What was built for MOM-2 of `docs/reports/2026-09-07-ux-design-plan.md` — the
last scheduled item in the plan — and the four decisions taken while building it that the plan did
not settle.*

---

## What it is

The moments screen keeps its default: everything, newest first, across every show, because the
reason to open it is usually the thing marked ten minutes ago. What MOM-2 adds is a way out of that
default once the list has grown past the point where scrolling is an answer.

- **A search field**, matching the note and the episode title.
- **A show chip**, listing the shows that actually have moments, with their counts.
- **A group-by-show toggle**, which gathers the list under headings.
- **A swipe on every row**, revealing *Edit note* and *Delete* — the note was three taps through an
  overflow menu, which is the sentence the plan's row ends on.

The controls appear only above eight moments. A search field over three of them costs more room
than the list it filters, which is the judgement the library screen already makes about its own.

## Four decisions the plan left open

**D-28 — The search matches the note and the episode, and deliberately not the show.** The show has
a control of its own two chips away. Letting a typed word do both jobs would mean a search for
"Radio" returning every moment in a show called *Radio-T* alongside the one note that mentions
radios — the user asked one question and got two answers mixed together. The note is matched because
it is the only thing in this app the user wrote and therefore the first thing they would type a
word from; the episode title is matched because it is all a moment *without* a note has. A test
pins the exclusion, since it is the kind of thing a later "improvement" would helpfully add back.

**D-29 — The swipe reveals two buttons and commits nothing.** Every other swipe row in this app has
a full-swipe action — queue, download now, remove. This one has none, and that is the point: the
thing on the other side of a committed swipe here would be deleting the only piece of writing in the
app that is the user's own. `MomentsRepository` carries its own Markdown export, moments are in the
backup, and the whole feature exists because a note typed at 12:23 of an episode cannot be
re-earned. A gesture that destroys one on a fast pull is exactly the gesture not to have. Both
buttons need a tap after the pull.

**D-30 — The overflow menu stays, carrying everything the swipe does.** A gesture is not
discoverable and a menu is; someone who has never swiped a row here should still be able to find
every action. The duplication costs two menu entries. Sharing is the one action that is *only* in
the menu, because MOM-2 is about making the note one gesture away and a third swipe button would
undo that.

**D-31 — None of it is persisted.** The library persists its sort and its filter; this does not. A
moments list is opened to find one thing, and a narrowing that survived until next time would greet
the user with most of their moments missing and nothing on screen saying why. The state lives in the
view model rather than the composition, so it survives a rotation and a fold — which is the one kind
of "next time" a narrowing should survive.

## The bug the existing tests found

The first spelling of `MomentsUiState` had `isEmpty` ask a *count* — `savedCount == 0`, where
`savedCount` is how many moments exist before the filter. It read well and it was a trap: five
existing tests that build `MomentsUiState(moments = listOf(…))` and nothing else immediately started
failing, because a state holding rows was reporting itself empty. Any future caller would have
walked into the same hole.

It now asks the filter instead:

```kotlin
val isEmpty get() = !isLoading && moments.isEmpty() && !filter.isNarrowing
val isNarrowedToNothing get() = !isLoading && moments.isEmpty() && filter.isNarrowing
```

Which is also the more honest question. The two empty states are "you have never saved a moment" and
"nothing matches this", they want opposite things done about them, and what actually separates them
is whether a filter is on — not a second number that has to be kept in step with the first.
`savedCount` survives for one job only: deciding whether the controls are worth drawing.

## Tests

- `MomentsFilterTest` (`:core:model`) — twelve cases on the pure narrowing and grouping: the note
  matched, the episode matched, the show *not* matched (D-28), case and surrounding space ignored
  because a phone keyboard supplies both, a show and a query narrowing together rather than
  either-or, the menu built most-moments-first, and groups appearing in first-appearance order with
  each show's own order kept inside — not alphabetical, because the list was newest first and
  sorting either would move the thing the user came for.
- `MomentsViewModelTest` (`:feature:moments`) — seven new cases: the threshold in both directions,
  a query narrowing without changing what exists, a query that matches nothing being a *different*
  empty, the show menu keeping the other shows after one is chosen (a menu that lost them would be
  a menu with no way back), grouping built only when asked for — the screen redraws whenever a note
  is saved — and grouping applied after narrowing, so a heading cannot outlive its last moment.
- `MomentsScreenTest` (`:feature:moments`) — eight new cases: the controls absent below the
  threshold and present above it, typing reported, the menu listing shows with counts, the chip
  naming the chosen show rather than the word "Show", the toggle reported, the right empty state,
  and — the one worth having — the row carrying *Edit note* and *Delete* as custom accessibility
  actions. MOM-2 exists because editing took three taps through a menu; for TalkBack it took the
  same three, and the swipe's actions are the shorter path for both.
- Three new goldens: `moments-grouped`, in the usual three renderings, from a preview with enough
  moments across two shows to draw the controls and the headings. The existing `moments` and
  `moments-empty` goldens are unchanged, because the flat preview stays below the threshold.

## Not done here

**The device pass**, for the two things a Robolectric test cannot judge: whether the swipe competes
with the list's own scroll on a real finger, and whether the section headings read as structure or
as clutter on a list of ninety.

**Sorting.** The plan's row asks for filtering, searching, grouping and a swipe, and not for an
order. Newest first is the order this screen is for; a "sort by show" would be the grouping toggle
under another name, and a "sort by episode position" would order a cross-show list by a number that
means nothing across shows.

With this, **every scheduled item in the plan is done**: 58 of 73, 3 decided against, 12 unscheduled
findings left, and the device pass and the `verification-metadata.xml` review are what stand between
this branch and `master`.
