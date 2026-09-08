package md.borisveriga.megapodcastplayer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.media.EXTRA_OPEN_PLAYER
import md.borisveriga.megapodcastplayer.core.model.AppearanceSettings
import md.borisveriga.megapodcastplayer.ui.MegaPodcastPlayerApp
import md.borisveriga.megapodcastplayer.ui.NotificationPermissionEffect

/**
 * The single activity hosting MegaPodcastPlayer's Compose UI.
 *
 * Draws edge to edge so the Fold 7's inner display is used fully; window insets are consumed by the
 * individual screens' scaffolds.
 *
 * Installs the platform splash screen, which is what covers the cold-start gap: the window is
 * opened from Theme.MegaPodcastPlayer.Starting before this process exists, and swapped for the
 * ordinary theme here.
 *
 * It is also the target of the new-episode notification. The show to open arrives as
 * [EXTRA_PODCAST_ID] on the launch intent, an extra rather than a deep-link URI so that the app's
 * own internal navigation stays something only the app can ask for.
 *
 * And it is the target of a shared or tapped podcast link; see [podcastLinkOrNull] for what is
 * accepted and why nothing is added without a tap.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * The show a notification asked to open, or null.
     *
     * Compose state rather than a flow so the composition simply reads it. It is cleared once
     * [MegaPodcastPlayerApp] has navigated, so a configuration change does not re-navigate the user away from
     * wherever they have since gone.
     */
    private var pendingPodcastId by mutableStateOf<String?>(null)

    /**
     * The episode a notification asked to open, or null.
     *
     * Only ever set alongside [pendingPodcastId]: an episode is opened *within* its show, so the
     * two travel together and the show is what is navigated to.
     */
    private var pendingEpisodeId by mutableStateOf<String?>(null)

    /**
     * A podcast link shared or tapped from another app, or null.
     *
     * Held the same way [pendingPodcastId] is, and for the same reason: it is consumed once by the
     * composition and cleared, so unfolding the phone does not re-open the add screen over
     * whatever the user has since done.
     */
    private var pendingSharedLink by mutableStateOf<String?>(null)

    /**
     * Whether the intent asked for the player to be open on arrival.
     *
     * Set by the media notification's own tap target; see [EXTRA_OPEN_PLAYER]. Consumed once, like
     * the other two, so the sheet is not re-opened over whatever the user has since done.
     */
    private var pendingOpenPlayer by mutableStateOf(false)

    /** Reads the stored appearance; the only state this activity has of its own. */
    private val viewModel: MainActivityViewModel by viewModels()

    /**
     * How to draw the app, or null until the stored preference has been read.
     *
     * Compose state, so the composition simply reads it and redraws when the user changes the
     * theme on the settings screen; also what the splash screen waits on.
     */
    private var appearance by mutableStateOf<AppearanceSettings?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before `super.onCreate`, which is where the library requires it: the call swaps the
        // window from Theme.MegaPodcastPlayer.Starting to Theme.MegaPodcastPlayer, and it has to
        // happen while the starting theme is still the one in force. Without it the splash the
        // manifest declares would be drawn and then never dismissed correctly.
        val splashScreen = installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // The stored theme has to be known before the first frame, or a cold start flashes the
        // wrong palette at anyone who chose one. The splash is already on screen; holding it for
        // the length of one DataStore read is free, and it is the only thing being waited for.
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.appearance.collect { appearance = it }
            }
        }
        splashScreen.setKeepOnScreenCondition { appearance == null }

        readIntent(intent)
        setContent {
            val settings = appearance ?: AppearanceSettings.DEFAULT
            val darkTheme = settings.isDark(isSystemInDarkTheme())
            MegaPodcastPlayerTheme(
                darkTheme = darkTheme,
                dynamicColor = settings.dynamicColor,
                pureBlack = settings.pureBlack,
            ) {
                // Asked for here rather than at the moment of posting: the worker runs with no UI,
                // so the only place a permission dialog can be shown is the app itself.
                NotificationPermissionEffect()
                MegaPodcastPlayerApp(
                    pendingPodcastId = pendingPodcastId,
                    pendingEpisodeId = pendingEpisodeId,
                    onPendingPodcastHandled = {
                        pendingPodcastId = null
                        pendingEpisodeId = null
                    },
                    pendingSharedLink = pendingSharedLink,
                    onPendingSharedLinkHandled = { pendingSharedLink = null },
                    pendingOpenPlayer = pendingOpenPlayer,
                    onPendingOpenPlayerHandled = { pendingOpenPlayer = false },
                )
            }
        }
    }

    /**
     * Handles a notification tap that arrives while the app is already running.
     *
     * `singleTop` in the manifest is what routes it here instead of creating a second activity.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
    }

    /**
     * Pulls whatever navigation an intent is asking for out of it.
     *
     * Every field is written on every intent, including to null or false: a launch that asks for
     * nothing has to clear what the previous one asked for, or a share followed by a tap on the
     * launcher icon would reopen the add screen.
     *
     * @param intent the intent that started or resumed the activity.
     */
    private fun readIntent(intent: Intent) {
        pendingPodcastId = intent.podcastIdExtra()
        pendingEpisodeId = intent.episodeIdExtra()
        pendingSharedLink = intent.podcastLinkOrNull()
        pendingOpenPlayer = intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)
    }

    companion object {

        /** Extra carrying the id of the show a notification wants opened. */
        const val EXTRA_PODCAST_ID = "md.borisveriga.megapodcastplayer.extra.PODCAST_ID"

        /** Extra carrying the id of the episode within it, when the notification named just one. */
        const val EXTRA_EPISODE_ID = "md.borisveriga.megapodcastplayer.extra.EPISODE_ID"
    }
}

/** Reads [MainActivity.EXTRA_PODCAST_ID], treating a blank value as absent. */
private fun Intent.podcastIdExtra(): String? =
    getStringExtra(MainActivity.EXTRA_PODCAST_ID)?.takeIf { it.isNotBlank() }

/** Reads [MainActivity.EXTRA_EPISODE_ID], treating a blank value as absent. */
private fun Intent.episodeIdExtra(): String? =
    getStringExtra(MainActivity.EXTRA_EPISODE_ID)?.takeIf { it.isNotBlank() }
