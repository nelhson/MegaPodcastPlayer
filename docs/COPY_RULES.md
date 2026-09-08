# Copy rules

*Written 8 September 2026 (COPY-4). Not an invention: every rule here is what the app's twenty
`strings.xml` files already do, written down so the next screen matches without re-deriving it from
the last one. Where the codebase disagreed with itself the disagreement is named and settled, and
the settling is a change in the same commit as this file.*

The one rule that is enforced by a tool: **user-facing strings live in each module's
`strings.xml`, never in a Kotlin literal.** Everything below is enforced by reading.

---

## 1. Case

**Sentence case, everywhere.** Buttons, menu entries, section headers, dialog titles, chips,
switches, snackbars. Capitalise the first word and nothing else.

> Add to queue · Delete and reload all episodes · Waiting for Wi-Fi · Has new episodes

Proper nouns keep their own capitals — *Wi-Fi*, *Apple Podcasts*, *YouTube*, *Firebase
Crashlytics*, and the names of shows. Nothing else does; there is no Title Case in this app and a
screen that introduces some will look like it came from a different one.

## 2. Naming what a control does

A label names an **action**, not a state: *Mark as played*, never *Played*. This matters twice
over, because every swipe label is also read out as an accessibility action, where the row has
already said which episode it is talking about and the label is the whole of what is about to
happen.

Keep a swipe label to one or two words — it is drawn under an icon, in a button a finger has to
still be able to read while pulling the row. Menu entries have more room, and use it when the
object is not obvious: the show page's overflow says *Remove show*, because it sits in a menu of
five unlike actions, while the library grid's context menu says *Remove*, because it opened on the
show it is about.

## 3. Destructive actions: *remove* against *delete*

This is the distinction COPY-2 was raised for, and it was genuinely drifting: the library said
*Remove* and the show page said *Delete* for the same act, and one download was *Remove download*
in a sheet and *Delete download* on a swipe.

- **Remove** — the thing leaves a *collection* and continues to exist elsewhere. A show leaves the
  library. An episode leaves the queue.
- **Delete** — the thing leaves the *device*. A file, a downloaded episode, a moment the user
  wrote. There is nothing left afterwards.

Where one gesture does both depending on state, it says which: the downloads swipe reads *Delete*
on a finished episode and *Cancel download* on a transfer, because a transfer has no file to
delete yet.

A destructive action that cannot be offered back **asks first**, and the question:

1. names the object — *Delete "Podlodka #402 – Сети"?*, *Remove Podlodka Podcast?* — never
   *Are you sure?*;
2. counts what is at stake rather than describing it, and says nothing at zero: *3 downloaded
   episodes will be deleted.* is worth reading, *you may lose data* is not;
3. repeats the verb on the confirming button — *Delete*, *Remove*, *Delete and reload* — so the
   button is readable without the title above it. The other button is always *Cancel*.

An action that *can* be offered back does not ask: it happens, and the snackbar carries *Undo*.
Queueing, marking played and deleting a moment work that way; removing a show does not, because
re-subscribing re-fetches the feed and what returns is a different subscription.

## 4. Titles inside a sentence

**Quote an episode title. Do not quote a show title.** An episode title is a sentence and runs
into the words around it; a show title is a name, and quoting it makes the app sound like it is
holding the name at arm's length.

> Deleted "Podlodka #402 – Сети" from this device
> Removed Podlodka Podcast

## 5. Numbers

Digits, always — *3 episodes*, never *three episodes*. Anything counted goes through a `plurals`
resource even where English needs only two forms, because the count is interpolated and a language
with more forms should not need a Kotlin change.

Units follow the platform's own formatting rather than being spelled out: sizes through
`formatBytes` (*148 MB*), durations through `formatDuration` (*1 h 24 min*, *12 min left*),
positions through `formatTimecode` (*12:23*), rates through `formatSpeed` (*1.5x* — a plain `x`,
not `×`, and not translated).

Numerals that sit beside each other in a row use the design system's `type.numeric` style, which
is what keeps a ticking timecode from shuffling sideways.

## 6. The `·` separator

Two facts on one line are joined by a middle dot with a space either side: *412 episodes ·
2 downloaded*, *24 Aug · 1 h 24 min*.

Never joined with a literal in Kotlin. The join is its own string resource — `..._combined`, or
`..._separator` for a value used repeatedly — so a translator can re-punctuate a line that a dot
does not suit:

```xml
<string name="library_counts_combined">%1$s · %2$s</string>
```

A line of three parts is folded through the same two-part resource rather than given a
three-part one, and a part that is zero is left out entirely rather than printed as *0
downloaded*.

## 7. Ellipsis and in-progress copy

One character — `…`, never three dots. Used only for something actually running: *Restoring…*,
*Copying…*. Never for "this opens a dialog"; a row that opens something says so with a chevron, or
by naming what it opens.

## 8. Empty states

Three parts and no more: a title saying what is not there, a description saying what would put
something there, and an action label that does it. An empty state that has nothing to offer omits
the action rather than inventing one.

A filtered list that matches nothing is **not** an empty collection and does not say the same
thing: *No shows match this filter* with *Clear filter*, not *Your library is empty* with *Find a
show*.

## 9. Snackbars

Past tense, and about what actually happened: *Removed Podlodka Podcast*, *Deleted all downloads,
freeing 1.2 GB*. Say the consequence when it is the reason the action was taken — the freed space,
the number of new episodes — and nothing when there is none.

*Undo* is the only action a snackbar ever carries here, and only where the undo is real. A dead
*Undo* beside three live ones teaches the user to stop reading the action.

## 10. Accessibility copy

- A **content description** names an icon-only control by what it does: *More actions*, *Show as
  list*. An icon that repeats adjacent text carries `contentDescription = null` instead.
- A **state description** carries the fact about the thing that changes while its name does not —
  *3 new episodes* on a library row, *Selected* on a chip.
- A **custom action** label is an action label under rule 2 and is the same string the visible
  control uses, so that the spoken app and the drawn app do not use two vocabularies.
- Never announce a bare number. A badge reading *3* is decorative
  (`clearAndSetSemantics {}`) and the row it sits on says *3 new episodes*.

## 11. Comments in `strings.xml`

Every string whose wording is a decision carries a comment saying what the decision was. Not
*"the remove button"* — the name says that — but why it says what it says:

```xml
<!--
    Both rules off: still stated, because "nothing" is an answer to the same question, and a
    line that came and went with the settings would make the card's height depend on a preference.
-->
<string name="downloads_rules_none">Nothing is deleted automatically</string>
```

The comment is what stops the next audit re-deriving the argument, and it is the only place the
reasoning can live: a `strings.xml` has no other room for it.
