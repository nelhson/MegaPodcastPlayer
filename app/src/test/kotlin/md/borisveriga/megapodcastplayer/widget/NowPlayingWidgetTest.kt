package md.borisveriga.megapodcastplayer.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.testing.unit.hasRunCallbackClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasContentDescriptionEqualTo
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Tests for what [WidgetBody] draws.
 *
 * What [WidgetSnapshotTest] pins is which snapshot a state produces; this pins the tree each
 * snapshot produces, which is a separate claim and the one a user sees. The two that matter are the
 * transport changing shape — three buttons when an episode is loaded, one when it is merely next —
 * and every button reaching the callback it is supposed to reach, because an `ActionCallback` is
 * wired by class name and nothing else checks that the name is the right one.
 *
 * Rendered with no artwork anywhere. A cover is fetched over the network by the composition itself,
 * and a unit test that waited for one would be a unit test that needed a network; a null URL takes
 * the placeholder branch, which is the same branch a show without a cover takes on a device.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NowPlayingWidgetTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val loaded = WidgetSnapshot(
        episode = WidgetEpisode("e1", "Podlodka #492", "Podlodka Podcast", artworkUrl = null),
        isLoaded = true,
        isPlaying = true,
        progressPercent = 25,
    )

    private val resumable = loaded.copy(isLoaded = false, isPlaying = false)

    @Test
    fun `a loaded episode is named, with its show`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(FullSize)
        provideComposable { WidgetBody(loaded) }

        onNode(hasText("Podlodka #492")).assertExists()
        onNode(hasText("Podlodka Podcast")).assertExists()
    }

    @Test
    fun `a playing episode offers pause, and skips in both directions`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(FullSize)
            provideComposable { WidgetBody(loaded) }

            onNode(hasContentDescriptionEqualTo("Pause"))
                .assert(hasRunCallbackClickAction<TogglePlayPauseAction>()) { "pause toggles" }
            onNode(hasContentDescriptionEqualTo("Skip back by your skip interval"))
                .assert(hasRunCallbackClickAction<SkipBackAction>()) { "skip back skips back" }
            onNode(hasContentDescriptionEqualTo("Skip ahead by your skip interval"))
                .assert(hasRunCallbackClickAction<SkipForwardAction>()) { "skip ahead skips ahead" }
        }

    @Test
    fun `a paused episode offers play rather than pause`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(FullSize)
        provideComposable { WidgetBody(loaded.copy(isPlaying = false)) }

        onNode(hasContentDescriptionEqualTo("Play")).assertExists()
        onNode(hasContentDescriptionEqualTo("Pause")).assertDoesNotExist()
    }

    /**
     * The decision `WidgetSnapshot.isLoaded` exists for. Skipping forward in something that is not
     * in the player would move nothing, so the buttons that would do it are not drawn at all.
     */
    @Test
    fun `an episode that is only next offers one button, and it resumes`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(FullSize)
            provideComposable { WidgetBody(resumable) }

            onNode(hasContentDescriptionEqualTo("Resume listening"))
                .assert(hasRunCallbackClickAction<ResumeAction>()) { "resume carries on" }
            onNode(hasContentDescriptionEqualTo("Skip back by your skip interval"))
                .assertDoesNotExist()
            onNode(hasContentDescriptionEqualTo("Skip ahead by your skip interval"))
                .assertDoesNotExist()
        }

    @Test
    fun `with nothing to carry on with the widget says so and names no episode`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(FullSize)
            provideComposable { WidgetBody(WidgetSnapshot()) }

            onNode(hasText("Nothing to carry on with")).assertExists()
            onNode(hasContentDescriptionEqualTo("Resume listening")).assertDoesNotExist()
            onNode(hasContentDescriptionEqualTo("Play")).assertDoesNotExist()
        }

    @Test
    fun `each shelf cover plays its own episode`() = runGlanceAppWidgetUnitTest {
        // By id carried on the action, not by position: the shelf can reorder between the draw and
        // the press, and a position would then start the wrong episode.
        setContext(context)
        setAppWidgetSize(FullSize)
        provideComposable {
            WidgetBody(
                loaded.copy(
                    continueListening = listOf(
                        WidgetEpisode("e2", "Podlodka #493", "Podlodka Podcast", null),
                        WidgetEpisode("e3", "Podlodka #494", "Podlodka Podcast", null),
                    ),
                ),
            )
        }

        onNode(hasContentDescriptionEqualTo("Play Podlodka #493")).assert(
            hasRunCallbackClickAction<PlayEpisodeAction>(actionParametersOf(episodeIdKey to "e2")),
        ) { "the second cover plays the second episode" }
        onNode(hasContentDescriptionEqualTo("Play Podlodka #494")).assert(
            hasRunCallbackClickAction<PlayEpisodeAction>(actionParametersOf(episodeIdKey to "e3")),
        ) { "the third cover plays the third episode" }
    }

    /**
     * The compact rendering is the episode and its buttons. The shelf is the first thing dropped
     * when there is no room, because it is the only part of the widget the app itself already
     * shows on its first screen.
     */
    @Test
    fun `the compact size drops the shelf and keeps the transport`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(CompactSize)
        provideComposable {
            WidgetBody(
                loaded.copy(
                    continueListening = listOf(
                        WidgetEpisode("e2", "Podlodka #493", "Podlodka Podcast", null),
                    ),
                ),
            )
        }

        onNode(hasContentDescriptionEqualTo("Pause")).assertExists()
        onNode(hasContentDescriptionEqualTo("Play Podlodka #493")).assertDoesNotExist()
    }

    private companion object {
        /** The tall rendering, where the shelf fits; matches `NowPlayingWidget.FullSize`. */
        val FullSize = DpSize(250.dp, 190.dp)

        /** The short one; matches `NowPlayingWidget.CompactSize`. */
        val CompactSize = DpSize(180.dp, 100.dp)
    }
}
