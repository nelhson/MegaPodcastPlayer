package md.borisveriga.megapodcastplayer.feature.player.video

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.media.PlaybackState
import org.junit.Assert.assertEquals
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
 * faded out cannot still be pressed or spoken, and that *minimise* and *switch to audio* each reach
 * their own handler rather than each other's.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class VideoScreenControlsTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Whether the screen under test is showing its controls; the test's stand-in for the route. */
    private var controlsVisible by mutableStateOf(true)

    /** The video screen on a YouTube episode, holding its own cleared-or-not flag. */
    private fun setScreen(actions: VideoActions = VideoActions()) {
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
                    surface = { modifier -> Box(modifier = modifier) },
                    actions = actions,
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
        composeRule.onNodeWithContentDescription("Switch to audio").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Next episode").assertDoesNotExist()
        composeRule.onNodeWithText("Episode").assertDoesNotExist()
        tapLabelled("Show the controls").assertExists()
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
        composeRule.onNodeWithContentDescription("Switch to audio").performClick()

        assertEquals(listOf("collapse", "listen"), pressed)
    }
}
