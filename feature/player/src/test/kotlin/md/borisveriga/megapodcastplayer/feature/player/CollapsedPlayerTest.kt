package md.borisveriga.megapodcastplayer.feature.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.media.PlaybackState
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings
import md.borisveriga.megapodcastplayer.core.model.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Tests for the collapsed player bar.
 *
 * Two things are pinned here, and both were bugs the bar shipped with.
 *
 * The first is arithmetic. The bar's height was a flat 64 dp holding two lines of text, so somewhere
 * past a 150 % font scale the lines alone outgrew it and the show's name was clipped in half — on
 * the one surface that is on screen for the whole session. What is asserted is the property rather
 * than a number: whatever the type does at whatever scale, the bar is at least as tall as it. The
 * bar is boxed at exactly `collapsedPlayerHeight()` because that is the geometry the sheet imposes
 * on it, and a bar that measured itself independently would still be clipped by its container.
 *
 * The second is honesty: the skip button was a fixed `Forward30` glyph while the setting offers five
 * intervals, so it said 30 and jumped 45 for four of them.
 *
 * The bar of an episode being watched is covered too: it is the one bar that draws something where
 * the artwork goes — the picture — and what is pinned is when it does, what shape it gives it, and
 * that the wider frame leaves the two lines of text inside the bar.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class CollapsedPlayerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val playback = PlaybackState(
        isConnected = true,
        episodeId = "e1",
        title = "Podlodka #492",
        showTitle = "Podlodka Podcast",
        isPlaying = true,
        positionMs = 724_000L,
        durationMs = 2_530_000L,
    )

    /** The same episode as a YouTube one whose picture is playing, [width] by [height]. */
    private fun watched(width: Int = 1280, height: Int = 720) = playback.copy(
        youTubeVideoId = "niTJ2221aS8",
        videoQuality = VideoQuality(720),
        videoWidth = width,
        videoHeight = height,
        pictureReady = true,
    )

    /**
     * Renders the bar at a given font scale, and reports the height the sheet gave it.
     *
     * @param settings the skip intervals under test.
     * @param fontScale the system text-size multiplier; 2.0 is reachable from Android's own
     *   display settings.
     * @param playback what the player is doing.
     * @param video whether the bar is the video screen put away. The picture is then a tagged box,
     *   standing in for the view bound to the player.
     * @return the bar's height, to assert the content against.
     */
    private fun setContent(
        settings: PlaybackSettings = PlaybackSettings(),
        fontScale: Float = 1f,
        playback: PlaybackState = this.playback,
        video: Boolean = false,
    ): Dp {
        var barHeight = Dp.Unspecified
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                MegaPodcastPlayerTheme {
                    barHeight = collapsedPlayerHeight()
                    Box(modifier = Modifier.height(barHeight)) {
                        CollapsedPlayer(
                            playback = playback,
                            settings = settings,
                            onPlayPause = {},
                            onSkipBack = {},
                            onSkipForward = {},
                            video = video,
                            picture = { pictureModifier ->
                                Box(modifier = pictureModifier.testTag(PICTURE_TAG))
                            },
                        )
                    }
                }
            }
        }
        return barHeight
    }

    @Test
    fun `a bar that is not the video put away draws no picture`() {
        // A YouTube episode being listened to: it has a picture, and the bar is not where it goes.
        setContent(playback = watched(), video = false)

        composeRule.onNodeWithTag(PICTURE_TAG).assertDoesNotExist()
    }

    @Test
    fun `the bar of an episode being watched shows its picture, as wide as a picture is`() {
        setContent(playback = watched(), video = true)

        val picture = composeRule.onNodeWithTag(PICTURE_TAG).assertIsDisplayed().getBoundsInRoot()
        // The artwork's height and a 16:9 picture's width: the bar keeps its height, and the
        // picture is not cropped to the square the artwork sits in.
        assertEquals(ARTWORK_SIDE.value, picture.height.value, DP_TOLERANCE)
        assertEquals(ARTWORK_SIDE.value * 16f / 9f, picture.width.value, DP_TOLERANCE)
    }

    @Test
    fun `a picture of another shape sits inside the frame rather than being stretched over it`() {
        // A vertical video: as tall as the frame, and a narrow strip of its width.
        setContent(playback = watched(width = 720, height = 1280), video = true)

        val picture = composeRule.onNodeWithTag(PICTURE_TAG).getBoundsInRoot()
        assertEquals(ARTWORK_SIDE.value, picture.height.value, DP_TOLERANCE)
        assertEquals(ARTWORK_SIDE.value * 9f / 16f, picture.width.value, DP_TOLERANCE)
    }

    @Test
    fun `the wider frame still leaves both lines inside the bar at twice the text size`() {
        val barHeight = setContent(fontScale = 2f, playback = watched(), video = true)

        assertFitsInside(barHeight)
        composeRule.onNodeWithContentDescription("Pause").assertIsDisplayed()
    }

    @Test
    fun `both lines of text fit inside the bar`() {
        val barHeight = setContent()

        assertFitsInside(barHeight)
    }

    @Test
    fun `both lines still fit at twice the text size`() {
        // The confirmed break. At 200 % the two lines alone are taller than the 64 dp the bar used
        // to be fixed at, and the show's name was cut through the middle.
        val barHeight = setContent(fontScale = 2f)

        assertTrue("the bar did not grow with the text", barHeight > MIN_BAR_HEIGHT)
        assertFitsInside(barHeight)
    }

    @Test
    fun `the skip buttons say the interval they will actually jump`() {
        setContent(PlaybackSettings(skipForwardMs = 45_000L, skipBackMs = 15_000L))

        composeRule.onNodeWithContentDescription("Skip ahead 45 seconds").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Skip back 15 seconds").assertIsDisplayed()
    }

    @Test
    fun `the bar carries the play control and both skips`() {
        setContent()

        // Three controls, not one. Replaying a sentence used to need the whole sheet opened.
        composeRule.onNodeWithContentDescription("Pause").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Skip back 30 seconds").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Skip ahead 30 seconds").assertIsDisplayed()
    }

    /**
     * Asserts that neither line of text runs past the bottom of the bar.
     *
     * @param barHeight the height the bar was measured at.
     */
    private fun assertFitsInside(barHeight: Dp) {
        val title = composeRule.onNodeWithText("Podlodka #492").getBoundsInRoot()
        val show = composeRule.onNodeWithText("Podlodka Podcast").getBoundsInRoot()

        assertTrue("the title overflows the bar", title.bottom <= barHeight)
        assertTrue("the show line overflows the bar", show.bottom <= barHeight)
    }

    private companion object {
        /** The height the bar rests at when the type does not need more. */
        val MIN_BAR_HEIGHT: Dp = 64.dp

        /** The side of the artwork the picture stands in for: `ArtworkSize.Mini`. */
        val ARTWORK_SIDE: Dp = 44.dp

        /** Slack for a dp value that went through whole pixels on the way. */
        const val DP_TOLERANCE = 0.5f

        /** Marks the box the tests draw where the player's picture would be. */
        const val PICTURE_TAG = "picture"
    }
}
