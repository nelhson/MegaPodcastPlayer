package md.borisveriga.bpodcat.wear.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import md.borisveriga.bpodcat.core.wearprotocol.NowPlayingSnapshot
import md.borisveriga.bpodcat.core.wearprotocol.OfflineEpisode
import md.borisveriga.bpodcat.core.wearprotocol.QueuedEpisode
import md.borisveriga.bpodcat.wear.data.PhoneLink
import md.borisveriga.bpodcat.wear.data.StoredEpisode
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

    @Test
    fun theEpisodeAndItsTransportControlsAreOnTheFirstScreen() {
        setScreen(
            WatchPlayerUiState(link = PhoneLink.CONNECTED, snapshot = playing, positionMs = 252_000L),
        )

        composeTestRule.onNodeWithText("The one about batteries").assertIsDisplayed()
        composeTestRule.onNodeWithText("Radio Hardware").assertIsDisplayed()
        // The position the watch worked out for itself, not one the phone sent as a string.
        composeTestRule.onNodeWithText("4:12").assertIsDisplayed()
        composeTestRule.onNodeWithText("1:00:00").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Pause").assertIsDisplayed()
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
            onPlayQueued = { played = it },
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
                positionMs = 252_000L,
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

        composeTestRule.onNodeWithText("BPodcat is not on your phone").assertIsDisplayed()
    }

    /** Scrolls the one scrollable list on the screen until the node holding [text] is on it. */
    private fun scrollTo(text: String) {
        composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
    }

    // ---- Episodes the watch holds ---------------------------------------------------------------

    @Test
    fun `episodes on the watch are listed and can be played from here`() {
        var played: StoredEpisode? = null
        setScreen(
            uiState = WatchPlayerUiState(
                link = PhoneLink.CONNECTED,
                snapshot = playing,
                stored = listOf(stored),
            ),
            onPlayOnWatch = { played = it },
        )

        scrollTo("The one about capacitors")
        composeTestRule.onNodeWithText("The one about capacitors").performClick()

        assertEquals("ep-9", played?.id)
    }

    @Test
    fun `an episode the phone has and the watch does not can be asked for`() {
        var copied: String? = null
        setScreen(
            uiState = WatchPlayerUiState(
                link = PhoneLink.CONNECTED,
                snapshot = playing,
                offered = listOf(
                    OfflineEpisode(id = "ep-8", title = "The one about resistors", showTitle = "Radio Hardware"),
                ),
            ),
            onCopyToWatch = { copied = it },
        )

        scrollTo("The one about resistors")
        composeTestRule.onNodeWithText("The one about resistors").performClick()

        assertEquals("ep-8", copied)
    }

    /**
     * The whole point of carrying episodes over: the phone is at home and the watch still plays. The
     * unreachable-phone screen must not stand in front of that.
     */
    @Test
    fun `an unreachable phone still shows what the watch can play by itself`() {
        setScreen(
            WatchPlayerUiState(
                link = PhoneLink.DISCONNECTED,
                snapshot = NowPlayingSnapshot(),
                stored = listOf(stored),
            ),
        )

        composeTestRule.onNodeWithText("Phone not connected").assertDoesNotExist()
        scrollTo("The one about capacitors")
        composeTestRule.onNodeWithText("The one about capacitors").assertIsDisplayed()
    }

    /**
     * While the watch is playing its own audio the phone's queue is not what "next" means, so the
     * button that would skip through it is replaced by the way back to the phone.
     */
    @Test
    fun `local playback swaps the queue controls for the way back to the phone`() {
        setScreen(
            WatchPlayerUiState(
                link = PhoneLink.CONNECTED,
                snapshot = playing,
                source = PlaybackSource.WATCH,
                stored = listOf(stored),
            ),
        )

        scrollTo("1.5x")
        composeTestRule.onNodeWithContentDescription("Back to the phone").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Next episode").assertDoesNotExist()
    }

    /** An episode on the watch, distinct from everything else on screen. */
    private val stored = StoredEpisode(
        id = "ep-9",
        title = "The one about capacitors",
        showTitle = "Radio Hardware",
        durationMs = 1_800_000L,
        sizeBytes = 14_000_000L,
    )

    /** Renders the screen with no-op callbacks except the ones a test cares about. */
    private fun setScreen(
        uiState: WatchPlayerUiState,
        onTogglePlayPause: () -> Unit = {},
        onPlayQueued: (String) -> Unit = {},
        onRetry: () -> Unit = {},
        onPlayOnWatch: (StoredEpisode) -> Unit = {},
        onCopyToWatch: (String) -> Unit = {},
    ) {
        composeTestRule.setContent {
            androidx.wear.compose.material3.MaterialTheme {
                androidx.wear.compose.material3.AppScaffold {
                    WatchPlayerScreen(
                        uiState = uiState,
                        onTogglePlayPause = onTogglePlayPause,
                        onSkipForward = {},
                        onSkipBack = {},
                        onSkipToNext = {},
                        onSkipToPrevious = {},
                        onCycleSpeed = {},
                        onPlayQueued = onPlayQueued,
                        onRetry = onRetry,
                        onPlayOnWatch = onPlayOnWatch,
                        onCopyToWatch = onCopyToWatch,
                    )
                }
            }
        }
    }
}
