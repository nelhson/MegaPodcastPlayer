package md.borisveriga.megapodcastplayer.wear.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.LinearProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import md.borisveriga.megapodcastplayer.core.testing.ScreenshotVariant
import md.borisveriga.megapodcastplayer.core.testing.captureScreenshot
import md.borisveriga.megapodcastplayer.core.wearprotocol.NowPlayingSnapshot
import md.borisveriga.megapodcastplayer.core.wearprotocol.WatchEpisode
import md.borisveriga.megapodcastplayer.wear.data.PhoneLink
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Goldens for the watch.
 *
 * The first the module has had. Two performance reports in a row found a visual regression here
 * that nothing in `:wear` could catch — a scroll transformation applied to the wrong half of the
 * item, a waveform that stopped drawing — because the difference lived in a `graphicsLayer` and
 * layout bounds do not see it. A golden does.
 *
 * One rendering per state, not the phone's three: Wear Material has one colour scheme, so a light
 * variant would be the same image under a different name. The window is the small round watch the
 * screen tests use, 192 dp at xhdpi — the 384 px Wear emulator.
 *
 * The screen goldens are recorded with animations removed, through the app's own switch for it, as
 * every golden is; so the glint, which is composed only while animating, does not appear in them.
 * It has a golden of its own, of one frame drawn still, which is what pins what it draws.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = WATCH_SCREENSHOT_QUALIFIERS)
class WatchPlayerScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

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
        volume = 9,
        maxVolume = 15,
        upNext = listOf(
            WatchEpisode(id = "ep-2", title = "The one about antennas", showTitle = "Signal Path"),
        ),
    )

    /** Renders a screen on the watch, in its theme, under the scaffold that draws the time. */
    private fun capture(name: String, content: @Composable () -> Unit) =
        composeRule.captureScreenshot(name, ScreenshotVariant.DARK) {
            MaterialTheme { AppScaffold { content() } }
        }

    /**
     * Renders one component in the theme and nothing else.
     *
     * No scaffold: it fills the window and draws the time across the top, which is a picture of
     * the scaffold with a component under it, not of the component.
     */
    private fun captureComponent(name: String, content: @Composable () -> Unit) =
        composeRule.captureScreenshot(name, ScreenshotVariant.DARK) {
            MaterialTheme { content() }
        }

    @Test
    fun playing() = capture("watch-player-playing") {
        WatchPlayerScreen(
            uiState = WatchPlayerUiState(link = PhoneLink.CONNECTED, snapshot = playing, volumeLevel = 9),
            position = { PlaybackPosition(positionMs = 252_000L, progress = 0.07f) },
        )
    }

    @Test
    fun volumeOpen() = capture("watch-player-volume-open") {
        WatchPlayerScreen(
            uiState = WatchPlayerUiState(
                link = PhoneLink.CONNECTED,
                snapshot = playing,
                volumeLevel = 9,
                isAdjustingVolume = true,
            ),
            position = { PlaybackPosition(positionMs = 252_000L, progress = 0.07f) },
        )
    }

    @Test
    fun scrubbing() = capture("watch-player-scrubbing") {
        WatchPlayerScreen(
            uiState = WatchPlayerUiState(
                link = PhoneLink.CONNECTED,
                snapshot = playing,
                volumeLevel = 9,
                isScrubbing = true,
                showsScrubHint = true,
            ),
            position = { PlaybackPosition(positionMs = 1_800_000L, progress = 0.5f) },
        )
    }

    @Test
    fun idle() = capture("watch-player-idle") {
        WatchPlayerScreen(
            uiState = WatchPlayerUiState(link = PhoneLink.CONNECTED, snapshot = NowPlayingSnapshot()),
        )
    }

    @Test
    fun disconnected() = capture("watch-player-disconnected") {
        WatchPlayerScreen(uiState = WatchPlayerUiState(link = PhoneLink.DISCONNECTED))
    }

    /**
     * One frame of the glint, halfway along a bar three-fifths full.
     *
     * The frame is drawn from one remembered path and one remembered gradient, and this is what
     * says the saving did not change the picture: the band is where it was, as wide and as faint.
     */
    @Test
    fun glint() = captureComponent("watch-progress-glint") {
        Box(
            modifier = Modifier.padding(8.dp).width(160.dp).height(6.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            LinearProgressIndicator(progress = { 0.6f }, modifier = Modifier.width(160.dp).height(6.dp))
            ProgressGlintFrame(
                progress = { 0.6f },
                phase = { 0.5f },
                modifier = Modifier.width(160.dp).height(6.dp),
            )
        }
    }

    /** Renders the screen with no-op callbacks: a golden is a picture, and pictures do not tap. */
    @Composable
    private fun WatchPlayerScreen(
        uiState: WatchPlayerUiState,
        position: () -> PlaybackPosition = { PlaybackPosition() },
    ) {
        WatchPlayerScreen(
            uiState = uiState,
            position = position,
            onTogglePlayPause = {},
            onSkipForward = {},
            onSkipBack = {},
            onSkipToNext = {},
            onSkipToPrevious = {},
            onCycleSpeed = {},
            onPlayOnPhone = {},
            onQueueOnPhone = {},
            onRetry = {},
        )
    }
}

/**
 * The watch every golden is recorded on: a small round face, 192 dp across at xhdpi.
 *
 * The same window `WatchPlayerScreenTest` pins the layout to, and for the same reason: the bugs
 * worth catching on a watch are about what a round screen clips.
 */
const val WATCH_SCREENSHOT_QUALIFIERS: String = "w192dp-h192dp-round-watch-xhdpi"
