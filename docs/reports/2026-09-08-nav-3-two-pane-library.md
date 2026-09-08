# NAV-3 — the open Fold

*8 September 2026. What was built for NAV-3 of `docs/reports/2026-09-07-ux-design-plan.md`, and the
three decisions taken while building it that the plan did not settle.*

---

## What it is

The Library tab is a `NavigableListDetailPaneScaffold`. Folded, it is exactly what it was: the
library, and a show pushed over it. Opened out, the show stands beside the library it was picked
from, and the row it was picked from is washed so the two panes agree about what is on screen.

`PodcastDetailRoute.showBackButton` — written for a caller that never came, and named in the plan's
row as the evidence this was always the intent — now has one. The arrow is drawn only when the list
pane is *not* on screen, because an arrow pointing back to a list already visible beside it points
at nothing.

Three files carry the whole of it:

- `app/ui/LibraryListDetail.kt` — the scaffold, the detail pane's own graph, and the placeholder.
- `app/navigation/DetailPaneNavigation.kt` — the four things that graph does to itself.
- `core/designsystem/component/ShowRow.kt` and `ShowTile.kt` — one parameter each, `isSelected`.

## Three decisions the plan left open

**D-14 — A second `NavHost` in the detail pane, not a `contentKey` on the pane navigator.** The
adaptive navigator will carry a key of any type through a pane change, and using it for the
podcast id would have been fewer lines. It would also have meant `PodcastDetailViewModel` no longer
receiving its `podcastId` the way it says it does — out of a `SavedStateHandle` filled by a
navigation argument — and the call site reaching for `CreationExtras` to fake one. Giving the pane a
graph of its own keeps `PodcastDetailRoute` the same call in both places it is now made, and leaves
the view model scoped, saved and cleared by the machinery that already does it. The graph has two
destinations: `Route.NoShowSelected` at the bottom, and at most one `Route.PodcastDetail` above it.

**D-15 — The outer `Route.PodcastDetail` stays, and stays full-screen at every width.** A show
opened from a notification, a search result or a shared link is still a push over everything,
including on the inner display. The temptation was to route those into the pane too, so there is one
way a show is shown. It is the wrong tidiness: the list a pane would put beside such a show is the
library, and the library is not where the user came from — search is, or a notification is. Backing
out of a show found by search should land back in the results, which is a promise `:feature:search`
already makes and the pane cannot keep. So the app has two ways to show a show, and the difference
between them is which list the user was reading a moment ago.

**D-16 — The system back gesture and the back arrow are allowed to differ, and the difference is
invisible.** `NavigableListDetailPaneScaffold` registers its own predictive-back handler, which
speaks to the pane navigator directly and never reaches the screen's `onBack`. So the arrow does
what the gesture does — close the pane — and nothing more: the show stays loaded either way, which
is what makes re-opening it cheap and what stops the two gestures meaning two things.

The one case the arrow handles alone is the case it is not drawn for. When both panes are open there
is no pane to close, so `navigateBack()` returns false, and the only caller left is a show that has
just removed itself. That one clears the pane back to its placeholder — without it, the pane sits on
a screen whose show is gone, and `PodcastDetailRoute`'s "the show disappeared, leave" effect asks to
go back forever.

## Two bugs the writing found, both about a fact being claimed where it is not true

**A row was left marked on a screen with nothing beside it.** The first spelling asked
`scaffoldValue[List] == Expanded` for whether to highlight. On a folded phone that is true whenever
the list is the pane on screen — including after a show has been backed out of, when the detail pane
is merely *hidden* and still holds it — so the library came back with a row washed for a show
nobody was looking at. The question the highlight is actually asking is whether *both* panes are
visible, and it now asks that.

**Re-tapping the highlighted row rebuilt the show beside it.** `launchSingleTop` does not cover this
one: it reuses an entry only when the destination is already on top, and the `popUpTo` that keeps
the pane to one show at a time has just taken it off. So the tap threw away the screen it was
pointing at and built a fresh one, losing its scroll position, its open sheet and its view model.
The guard is the same early return `navigateToTopLevel` makes for the tab the user is already
standing on, and `DetailPaneNavigationTest` pins it by identity: the same back-stack entry, not an
equal one.

## The bug the device found

The device pass §5 asked for happened, and the first tap in the library killed the app:
`IllegalStateException: You must call setGraph() before calling getGraph()`, out of
`openShowInDetailPane`.

