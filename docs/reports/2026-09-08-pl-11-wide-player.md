# PL-11 — the wide player

*8 September 2026. What was built for PL-11 of `docs/reports/2026-09-07-ux-design-plan.md`, the
second half of the Fold pair D-13 scheduled with NAV-3, and the three decisions taken while
building it that the plan did not settle.*

---

## What it is

The expanded player has two shapes now, and the same two blocks make both. What the episode *is* —
the artwork, the title, the show, the chapter — is one block; what the user *does* — scrub, play,
skip, sleep, mark, open the queue — is the other.

Stacked, which is every phone held upright, nothing has changed: the episode at the top where the
artwork lands, the controls on their fading strip at the bottom within reach of a thumb. The three
`expanded-player` goldens are byte-identical to the ones DS-5 recorded, which is the evidence.

Side by side, on a window at or past Material 3's expanded width breakpoint: the artwork takes the
leading half and everything with a word or a button in it takes the trailing one. The artwork is
centred in its half rather than hung from the header, because there is nothing under it to leave
room for.

And in both shapes the hero is now capped by the *shorter* side of the room it is given, not just
the wider one. That is the half of this item that matters on a device nobody thought about: a phone
laid on its side is over the breakpoint too, and 72 % of half of 891 dp is a 320 dp square in a
411 dp window.

Three files carry it:

- `feature/player/ExpandedPlayer.kt` — `TallPlayer`, `WidePlayer`, and `expandedPlayerLayout`.
- `feature/player/PlayerSheet.kt` — one call to that function, and the artwork's travel.
- `core/testing/Screenshots.kt` — the second recording size, and why there is only one.

## Three decisions the plan left open

**D-17 — The titles go with the controls, not with the artwork.** The plan's row says "lay artwork
and controls side by side" and does not say where the words go. Putting the title under the artwork
would have kept the stacked layout's pairing and cost a measurement: the artwork is the sheet's own
travelling copy, positioned by arithmetic rather than by the layout, and centring *artwork plus an
unknown number of title lines* in a half is not something arithmetic can do a frame ahead of the
finger. Putting the title at the top of the control panel needs no measurement, gives the panel a
heading over its body the way a page has one, and leaves the artwork alone in a half where its
position is `(paneHeight - heroSize) / 2` and nothing else. The gain is not only cheapness: the
title is now the same distance from the scrubber in both shapes, so the block a user reads has not
been taken apart, only moved.

**D-18 — The control panel is solid, and the seam is hard.** The stacked shape's strip fades in from
transparent, and the reason is written above it: a strip stops in the middle of a page, and a hard
horizontal line there reads as a floating card. That reasoning does not carry over. An edge that
runs the full height of the window is not a card, it is a screen divided in two, which is what this
is. So the panel is `surfaceContainerHigh` at full height with no gradient, and the blurred cover
behind it shows on the artwork's half alone. Both spellings exist for the same reason — cover art is
arbitrary third-party imagery and the contrast of a timecode against it cannot be reasoned about —
and they differ only in where they stop. *The one thing this decision cannot answer from a laptop is
whether the seam is beautiful.* The goldens do not show it, because a preview has no cover to blur;
the device pass is where it will be seen for the first time.

**D-19 — Width alone chooses the shape; height only sizes the artwork.** The tempting rule is "side
by side when the window is too short to stack", which is about shape rather than width and would
catch a 731 dp landscape phone that the breakpoint does not. It was not taken. The plan says the
expanded width class, the breakpoint is the number every other adaptive surface in this app already
turns at, and a second rule about shape would be a second thing to explain when a window is on the
wrong side of it. What saves the medium-width landscape phone is the *other* half of this item: at
411 dp of height the hero caps to 160 dp, the column keeps its stacked layout, and it scrolls. That
is a worse screen than a two-pane one would be, and it is a screen rather than a bug.

## What the height cap is worth, in numbers

| Window | Shape | Hero was | Hero is | Capped by |
|---|---|---|---|---|
| 411×891 — Fold 7 folded | stacked | 296 | 296 | width |
| 882×830 — Fold 7 open | side by side | 635 | 318 | width, of its half |
| 891×411 — a phone on its side | side by side | 641 | 160 | height |
| 411×520 — a small phone | stacked | 296 | 209 | height |

The second row is the one the plan's title names. The third is the one it did not: before this,
turning any phone with a 891 dp long edge on its side asked for a square wider than the window was
tall, and got a player that was artwork and nothing else until it was scrolled.

## Tests

- `ExpandedPlayerLayoutTest` (`:feature:player`) — nine cases against `expandedPlayerLayout`, which
  is the whole decision as arithmetic: the two Fold windows, a phone on its side, a short narrow
  one, both sides of the breakpoint, and a window shorter than its own header, which is not a real
  window but is every height a split-screen drag passes through on its way down. That last one is
  why the pane height is coerced: `Modifier.size` refuses a negative number.
- `PlayerSheetTest` (`:feature:player`) — three cases at `w882dp-h830dp`, on the rendering rather
  than the arithmetic: both blocks past the halfway mark, the last row of the panel clear of the
  bottom edge (centred, not pinned), and every control still present. A layout swap that drew the
  wide shape with an empty half and the controls still stacked under it would pass every test in
  the file above and fail these.
- Three new goldens: `expanded-player-wide`, in the usual three renderings, recorded at 882×830 dp.
  The first goldens in the suite at a second size; `docs/SCREENSHOT_TESTS.md` now says when that is
  allowed, which is when a screen becomes a *different layout* rather than merely reflowing.

**What has no golden.** The seam, for the reason D-18 gives — the preview has no artwork to blur, so
the image shows two halves of the same colour, and what it is actually proving is the arrangement.
And every intermediate state of the travel: the artwork's path from the collapsed bar's leading edge
to the middle of a half is one `lerp` per axis and is pinned by its two ends, as it was before.

## Not done here

**The device pass.** §5 of the plan asks for one before any of this merges, and PL-11 is the item
that most needs it: it is the only change in the plan whose subject is a window this machine cannot
show. What to look at, in order — the seam between the two halves with real cover art behind it;
whether the artwork at 318 dp looks like a cover or like a stamp in a lot of air; and the travel,
opening and closing the sheet on the inner display, where the artwork now flies to a different place
than it did folded.

**The queue on a wide window.** Untouched, and NAV-3's report already said why the pane scaffold has
nothing to hold there. It is a single list; a wide window gives it a wider list.

With this, D-13's Fold pair is done and **SYS-2, the Glance widget, is what P3 picks up next.**
