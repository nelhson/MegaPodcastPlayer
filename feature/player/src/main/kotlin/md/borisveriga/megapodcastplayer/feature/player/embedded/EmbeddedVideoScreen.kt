package md.borisveriga.megapodcastplayer.feature.player.embedded

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import md.borisveriga.megapodcastplayer.core.designsystem.component.EmptyState
import md.borisveriga.megapodcastplayer.core.designsystem.component.MegaPodcastPlayerTopAppBar
import md.borisveriga.megapodcastplayer.core.designsystem.theme.FontScalePreviews
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.designsystem.theme.ThemePreviews
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.youTubeWatchUrl
import md.borisveriga.megapodcastplayer.feature.player.R

/** The picture's proportions: every YouTube embed is drawn in a sixteen-by-nine frame. */
private const val PICTURE_ASPECT_RATIO = 16f / 9f

/**
 * The embedded video screen: YouTube's own player on one episode, under the official source.
 *
 * Not a face of the app's player; see [EmbeddedVideoViewModel] for what it is instead. On screen it
 * is the picture in its frame with the episode's name under it, and nothing else: no transport,
 * because the embed draws its own, and no queue, because the embed has none to show.
 *
 * The screen leaves on its own when the episode turns out not to be a YouTube one — a route built
 * for an episode the library has since changed under it — since there is then nothing to embed.
 *
 * @param onBack leaves the screen.
 * @param modifier layout modifier.
 * @param viewModel injected by Hilt.
 */
@Composable
fun EmbeddedVideoRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EmbeddedVideoViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // On every start, not once: the app's player may have been started from the notification while
    // this screen was in the background, and coming back to the picture pauses it again.
    LifecycleStartEffect(Unit) {
        viewModel.enter()
        onStopOrDispose {}
    }

    // An episode that is loaded and has no video has nothing for this screen to show.
    val leave by rememberUpdatedState(onBack)
    LaunchedEffect(uiState.episode, uiState.videoId) {
        if (uiState.episode != null && uiState.videoId == null) leave()
    }

    EmbeddedVideoScreen(
        uiState = uiState,
        onBack = onBack,
        onEvent = viewModel::onEvent,
        onLeaveFront = viewModel::savePosition,
        modifier = modifier,
    )
}

/**
 * The embedded video screen, stateless.
 *
 * @param uiState what to show.
 * @param onBack leaves the screen.
 * @param onEvent receives what the embed's page reports; see [EmbeddedPlayerBridge].
 * @param onLeaveFront called when the screen leaves the front or is taken down, so the position
 *   reached can be written.
 * @param modifier layout modifier.
 * @param player draws the picture's frame; the real embed by default, and in a preview whatever a
 *   preview can draw, since a `WebView` cannot be rendered off a device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EmbeddedVideoScreen(
    uiState: EmbeddedVideoUiState,
    onBack: () -> Unit,
    onEvent: (EmbeddedPlayerEvent) -> Unit,
    onLeaveFront: () -> Unit,
    modifier: Modifier = Modifier,
    player: @Composable (videoId: String, startMs: Long, Modifier) -> Unit = { videoId, startMs, playerModifier ->
        EmbeddedPlayer(
            videoId = videoId,
            startMs = startMs,
            onEvent = onEvent,
            onLeaveFront = onLeaveFront,
            modifier = playerModifier,
        )
    },
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            MegaPodcastPlayerTopAppBar(
                title = uiState.episode?.title.orEmpty(),
                subtitle = uiState.showTitle.takeIf { it.isNotEmpty() },
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            val videoId = uiState.videoId
            val frame = Modifier
                .fillMaxWidth()
                .aspectRatio(PICTURE_ASPECT_RATIO)
                .background(Color.Black)
            when {
                videoId == null -> Box(modifier = frame)

                !uiState.isOnline -> EmptyState(
                    icon = Icons.Rounded.CloudOff,
                    title = stringResource(R.string.embedded_offline_title),
                    description = stringResource(R.string.embedded_offline_description),
                    modifier = Modifier.fillMaxWidth(),
                )

                uiState.error != null -> PlayerFailed(error = uiState.error, videoId = videoId)

                else -> player(videoId, uiState.startMs, frame)
            }

            Text(
                text = stringResource(R.string.embedded_player_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

/**
 * What stands in the picture's place when the embed would not play.
 *
 * The one failure worth its own sentence is the owner having turned embedding off — the realistic
 * one under the official source — and the one thing to offer then is YouTube itself, since the
 * video plays there and nowhere else.
 *
 * @param error why the embed would not play.
 * @param videoId the video, for the link to YouTube.
 */
