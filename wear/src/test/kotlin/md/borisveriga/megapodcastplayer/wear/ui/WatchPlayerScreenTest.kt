package md.borisveriga.megapodcastplayer.wear.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import md.borisveriga.megapodcastplayer.core.wearprotocol.NowPlayingSnapshot
import md.borisveriga.megapodcastplayer.core.wearprotocol.QueuedEpisode
import md.borisveriga.megapodcastplayer.wear.data.PhoneLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Renders the watch screen and checks what actually lands on it.
 *
 * The bug these replace was a layout one: a placeholder positioned in the corner of a round screen,
 * clipped away entirely, which read as a black screen. Catching that class of mistake needs the real
 * screen geometry, so the [Config] qualifier below pins the display to a small round watch —
 * 192 dp square at xhdpi, i.e. the 384 px Wear emulator. Rendered through Robolectric rather than on
 * a device because the Wear image available here ships an Android version Espresso cannot drive.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w192dp-h192dp-round-watch-xhdpi")
class WatchPlayerScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val playing = NowPlayingSnapshot(
        episodeId = "ep-1",
        title = "The one about batteries",
        showTitle = "Radio Hardware",
        isPlaying = true,
        positionMs = 252_000L,
        durationMs = 3_600_000L,
        speed = 1.5f,
        skipForwardMs = 30_000L,
        skipBackMs = 10_000L,
        hasNext = true,
        upNext = listOf(
            // A different show from the one playing, so the assertions below cannot match the queue
            // row when they mean the header.
            QueuedEpisode(id = "ep-2", title = "The one about antennas", showTitle = "Signal Path"),
        ),
    )

    /** A queue long enough to push anything below it off a 45 mm screen. */
    private val longQueue = List(12) { index ->
        QueuedEpisode(id = "ep-$index", title = "Queued episode $index", showTitle = "Signal Path")
    }

    @Test
    fun theEpisodeAndItsTransportControlsAreOnTheFirstScreen() {
        setScreen(
            uiState = WatchPlayerUiState(link = PhoneLink.CONNECTED, snapshot = playing),
            position = { PlaybackPosition(positionMs = 252_000L, progress = 0.07f) },
        )

        composeTestRule.onNodeWithText("The one about batteries").assertIsDisplayed()
        composeTestRule.onNodeWithText("Radio Hardware").assertIsDisplayed()
        // The position the watch worked out for itself, not one the phone sent as a string.
        composeTestRule.onNodeWithText("4:12").assertIsDisplayed()
        composeTestRule.onNodeWithText("1:00:00").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Pause").assertIsDisplayed()
    }

    /**
     * The position reaches the screen through a lambda rather than as part of the state, so that
     * the clock moving recomposes the label and not the list. This checks the half of that which
     * a test can see: the label does follow the lambda.
     */
    @Test
    fun `the time label follows the position on its own`() {
        val position = mutableStateOf(PlaybackPosition(positionMs = 252_000L, progress = 0.07f))
        setScreen(
            uiState = WatchPlayerUiState(link = PhoneLink.CONNECTED, snapshot = playing),
            position = { position.value },
        )
        composeTestRule.onNodeWithText("4:12").assertIsDisplayed()

        composeTestRule.runOnIdle {
            position.value = PlaybackPosition(positionMs = 253_000L, progress = 0.07f)
        }

        composeTestRule.onNodeWithText("4:13").assertIsDisplayed()
    }

    @Test
    fun theSkipButtonsAnnounceThePhonesConfiguredIntervals() {
        setScreen(WatchPlayerUiState(link = PhoneLink.CONNECTED, snapshot = playing))

        composeTestRule.onNodeWithContentDescription("Skip ahead 30 seconds").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Skip back 10 seconds").assertIsDisplayed()
    }

    @Test
    fun theCentreButtonAsksThePhoneToToggle() {
        var toggles = 0
        setScreen(
            uiState = WatchPlayerUiState(link = PhoneLink.CONNECTED, snapshot = playing),
            onTogglePlayPause = { toggles++ },
        )

        composeTestRule.onNodeWithContentDescription("Pause").performClick()

        assertEquals(1, toggles)
    }

    @Test
    fun aPausedPhoneOffersPlayRatherThanPause() {
        setScreen(
            WatchPlayerUiState(link = PhoneLink.CONNECTED, snapshot = playing.copy(isPlaying = false)),
        )

        composeTestRule.onNodeWithContentDescription("Play").assertIsDisplayed()
    }

    /**
     * The secondary controls sit below the fold on a watch, so this is really a test that the list
     * scrolls — the failure mode being controls composed but permanently out of reach.
     */
    @Test
    fun theSpeedAndEpisodeControlsAreReachableByScrolling() {
        setScreen(WatchPlayerUiState(link = PhoneLink.CONNECTED, snapshot = playing))

        scrollTo("1.5x")

        composeTestRule.onNodeWithText("1.5x").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Next episode").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Previous episode").assertIsDisplayed()
    }

    @Test
    fun tappingAQueuedEpisodePlaysIt() {
        var played: String? = null
        setScreen(
            uiState = WatchPlayerUiState(link = PhoneLink.CONNECTED, snapshot = playing),
            onPlayOnPhone = { played = it },
        )

        scrollTo("The one about antennas")
        composeTestRule.onNodeWithText("The one about antennas").performClick()

        assertEquals("ep-2", played)
    }

    @Test
    fun aBufferingPhoneSaysSoRatherThanLookingPaused() {
        setScreen(
            WatchPlayerUiState(
                link = PhoneLink.CONNECTED,
                snapshot = playing.copy(isBuffering = true),
            ),
        )

        composeTestRule.onNodeWithContentDescription("Buffering").assertIsDisplayed()
        // The button stays pressable while the ring is up; buffering is a state, not a third mode.
        composeTestRule.onNodeWithContentDescription("Pause").assertIsDisplayed()
    }

    @Test
    fun aPhoneThatIsNotBufferingShowsNoRing() {
        setScreen(WatchPlayerUiState(link = PhoneLink.CONNECTED, snapshot = playing))

        composeTestRule.onNodeWithContentDescription("Buffering").assertDoesNotExist()
    }

    @Test
    fun theProgressBarOffersScrubbingWhenTheDurationIsKnown() {
        setScreen(WatchPlayerUiState(link = PhoneLink.CONNECTED, snapshot = playing))

        composeTestRule
            .onNodeWithContentDescription("Playback position. Tap to adjust")
            .assertIsDisplayed()
    }

    @Test
    fun aScrubInProgressSaysHowToFinishIt() {
        setScreen(
            WatchPlayerUiState(
                link = PhoneLink.CONNECTED,
                snapshot = playing,
                isScrubbing = true,
            ),
        )

        composeTestRule
            .onNodeWithContentDescription("Adjusting position. Turn the bezel, then tap to confirm")
            .assertIsDisplayed()
    }

    /**
     * The header draws a waveform and a colour where cover art used to be, and both are decorative.
     * The words are what has to survive: a phone that sends no show title must not cost the episode
     * its own line, and TalkBack must not be handed a bar chart to read out.
     */
    @Test
    fun theHeaderShowsTheEpisodeWithNoShowTitleToDecorateItWith() {
        setScreen(
            WatchPlayerUiState(
                link = PhoneLink.CONNECTED,
                snapshot = playing.copy(showTitle = ""),
            ),
        )

        composeTestRule.onNodeWithText("The one about batteries").assertIsDisplayed()
        composeTestRule.onNodeWithText("Radio Hardware").assertDoesNotExist()
    }

    @Test
    fun anIdlePhoneExplainsItselfInsteadOfShowingDeadControls() {
        setScreen(WatchPlayerUiState(link = PhoneLink.CONNECTED, snapshot = NowPlayingSnapshot()))

        composeTestRule.onNodeWithText("Nothing playing").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Play").assertDoesNotExist()
    }

    @Test
    fun anUnreachablePhoneReplacesTheControlsAndOffersARetry() {
        var retries = 0
        setScreen(
            uiState = WatchPlayerUiState(link = PhoneLink.DISCONNECTED, snapshot = playing),
            onRetry = { retries++ },
        )

        composeTestRule.onNodeWithText("Phone not connected").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Pause").assertDoesNotExist()

        composeTestRule.onNodeWithText("Retry").performClick()

        assertTrue(retries > 0)
    }

    @Test
    fun aPhoneWithoutTheAppSaysSoRatherThanBlamingBluetooth() {
        setScreen(WatchPlayerUiState(link = PhoneLink.APP_NOT_INSTALLED, snapshot = playing))

        composeTestRule.onNodeWithText("MegaPodcastPlayer is not on your phone").assertIsDisplayed()
    }

    // ---- One column -----------------------------------------------------------------------------

    /**
     * The queue is the only part of the column whose length the phone decides, and it comes last,
     * so the transport sits exactly where it sits when the queue is empty. This is what a second
     * page used to guarantee; with the queue at the bottom, one column guarantees it too.
     */
    @Test
    fun `the transport stays put however long the phone's queue is`() {
        setScreen(
            WatchPlayerUiState(
                link = PhoneLink.CONNECTED,
                snapshot = playing.copy(upNext = longQueue),
            ),
        )

        composeTestRule.onNodeWithContentDescription("Pause").assertIsDisplayed()
        // Below the fold, not beside it: the queue is a scroll away, never a swipe.
        composeTestRule.onNodeWithText("Queued episode 0").assertIsNotDisplayed()
        composeTestRule.onNode(isHorizontalPager()).assertDoesNotExist()
    }

    /**
     * The order the column is read in. The moment button is the last control, and the queue starts
     * directly under it — not above the transport, and not on a page of its own.
     */
    @Test
    fun `the queue sits right under the moment button`() {
        setScreen(WatchPlayerUiState(link = PhoneLink.CONNECTED, snapshot = playing))

        // Two scrolls, because a lazy column only composes what is near the viewport and the three
        // items do not all fit a 192 dp screen at once; each pair is compared while both exist.
        scrollTo("Save moment")
        val speed = composeTestRule.onNodeWithText("1.5x").getUnclippedBoundsInRoot()
        val moment = composeTestRule.onNodeWithText("Save moment").getUnclippedBoundsInRoot()
        assertTrue("speed row above the moment button", speed.bottom <= moment.top)

        scrollTo("Phone queue")
        val momentAgain = composeTestRule.onNodeWithText("Save moment").getUnclippedBoundsInRoot()
        val queue = composeTestRule.onNodeWithText("Phone queue").getUnclippedBoundsInRoot()
        assertTrue("queue header below the moment button", momentAgain.bottom <= queue.top)
    }

    /**
     * With nothing playing there are no controls, and the sentence explaining the empty transport
     * sits above the episodes it is telling the wearer to pick from.
     */
    @Test
    fun `an idle phone still lists its queue`() {
        setScreen(
            WatchPlayerUiState(
                link = PhoneLink.CONNECTED,
                snapshot = NowPlayingSnapshot(upNext = playing.upNext),
            ),
        )

        composeTestRule.onNodeWithText("Nothing playing").assertIsDisplayed()

        scrollTo("The one about antennas")
        composeTestRule.onNodeWithText("The one about antennas").assertIsDisplayed()
    }

    /**
     * The header names the list rather than the action its rows perform: on a screen this small it
     * is the only thing saying whose episodes these are.
     */
    @Test
    fun `the queue names whose episodes it holds`() {
        setScreen(WatchPlayerUiState(link = PhoneLink.CONNECTED, snapshot = playing))

        scrollTo("Phone queue")
        composeTestRule.onNodeWithText("Phone queue").assertIsDisplayed()
    }

    /** Scrolls the column until the node holding [text] is on screen. */
    private fun scrollTo(text: String) {
        composeTestRule.onNode(isVerticalList()).performScrollToNode(hasText(text))
    }

    /** A pager: a scrollable that moves sideways. There must not be one on this screen any more. */
    private fun isHorizontalPager(): SemanticsMatcher = hasScrollToNodeAction() and
        SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)

    /** The column: the one scrollable on the screen, and it moves up and down. */
    private fun isVerticalList(): SemanticsMatcher = hasScrollToNodeAction() and
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    @Test
    fun theFirstScrubSaysWhatTheBezelDoes() {
        setScreen(
            WatchPlayerUiState(
                link = PhoneLink.CONNECTED,
                snapshot = playing,
                isScrubbing = true,
                showsScrubHint = true,
            ),
        )

        composeTestRule.onNodeWithText("Turn the bezel to seek").assertIsDisplayed()
    }

    @Test
    fun aScrubThatHasBeenExplainedBeforeSaysNothing() {
        setScreen(
            WatchPlayerUiState(
                link = PhoneLink.CONNECTED,
                snapshot = playing,
                isScrubbing = true,
            ),
        )

        composeTestRule.onNodeWithText("Turn the bezel to seek").assertDoesNotExist()
        // The mode is still entered, and the bar still says so to TalkBack.
        composeTestRule
            .onNodeWithContentDescription("Adjusting position. Turn the bezel, then tap to confirm")
            .assertIsDisplayed()
    }

    /** Renders the screen with no-op callbacks except the ones a test cares about. */
    private fun setScreen(
        uiState: WatchPlayerUiState,
        position: () -> PlaybackPosition = { PlaybackPosition() },
        onTogglePlayPause: () -> Unit = {},
        onPlayOnPhone: (String) -> Unit = {},
        onRetry: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            androidx.wear.compose.material3.MaterialTheme {
                androidx.wear.compose.material3.AppScaffold {
                    WatchPlayerScreen(
                        uiState = uiState,
                        position = position,
                        onTogglePlayPause = onTogglePlayPause,
                        onSkipForward = {},
                        onSkipBack = {},
                        onSkipToNext = {},
                        onSkipToPrevious = {},
                        onCycleSpeed = {},
                        onPlayOnPhone = onPlayOnPhone,
                        onRetry = onRetry,
                    )
                }
            }
        }
    }
}