`AnimatedPane` draws a hidden pane through `AnimatedVisibility`, and `AnimatedVisibility` does not
compose what it is not showing. Folded, the detail pane is `Hidden` — so at the moment a row is
tapped the pane's `NavHost` has never run, and a `NavHost` is the thing that sets the graph on its
controller. The tap reached a controller that had none. Opened out it never happened once, because
both panes are composed before there is anything to tap: the bug was invisible on exactly the
display this item was written for, and fatal on the one the phone is in most of the time.

The fix is to stop making the pane responsible for something the *list* uses.
`rememberDetailPaneGraph` builds the graph and attaches it in `LibraryListDetail`, where both panes
are, so the pane has a back stack from the first frame whether or not it is on screen; the pane's
`NavHost` is then handed that same `NavGraph` instance rather than a builder, because setting an
*equal* graph refreshes the destinations in place while setting a different one pops everything off
first — the difference between the pane keeping the show just opened on it and losing it the instant
it becomes visible. Two orderings the KDoc carries: the view model store has to be installed before
the graph, since `setViewModelStore` refuses once the back stack is non-empty and setting the graph
is what fills it, and it has to be the store the pane's own `NavHost` would install — which it is,
because nothing between the two provides a new `LocalViewModelStoreOwner`.

It also settles something the crash was hiding. Deferring the navigation until the pane appeared —
the other way out — would have had the pane slide in on the placeholder and cross-fade to the show a
frame later, on every tap, on the folded phone. A graph attached early means the pane is already
showing the right thing when it arrives.

## The mark, and why it is a wash

`ShowRow` and `ShowTile` gained `isSelected`, which is set only where a detail pane exists — never
in search results, never on a folded phone. It draws `secondaryContainer` behind the row, animated
with the same fade `EpisodeRow`'s now-playing wash uses, and announces itself with Compose's own
`Selected` property rather than with a sentence appended to the state description: TalkBack already
has a word for a list item whose detail is open, and §7's rule is one mark per fact.

`selected` is set only when true. `selected = false` would have every row in every list — the search
results, the folded library — announce that it is not selected, which is an answer to a question
those screens never pose.

The tile paints its wash as a *shaped background* rather than a clip plus a fill. The first spelling
clipped the tile to the artwork radius, which quietly trimmed the new-episode badge and the
downloaded mark out of its corners — caught by the screenshot suite, on the two goldens of the
*unselected* tile, which is exactly the job DS-5 was taken before this item to do.

`ColorContrastTest` gained the two pairs the wash creates: `onSurface` and `onSurfaceVariant` on
`secondaryContainer`, in both schemes. The row keeps its ordinary content colours rather than
switching to `onSecondaryContainer` — selection moves, and text that changed colour as the highlight
passed would read as two different rows — so both have to survive the container, and both do.

## Tests

- `DetailPaneNavigationTest` (`:app`) — eight cases against the pane's real graph with empty
  screens, the way `TopLevelNavigationTest` does for the app's: the placeholder starts, a show sits
  over it, a second show replaces rather than stacks, re-opening the open show changes nothing *and
  keeps its entry*, clearing returns to the placeholder, and clearing an already-empty pane leaves
  it empty. Whether the pane is composed is a parameter of the fixture rather than an assumption,
  which is what the last two need: a show opens on a pane that has never been on screen, and the
  pane then arrives showing that show rather than the placeholder.
- `ShowRowTest` / `ShowTileTest` (`:core:designsystem`) — selected announces itself; unselected says
  nothing at all.
- `LibraryScreenTest` (`:feature:library`) — one row marked and only one, in both layouts, and
  nothing marked when no pane is beside the list.
- Nine new goldens: `show-row-selected`, `show-tile-selected`, `library-selected`, and
  `podcast-detail-in-pane` — the show without its arrow, which is NAV-3's one visible change to that
  screen. Three renderings each, all from the component's or screen's own `@Preview`.

**What has no golden.** The two-pane split itself. The layout is `ListDetailPaneScaffold`'s, not
this app's, and a golden of it would be a picture of androidx; the screenshot suite records at one
window size by design. What the pane arrangement looks like on the inner display is for the device
pass, which §5's last paragraph says has to happen before any of this merges. It has now been run —
on the Fold 7, folded — and what it found is above.

## Not done here

**PL-11 — the wide player** is the next item and is untouched: the expanded player still draws its
hero artwork at full width on the inner display. NAV-3 and PL-11 were scheduled as a pair (D-13) and
remain one; this is the first half.

The plan's row also names Queue and Player as later candidates for the same treatment. Neither is
taken: the queue is a single list with no detail to put beside it, and the player is a sheet rather
than a destination — the pane scaffold has nothing to hold there.
