package md.borisveriga.megapodcastplayer.feature.player.video

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.media.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Tests for what the video screen's page does besides playing: clearing itself to leave only the
 * picture, and its two ways out.
 *
 * Behaviour, not pictures. A cleared page is a black one, which a golden would guard no better than
 * a comment; what can break is the wiring — that the tap reaches the flag, that buttons which have
 * faded out cannot still be pressed or spoken, that *minimise* and *switch to audio* each reach
 * their own handler rather than each other's, and that Back undoes a cleared page before it leaves.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class VideoScreenControlsTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Whether the screen under test is showing its controls; the test's stand-in for the route. */
    private var controlsVisible by mutableStateOf(true)

    /** Where Back is sent in the composition under test; read to press it and to ask who takes it. */
    private lateinit var backDispatcher: OnBackPressedDispatcher

    /** The video screen on a YouTube episode, holding its own cleared-or-not flag. */
    private fun setScreen(actions: VideoActions = VideoActions(), fullscreen: Boolean = false) {
        composeRule.setContent {
            backDispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            MegaPodcastPlayerTheme {
                VideoScreen(
                    uiState = VideoUiState(
                        playback = PlaybackState(
                            isConnected = true,
                            episodeId = "e1",
                            title = "Episode",
                            showTitle = "A playlist",
                            youTubeVideoId = "niTJ2221aS8",
                            durationMs = 60_000L,
                        ),
                    ),
                    surface = { modifier -> Box(modifier = modifier) },
                    actions = actions,
                    fullscreen = fullscreen,
                    controlsVisible = controlsVisible,
                    onControlsVisibleChange = { controlsVisible = it },
                )
            }
        }
    }

    /** The node whose tap is spoken as [label]; the picture and the cleared page have no text. */
    private fun tapLabelled(label: String) = composeRule.onNode(
        SemanticsMatcher("click label is \"$label\"") { node ->
            node.config.getOrNull(SemanticsActions.OnClick)?.label == label
        },
    )

    @Test
    fun `a tap on the picture clears the page, and a tap on the cleared page brings it back`() {
        setScreen()
        composeRule.onNodeWithContentDescription("Minimise the video").assertIsDisplayed()

        tapLabelled("Hide the controls").performClick()
        composeRule.waitForIdle()
        assertEquals(false, controlsVisible)

        tapLabelled("Show the controls").performClick()
        composeRule.waitForIdle()
        assertEquals(true, controlsVisible)
        composeRule.onNodeWithContentDescription("Minimise the video").assertIsDisplayed()
    }

    @Test
    fun `a cleared page offers nothing to press or to read but the way back`() {
        controlsVisible = false
        setScreen()

        // Faded, not removed, so that the picture does not move — which means the tree has to say
        // they are gone, or a screen reader would walk a row of buttons nobody can see.
        composeRule.onNodeWithContentDescription("Minimise the video").assertDoesNotExist()
        composeRule.onNodeWithText("Audio").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Next episode").assertDoesNotExist()
        composeRule.onNodeWithText("Episode").assertDoesNotExist()
        tapLabelled("Show the controls").assertExists()
    }

    @Test
    fun `a tap where a faded button sits brings the controls back instead of pressing it`() {
        val pressed = mutableListOf<String>()
        setScreen(VideoActions(onCollapse = { pressed += "collapse" }))
        // Where the minimise button is, read while it can still be found.
        val button = composeRule.onNodeWithContentDescription("Minimise the video")
            .fetchSemanticsNode().boundsInRoot.center
        controlsVisible = false
        composeRule.waitForIdle()

        composeRule.onRoot().performTouchInput { click(button) }
        composeRule.waitForIdle()

        assertEquals(emptyList<String>(), pressed)
        assertEquals(true, controlsVisible)
    }

    @Test
    fun `minimise and switch to audio each reach their own handler`() {
        val pressed = mutableListOf<String>()
        setScreen(
            VideoActions(
                onCollapse = { pressed += "collapse" },
                onListen = { pressed += "listen" },
            ),
        )

        composeRule.onNodeWithContentDescription("Minimise the video").performClick()
        // The screen is the video face; its switch says so, and the other half is the way out.
        composeRule.onNodeWithText("Video").assertIsSelected()
        tapLabelled("Switch to audio").performClick()

        assertEquals(listOf("collapse", "listen"), pressed)
    }

    @Test
    fun `the half of the switch for the face already showing does nothing`() {
        val pressed = mutableListOf<String>()
        setScreen(VideoActions(onListen = { pressed += "listen" }))

        composeRule.onNodeWithText("Video").performClick()

        assertEquals(emptyList<String>(), pressed)
    }

    @Test
    fun `back on a cleared page brings the controls back, and is not taken while they show`() {
        setScreen()
        // With the controls up, Back is the caller's: it minimises. Nothing here stands in its way.
        assertEquals(false, backDispatcher.hasEnabledCallbacks())

        controlsVisible = false
        composeRule.waitForIdle()
        composeRule.runOnUiThread { backDispatcher.onBackPressed() }
        composeRule.waitForIdle()

        assertEquals(true, controlsVisible)
        // And the next Back is the caller's again.
        assertEquals(false, backDispatcher.hasEnabledCallbacks())
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-land-xxhdpi")
    fun `back in landscape is never spent on the controls, which hide on their own there`() {
        controlsVisible = false
        setScreen()

        assertEquals(false, backDispatcher.hasEnabledCallbacks())
    }

    /**
     * The Fold opened out: a landscape window by its proportions, with the height of a page. The
     * way to tell the page from the overlay here is the button only the page offers unasked.
     */
    @Test
    @Config(qualifiers = "w882dp-h830dp-land-xxhdpi")
    fun `a wide window tall enough for it gets the page, not the overlay`() {
        setScreen()

        composeRule.onNodeWithContentDescription("Fill the screen").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Minimise the video").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w882dp-h830dp-land-xxhdpi")
    fun `on a wide window the picture keeps its proportions in the room it is left`() {
        composeRule.setContent {
            MegaPodcastPlayerTheme {
                VideoScreen(
                    uiState = VideoUiState(
                        playback = PlaybackState(
                            isConnected = true,
                            episodeId = "e1",
                            title = "Episode",
                            showTitle = "A playlist",
                            youTubeVideoId = "niTJ2221aS8",
                            durationMs = 60_000L,
                        ),
                    ),
                    surface = { modifier -> Box(modifier = modifier.testTag("surface")) },
                    actions = VideoActions(),
                )
            }
        }

        val picture = composeRule.onNodeWithTag("surface", useUnmergedTree = true)
            .getBoundsInRoot()
        val names = composeRule.onNodeWithText("A playlist", useUnmergedTree = true).getBoundsInRoot()
        val scrubber = composeRule.onNodeWithContentDescription("Playback position").getBoundsInRoot()

        // 16:9, and in the room between the names and the transport rather than over either: a
        // frame told only to fill the width takes whatever height that comes to.
        assertEquals(16f / 9f, picture.width / picture.height, 0.02f)
        assertTrue(picture.top >= names.bottom)
        assertTrue(picture.bottom <= scrubber.top)
    }

    @Test
    @Config(qualifiers = "w882dp-h830dp-land-xxhdpi")
    fun `asking for full screen on a wide window gets the overlay`() {
        // Nothing turns on a window this size, so the ask has to be what changes the shape. The
        // overlay is told by Back: it is never spent on the controls there.
        controlsVisible = false
        setScreen(fullscreen = true)

        assertEquals(false, backDispatcher.hasEnabledCallbacks())
        tapLabelled("Show the controls").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Leave full screen").assertIsDisplayed()
    }
}