@Composable
private fun PlayerFailed(error: EmbeddedPlayerError, videoId: String) {
    val context = LocalContext.current
    val description = when (error) {
        EmbeddedPlayerError.EMBEDDING_NOT_ALLOWED -> R.string.embedded_error_not_allowed
        EmbeddedPlayerError.NOT_FOUND, EmbeddedPlayerError.INVALID_VIDEO -> R.string.embedded_error_gone
        EmbeddedPlayerError.PLAYER_FAILED -> R.string.embedded_error_failed
    }
    EmptyState(
        icon = Icons.Rounded.ErrorOutline,
        title = stringResource(R.string.embedded_error_title),
        description = stringResource(description),
        actionLabel = stringResource(R.string.embedded_open_in_youtube),
        onAction = {
            // Whatever handles the link — the YouTube app, a browser — and the chooser if several
            // do. Nothing is played here on failure, so nothing here needs to know which.
            context.startActivity(Intent(Intent.ACTION_VIEW, youTubeWatchUrl(videoId).toUri()))
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * YouTube's embedded player, in a `WebView`, on one video.
 *
 * The one piece of this feature that is platform through and through, kept small for it. The page
 * it loads is [embeddedPlayerHtml]; what the page reports comes back through [EmbeddedPlayerBridge].
 *
 * The player follows the screen's lifecycle rather than running on under it: leaving the front
 * pauses the picture, because a picture nobody can see is bandwidth and battery for nothing, and
 * because the official source permits watching, not listening with the screen off. Coming back
 * leaves it paused, where the user left it. Taking the screen down destroys the `WebView`, which
 * is what stops its sound as well.
 *
 * @param videoId the video.
 * @param startMs where to start.
 * @param onEvent receives what the page reports.
 * @param onLeaveFront called as the screen leaves the front and as the view is taken down.
 * @param modifier layout modifier; the caller sizes the frame.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun EmbeddedPlayer(
    videoId: String,
    startMs: Long,
    onEvent: (EmbeddedPlayerEvent) -> Unit,
    onLeaveFront: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val events by rememberUpdatedState(onEvent)
    val leaveFront by rememberUpdatedState(onLeaveFront)
    // The one view, for the lifecycle observer below. Remembered, not hoisted: a configuration
    // change disposes this composable and its view together, and the new pair starts afresh.
    var webView by remember { mutableStateOf<WebView?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                webView = this
                // JavaScript is the whole point: the IFrame Player API is a script, and the bridge
                // back is one. The page is the app's own, built from a validated id, and loads
                // nothing but YouTube's API — which is as trusted as the embed it draws.
                settings.javaScriptEnabled = true
                // The gesture that opened this screen is the gesture; without this the embed would
                // sit on its poster waiting for a second tap on the picture itself.
                settings.mediaPlaybackRequiresUserGesture = false
                settings.domStorageEnabled = true
                // Keeps a tap on the embed's title or watermark inside this view rather than
                // handing it to the system; the page is the player and nothing else.
                webViewClient = WebViewClient()
                addJavascriptInterface(EmbeddedPlayerBridge { event -> events(event) }, EMBEDDED_BRIDGE_NAME)
                loadDataWithBaseURL(
                    EMBEDDED_PAGE_BASE_URL,
                    embeddedPlayerHtml(videoId, startSeconds = (startMs / MILLIS_PER_SECOND).toInt()),
                    "text/html",
                    "utf-8",
                    null,
                )
            }
        },
        onRelease = { view ->
            leaveFront()
            webView = null
            view.destroy()
        },
    )

    // The pause on leaving the front, and the resume of the view's own machinery on coming back.
    // Observed here rather than in `update`, which runs on recomposition and knows nothing about
    // the lifecycle.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    webView?.evaluateJavascript(EMBEDDED_PAUSE_SCRIPT, null)
                    webView?.onPause()
                    leaveFront()
                }

                Lifecycle.Event.ON_RESUME -> webView?.onResume()

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

private const val MILLIS_PER_SECOND = 1_000L

/** The screen with no network: the frame gives way to the explanation. */
@ThemePreviews
@FontScalePreviews
@Composable
internal fun EmbeddedVideoOfflinePreview() {
    MegaPodcastPlayerTheme {
        EmbeddedVideoScreen(
            uiState = EmbeddedVideoUiState(
                episode = previewEpisode,
                showTitle = "Generic",
                videoId = "niTJ2221aS8",
                isOnline = false,
            ),
            onBack = {},
            onEvent = {},
            onLeaveFront = {},
        )
    }
}

/** The screen on a video whose owner has turned embedding off. */
@ThemePreviews
@FontScalePreviews
@Composable
internal fun EmbeddedVideoNotAllowedPreview() {
    MegaPodcastPlayerTheme {
        EmbeddedVideoScreen(
            uiState = EmbeddedVideoUiState(
                episode = previewEpisode,
                showTitle = "Generic",
                videoId = "niTJ2221aS8",
                error = EmbeddedPlayerError.EMBEDDING_NOT_ALLOWED,
            ),
            onBack = {},
            onEvent = {},
            onLeaveFront = {},
        )
    }
}

/** The screen with the player up; a preview draws a dark frame where the `WebView` would be. */
@ThemePreviews
@FontScalePreviews
@Composable
internal fun EmbeddedVideoPlayerPreview() {
    MegaPodcastPlayerTheme {
        EmbeddedVideoScreen(
            uiState = EmbeddedVideoUiState(
                episode = previewEpisode,
                showTitle = "Generic",
                videoId = "niTJ2221aS8",
            ),
            onBack = {},
            onEvent = {},
            onLeaveFront = {},
            player = { _, _, frame -> Box(modifier = frame.background(Color.DarkGray)) },
        )
    }
}

private val previewEpisode = Episode(
    id = "e1",
    podcastId = "p1",
    guid = "yt:video:niTJ2221aS8",
    title = "Оправдан ли запрет на соцсети для детей?",
    description = "",
    audioUrl = "youtube://video/niTJ2221aS8",
    artworkUrl = null,
    durationMs = null,
    publishedAt = Instant.parse("2026-08-26T18:24:30Z"),
    sizeBytes = null,
)
