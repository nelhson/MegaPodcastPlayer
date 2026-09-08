# Screenshot tests

Every component in `:core:designsystem` and every screen state that has a preview is rendered on the
JVM and held against a PNG committed beside the test. A rendering change is then a failing test with
an image diff, rather than something to be noticed by eye three weeks later.

Robolectric does the rendering — the same Robolectric the rest of the unit tests already use, in
native graphics mode, with the app's own Bricolage and Inter files. Roborazzi writes and compares
the image. There is no device and no emulator; `testDebugUnitTest` runs the lot.

## The one idea

**A test renders the component's own `@Preview`.** Nothing in a screenshot test constructs a
podcast, an episode or a UI state of its own: the preview already holds a curated one, chosen by
whoever built the screen, and a second copy here would be a second thing to keep true. That is why
the previews are `internal` rather than `private`, and it is the whole reason the plan asked for
previews and screenshots as one item.

Adding a state to the suite is therefore two steps, and usually one:

1. give it a `@Preview` (with `@ThemePreviews`, and `@FontScalePreviews` when text can break it);
2. add one line to the module's screenshot test.

## Where things are

| | |
|---|---|
| Goldens | `<module>/src/test/screenshots/<name>-<variant>.png` |
| Harness | `:core:testing`'s `captureScreenshot` |
| Suites | `ComponentScreenshotTest` in `:core:designsystem`, `*ScreenshotTest` in each feature |
| Diffs after a failure | `<module>/build/outputs/roborazzi/<name>_compare.png` |

Three variants of each: **light**, **dark**, and **light at 200 % text**. Not four — dark at 200 %
is the one a light/dark × 100/200 % grid would add, and it pays least: a palette and a layout fail
independently, so a row that overflows at 200 % overflows in both schemes.

Recorded at 411×891 dp, mdpi. That is the Fold 7 closed, at one pixel per dp: nothing here tests
subpixel rendering, and it keeps a hundred-odd images to well under a megabyte.

**One window, with one exception.** A suite recorded at every size is a suite nobody re-records, so
the default stands for every golden but those whose *subject* is a second size. Today that is
`expanded-player-wide`, the side-by-side player PL-11 built, recorded at 882×830 dp — the Fold 7
opened out — through a method-level `@Config(qualifiers = SCREENSHOT_QUALIFIERS_WIDE)`. A screen
that merely reflows at a second size does not qualify; one that becomes a different layout does.

## Recording

```
./gradlew.bat testDebugUnitTest -Pmegapodcastplayer.screenshots.record
```

Without the flag the build **verifies**, which is the point: a golden nobody compares against is a
file, not a test.

Then **look at the images before committing them**, the same discipline
`docs/DEPENDENCY_VERIFICATION.md` asks for a regenerated checksum. A re-recorded golden is a design
change being accepted. If it was not meant, the diff is the only place that will ever say so.

Recording deliberately never comes from Gradle's cache, and the goldens are declared as task inputs
— both because the suite lied once without them, reporting a successful re-record that had changed
no pixel.

## Animations

Captured with animations removed, through the app's own switch for it: the harness sets
`ANIMATOR_DURATION_SCALE` to zero, which is what `rememberReduceMotion` watches. The now-playing
bars, the wavy hairline, the morphing loader and the scrubber's wave all loop forever, and a
screenshot of a loop is a picture of one arbitrary frame of it. So the goldens are the still frame
the app itself draws for someone who has turned animations off — a real rendering, not a frozen
clock.

## When CI disagrees with your laptop

Robolectric rasterises text with a Skia built for the machine it runs on, so antialiasing differs
between a Windows laptop and a Linux runner. The harness allows a small per-pixel colour distance
and a small fraction of changed pixels for exactly that; the constants and the reasoning are in
`Screenshots.kt`.

If CI still disagrees, record on the platform CI runs. Do not widen the tolerance: past a point it
stops being a fact about renderers and starts hiding the regressions the suite exists to find.

**And do not ask a golden whether a control exists.** The same tolerance that absorbs antialiasing
absorbs a small icon: a 24 dp glyph added to an app bar is about 0.16 %% of a 411x891 image, well
under the 1 %% of changed pixels allowed, and NAV-5 put a gear on four bars without failing a single
golden. Whether a button is there, and whether it reaches its handler, is a behaviour assertion.
What a golden is for is what a screen *looks like* once it is.

## What is not covered

- **Dialogs and bottom sheets.** They compose into their own window, so the capture — which takes
  the bounds of the content under test — would come back empty. `NoteDialog` is the one component
  with a preview and no golden.
- **Anything that needs a gesture.** `SwipeActionsRow` at rest is a row; the states worth seeing are
  the ones a finger produces, and those belong to its behaviour tests.
- **The watch.** `:wear` draws with Wear Compose on a round screen, and W-1 and W-4 have already
  said what that needs is a wrist, not an image.
