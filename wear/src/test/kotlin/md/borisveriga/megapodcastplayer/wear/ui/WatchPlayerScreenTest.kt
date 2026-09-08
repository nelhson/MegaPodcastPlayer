package md.borisveriga.megapodcastplayer.wear.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import md.borisveriga.megapodcastplayer.core.wearprotocol.NowPlayingSnapshot
import md.borisveriga.megapodcastplayer.core.wearprotocol.OfflineEpisode
import md.borisveriga.megapodcastplayer.core.wearprotocol.QueuedEpisode
import md.borisveriga.megapodcastplayer.wear.data.PhoneLink
import md.borisveriga.megapodcastplayer.wear.data.StoredEpisode
import md.borisveriga.megapodcastplayer.wear.data.TransferProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
            onPlayOnPhone = { played = it },
        )

        openEpisodes()
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

        composeTestRule.onNodeWithText("MegaPodcastPlayer is not on your phone").assertIsDisplayed()
    }

    // ---- Two pages ------------------------------------------------------------------------------

    /**
     * The whole reason the screen was split. The episode lists grow without limit — the phone's
     * queue, what the phone has downloaded, what this watch holds — and in one column that pushed
     * pause off the bottom of a 45 mm screen. On a page of its own the transport sits exactly where
     * it sits when the watch is carrying nothing.
     */
    @Test
    fun `the transport stays put however many episodes the watch is carrying`() {
        setScreen(
            WatchPlayerUiState(
                link = PhoneLink.CONNECTED,
                snapshot = playing,
                stored = List(12) { index ->
                    stored.copy(id = "ep-$index", title = "Stored episode $index")
                },
            ),
        )

        composeTestRule.onNodeWithContentDescription("Pause").assertIsDisplayed()
        composeTestRule.onNodeWithText("Stored episode 0").assertDoesNotExist()
    }

    @Test
    fun `the episodes are one swipe away rather than one scroll`() {
        setScreen(
            WatchPlayerUiState(
                link = PhoneLink.CONNECTED,
                snapshot = playing,
                stored = listOf(stored),
            ),
        )

        composeTestRule.onNodeWithText("On this watch").assertDoesNotExist()

        openEpisodes()

        scrollTo("On this watch")
        composeTestRule.onNodeWithText("On this watch").assertIsDisplayed()
    }

    /**
     * With nothing playing there is no page worth swiping to, so the screen collapses back to the
     * one list it always was — and the sentence explaining the empty transport sits above the
     * episodes it is telling the wearer to pick from.
     */
    @Test
    fun `an idle phone keeps its episodes on the one page`() {
        setScreen(
            WatchPlayerUiState(
                link = PhoneLink.CONNECTED,
                snapshot = NowPlayingSnapshot(upNext = playing.upNext),
                stored = listOf(stored),
            ),
        )

        composeTestRule.onNodeWithText("Nothing playing").assertIsDisplayed()

        scrollTo("The one about capacitors")
        composeTestRule.onNodeWithText("The one about capacitors").assertIsDisplayed()
    }

    /**
     * A fact about the phone is true on whichever page the thumb happens to be on, so the two link
     * notes are drawn on both rather than assigned to one of them.
     */
    @Test
    fun `a phone out of range says so on both pages`() {
        setScreen(
            WatchPlayerUiState(
                link = PhoneLink.DISCONNECTED,
                snapshot = playing,
                source = PlaybackSource.WATCH,
                stored = listOf(stored),
            ),
        )

        scrollTo(OUT_OF_RANGE)
        composeTestRule.onNodeWithText(OUT_OF_RANGE).assertIsDisplayed()

        openEpisodes()

        scrollTo(OUT_OF_RANGE)
        composeTestRule.onNodeWithText(OUT_OF_RANGE).assertIsDisplayed()
    }

    // ---- What a stored row says about itself ----------------------------------------------------

    /**
     * Every stored row answers the same question — will this fit my walk — so one nobody has started
     * gives its whole length rather than only naming the show.
     */
    @Test
    fun `an episode nobody has started says how long it is`() {
        setScreen(
            WatchPlayerUiState(
                link = PhoneLink.CONNECTED,
                snapshot = playing,
                stored = listOf(stored),
            ),
        )

        openEpisodes()
        scrollTo("The one about capacitors")

        composeTestRule.onNodeWithText("Radio Hardware · 30m").assertIsDisplayed()
    }

    @Test
    fun `a part-heard episode says what is left of it instead`() {
        setScreen(
            WatchPlayerUiState(
                link = PhoneLink.CONNECTED,
                snapshot = playing,
                stored = listOf(stored.copy(positionMs = 600_000L)),
            ),
        )

        openEpisodes()
        scrollTo("The one about capacitors")

        composeTestRule.onNodeWithText("Radio Hardware · 20m left").assertIsDisplayed()
    }

    /** Scrolls the list on whichever page is showing until the node holding [text] is on it. */
    private fun scrollTo(text: String) {
        composeTestRule.onNode(isVerticalList()).performScrollToNode(hasText(text))
    }

    /** The same, for a node that carries no text of its own — a button that is only a glyph. */
    private fun scrollToDescription(description: String) {
        composeTestRule.onNode(isVerticalList())
            .performScrollToNode(hasContentDescription(description))
    }

    /**
     * Moves from the now-playing page to the episodes page.
     *
     * Driven through the pager's own scroll-to-index rather than by a swipe gesture: the left edge
     * of the first page is reserved for the system's swipe-to-dismiss, and a test that has to aim
     * around that zone ends up asserting on the gesture rather than on the page it lands on.
     */
    private fun openEpisodes() {
        composeTestRule.onNode(isPager()).performScrollToIndex(EPISODES_PAGE)
    }

    /** The pager: the one scrollable on this screen that moves sideways. */
    private fun isPager(): SemanticsMatcher = hasScrollToNodeAction() and
        SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)

    /** The list on the page being shown: the one scrollable that moves up and down. */
    private fun isVerticalList(): SemanticsMatcher = hasScrollToNodeAction() and
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

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

        openEpisodes()
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

        openEpisodes()
        scrollToDescription("Copy to watch")
        composeTestRule.onNodeWithContentDescription("Copy to watch").performClick()

        assertEquals("ep-8", copied)
    }

    /**
     * The common want, and the one this list could not serve before: the phone is in a pocket and
     * the episode should come out of it now, without minutes of Bluetooth first.
     */
    @Test
    fun `tapping a downloaded-on-phone episode starts it on the phone`() {
        var played: String? = null
        var copied: String? = null
        setScreen(
            uiState = WatchPlayerUiState(
                link = PhoneLink.CONNECTED,
                snapshot = playing,
                offered = listOf(
                    OfflineEpisode(id = "ep-8", title = "The one about resistors", showTitle = "Radio Hardware"),
                ),
            ),
            onPlayOnPhone = { played = it },
            onCopyToWatch = { copied = it },
        )

        openEpisodes()
        scrollTo("The one about resistors")
        composeTestRule.onNodeWithText("The one about resistors").performClick()

        assertEquals("ep-8", played)
        // The expensive half of the row must stay behind its own button.
        assertNull(copied)
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

    /**
     * Three lists run down this screen one after another, and the only thing separating them is a
     * header. Each therefore has to name the list rather than the action its rows perform: "up next"
     * and "downloaded" are both true of the phone at once, and neither is true of the watch.
     */
    @Test
    fun `each list names whose episodes it holds`() {
        setScreen(
            WatchPlayerUiState(
                link = PhoneLink.CONNECTED,
                snapshot = playing,
                stored = listOf(stored),
                offered = listOf(
                    OfflineEpisode(
                        id = "ep-8",
                        title = "The one about resistors",
                        showTitle = "Radio Hardware",
                    ),
                ),
            ),
        )

        openEpisodes()

        scrollTo("Phone queue")
        composeTestRule.onNodeWithText("Phone queue").assertIsDisplayed()

        scrollTo("Downloaded on phone")
        composeTestRule.onNodeWithText("Downloaded on phone").assertIsDisplayed()

        scrollTo("On this watch")
        composeTestRule.onNodeWithText("On this watch").assertIsDisplayed()
    }

    /**
     * With the header naming the list rather than the action, the row's two icons are the only
     * thing left saying which device each target reaches — and TalkBack cannot see an icon.
     */
    @Test
    fun `a downloaded-on-phone row announces both of the things it can do`() {
        setScreen(
            WatchPlayerUiState(
                link = PhoneLink.CONNECTED,
                snapshot = playing,
                offered = listOf(
                    OfflineEpisode(
                        id = "ep-8",
                        title = "The one about resistors",
                        showTitle = "Radio Hardware",
                    ),
                ),
            ),
        )

        openEpisodes()
        scrollTo("The one about resistors")
        composeTestRule.onNodeWithContentDescription("Play on phone").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Copy to watch").assertIsDisplayed()
    }

    /**
     * An episode is minutes of Bluetooth, so the wrong one tapped — or one that has plainly stalled
     * — needs a way out that is not "wait for it".
     */
    @Test
    fun `a copy that is arriving can be cancelled`() {
        var cancelled: String? = null
        val coming = OfflineEpisode(
            id = "ep-8",
            title = "The one about resistors",
            showTitle = "Radio Hardware",
            sizeBytes = 20_000_000L,
        )
        setScreen(
            uiState = WatchPlayerUiState(
                link = PhoneLink.CONNECTED,
                snapshot = playing,
                offered = listOf(coming),
                transfers = mapOf(
                    "ep-8" to TransferProgress(receivedBytes = 5_000_000L, expectedBytes = 20_000_000L),
                ),
            ),
            onCancelCopyToWatch = { cancelled = it },
        )

        openEpisodes()
        // Scrolled to the button rather than to the row's title: the row is taller than a quarter
        // of this screen, so a scroll that lands the title leaves the button below the bezel.
        scrollToDescription("Cancel copy")
        composeTestRule.onNodeWithContentDescription("Cancel copy").performClick()

        assertEquals("ep-8", cancelled)
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
    @Test
    fun theFirstScrubSaysWhatTheBezelDoes() {
        setScreen(
            WatchPlayerUiState(
                link = PhoneLink.CONNECTED,
                snapshot = playing,
                positionMs = 252_000L,
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
                positionMs = 252_000L,
                isScrubbing = true,
            ),
        )

        composeTestRule.onNodeWithText("Turn the bezel to seek").assertDoesNotExist()
        // The mode is still entered, and the bar still says so to TalkBack.
        composeTestRule
            .onNodeWithContentDescription("Adjusting position. Turn the bezel, then tap to confirm")
            .assertIsDisplayed()
    }

    private fun setScreen(
        uiState: WatchPlayerUiState,
        onTogglePlayPause: () -> Unit = {},
        onPlayOnPhone: (String) -> Unit = {},
        onRetry: () -> Unit = {},
        onPlayOnWatch: (StoredEpisode) -> Unit = {},
        onCopyToWatch: (String) -> Unit = {},
        onCancelCopyToWatch: (String) -> Unit = {},
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
                        onPlayOnPhone = onPlayOnPhone,
                        onRetry = onRetry,
                        onPlayOnWatch = onPlayOnWatch,
                        onCopyToWatch = onCopyToWatch,
                        onCancelCopyToWatch = onCancelCopyToWatch,
                    )
                }
            }
        }
    }
}

/** The episodes page's index in the pager; the now-playing page is the one before it. */
private const val EPISODES_PAGE = 1

/** Spelled once because two assertions in a row read it, and it is a whole sentence. */
private const val OUT_OF_RANGE = "Phone out of range. Episodes on this watch still play."
