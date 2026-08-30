package md.borisveriga.bpodcat

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint
import md.borisveriga.bpodcat.core.designsystem.theme.BPodcatTheme
import md.borisveriga.bpodcat.ui.BPodcatApp
import md.borisveriga.bpodcat.ui.NotificationPermissionEffect

/**
 * The single activity hosting BPodcat's Compose UI.
 *
 * Draws edge to edge so the Fold 7's inner display is used fully; window insets are consumed by the
 * individual screens' scaffolds.
 *
 * It is also the target of the new-episode notification. The show to open arrives as
 * [EXTRA_PODCAST_ID] on the launch intent rather than as a deep-link URI, which keeps the activity
 * unexported: navigation into the app stays something only the app itself can ask for.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * The show a notification asked to open, or null.
     *
     * Compose state rather than a flow so the composition simply reads it. It is cleared once
     * [BPodcatApp] has navigated, so a configuration change does not re-navigate the user away from
     * wherever they have since gone.
     */
    private var pendingPodcastId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pendingPodcastId = intent.podcastIdExtra()
        setContent {
            BPodcatTheme {
                // Asked for here rather than at the moment of posting: the worker runs with no UI,
                // so the only place a permission dialog can be shown is the app itself.
                NotificationPermissionEffect()
                BPodcatApp(
                    pendingPodcastId = pendingPodcastId,
                    onPendingPodcastHandled = { pendingPodcastId = null },
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
        pendingPodcastId = intent.podcastIdExtra()
    }

    companion object {

        /** Extra carrying the id of the show a notification wants opened. */
        const val EXTRA_PODCAST_ID = "md.borisveriga.bpodcat.extra.PODCAST_ID"
    }
}

/** Reads [MainActivity.EXTRA_PODCAST_ID], treating a blank value as absent. */
private fun Intent.podcastIdExtra(): String? =
    getStringExtra(MainActivity.EXTRA_PODCAST_ID)?.takeIf { it.isNotBlank() }
