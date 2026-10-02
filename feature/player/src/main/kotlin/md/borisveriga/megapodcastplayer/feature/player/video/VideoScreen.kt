package md.borisveriga.megapodcastplayer.feature.player.video

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.SurfaceView
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import md.borisveriga.megapodcastplayer.core.common.format.formatCountdown
import md.borisveriga.megapodcastplayer.core.common.format.formatPosition
import md.borisveriga.megapodcastplayer.core.common.format.formatSpeed
import md.borisveriga.megapodcastplayer.core.designsystem.component.LabelledWaveScrubber
import md.borisveriga.megapodcastplayer.core.designsystem.component.PlayPauseButton
import md.borisveriga.megapodcastplayer.core.designsystem.component.PlayPauseSize
import md.borisveriga.megapodcastplayer.core.designsystem.theme.FontScalePreviews
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.designsystem.theme.Motion
import md.borisveriga.megapodcastplayer.core.designsystem.theme.ThemePreviews
import md.borisveriga.megapodcastplayer.core.media.PlaybackState
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings
import md.borisveriga.megapodcastplayer.core.model.VideoDownload
import md.borisveriga.megapodcastplayer.core.model.VideoQuality
import md.borisveriga.megapodcastplayer.feature.player.R
import md.borisveriga.megapodcastplayer.feature.player.SkipGlyph
import md.borisveriga.megapodcastplayer.feature.player.SpeedSheet
import md.borisveriga.megapodcastplayer.feature.player.previewPlayback

/**
 * The video screen: the picture of the YouTube episode playing, with the transport under it.
 *
 * It is the player's other face rather than a second player. The service keeps playing the same
 * episode at the same position before, during and after; what this screen adds is a surface, and
 * an ask for the picture each time it starts. It does not end the picture when it goes: that is
 * the shell's, which knows what this screen cannot — whether the picture is going on to the
 * collapsed bar or going away. See `PlayerSheetScaffold`.
 *
 * There are two ways to leave and they mean different things. *Minimise* puts the video away: the
 * episode carries on in the collapsed bar, picture and all, and a tap on the bar comes back here.
 * *Switch to audio* changes what the player is: the picture stops, and the caller opens the audio
 * player in its place. Which of the two the player is in is the caller's to remember; this screen
 * only reports the choice.
 *
 * A tap on the picture hides everything but the picture, and a tap anywhere brings it back.
 *
 * @param onCollapse puts the video away; playback carries on, and the bar reopens this screen.
 * @param onListen leaves for the audio player.
 * @param modifier layout modifier.
 * @param viewModel the picture's view model. The shell passes the instance its collapsed bar draws
 *   from, so the picture is one thing handed between two surfaces; the default is a screen of its
 *   own, for a caller with no bar.
 */
@Composable
fun VideoRoute(
    onCollapse: () -> Unit,
    onListen: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VideoViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Asked on every start, not once: coming back to the front finds the picture handed back to
    // sound, and arriving here is also the retry for one the service refused. Nothing is undone on
    // the way out — minimising must leave the picture playing for the bar.
    LifecycleStartEffect(Unit) {
        viewModel.enter()
        onStopOrDispose {}
    }

    // The screen is about one episode's picture. When the player has moved on to something without
    // one, or has emptied, there is nothing left here to show. Not before the service has answered:
    // the first frame reads as "nothing loaded" and would send the user straight back out.
    LaunchedEffect(uiState.playback.isConnected, uiState.canWatch) {
        if (uiState.playback.isConnected && !uiState.canWatch) {
            // Said here as well as left to the shell: the queue may still hold episodes in their
            // video flavour, and going back to sound is what turns them back.
            viewModel.exit()
            onCollapse()
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val refusedText = stringResource(R.string.video_refused)
    LaunchedEffect(uiState.refused) {
        if (!uiState.refused) return@LaunchedEffect
        snackbarHostState.showSnackbar(refusedText)
        viewModel.onRefusalShown()
    }

    val downloadMessage = uiState.downloadMessage?.let { videoDownloadMessageText(it) }
    LaunchedEffect(uiState.downloadMessage) {
        if (downloadMessage == null) return@LaunchedEffect
        snackbarHostState.showSnackbar(downloadMessage)
        viewModel.onDownloadMessageShown()
    }

    var speedOpen by rememberSaveable { mutableStateOf(false) }
    var qualityOpen by rememberSaveable { mutableStateOf(false) }
    var downloadOpen by rememberSaveable { mutableStateOf(false) }

    if (speedOpen) {
        SpeedSheet(
            speed = uiState.playback.speed,
            onPreview = viewModel::previewSpeed,
            onCommit = viewModel::setSpeed,
            onDismiss = { speedOpen = false },
        )
    }
    if (qualityOpen) {
        QualitySheet(
            qualities = uiState.qualities,
            selected = uiState.qualityShown,
            failed = uiState.qualitiesFailed,
            onSelect = { quality ->
                qualityOpen = false
                viewModel.setQuality(quality)
            },
            onDismiss = { qualityOpen = false },
        )
    }
    if (downloadOpen) {
        DownloadVideoSheet(
            qualities = uiState.qualities,
            failed = uiState.qualitiesFailed,
            download = uiState.videoDownload,
            onDownload = { quality ->
                downloadOpen = false
                viewModel.downloadVideo(quality)
            },
            onDelete = {
                downloadOpen = false
                viewModel.deleteVideoDownload()
            },
            onDismiss = { downloadOpen = false },
        )
    }

    // Whether the names, the transport and the buttons are on screen, or only the picture — kept
    // as *the shape they were hidden in*, or null while they show. Saved, so a cleared picture
    // survives the Fold opening; but a turn recreates the activity and restores whatever was
    // saved, and landscape hides its controls on a timer, so a plain flag would turn back to
    // portrait as a black page where the player was. Hidden in the other shape reads as showing.
    val landscape = isLandscape()
    var hiddenInLandscape by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val controlsVisible = hiddenInLandscape != landscape

    FullscreenEffects(
        landscape = landscape,
        immersive = landscape || !controlsVisible,
        keepScreenOn = uiState.playback.isPlaying,
    )

    VideoScreen(
        uiState = uiState,
        surface = { surfaceModifier ->
            VideoSurface(
                onAttach = viewModel::attachSurface,
                onDetach = viewModel::detachSurface,
                modifier = surfaceModifier,
            )
        },
        actions = VideoActions(
            onPlayPause = viewModel::togglePlayPause,
            onSeek = viewModel::seekTo,
            onSkipForward = viewModel::skipForward,
            onSkipBack = viewModel::skipBack,
            onSkipToNext = viewModel::skipToNext,
            onSkipToPrevious = viewModel::skipToPrevious,
            onOpenSpeed = { speedOpen = true },
            onOpenQuality = { qualityOpen = true },
            onOpenDownload = { downloadOpen = true },
            onCollapse = onCollapse,
            // Before the mode change it leads to has been stored and read back, so the picture
            // stops with the tap rather than a moment after the audio player has opened.
            onListen = {
                viewModel.exit()
                onListen()
            },
        ),
        modifier = modifier,
        controlsVisible = controlsVisible,
        onControlsVisibleChange = { visible -> hiddenInLandscape = if (visible) null else landscape },
        snackbarHostState = snackbarHostState,
    )
}

/**
 * Everything the controls on the video screen can do, gathered so the layouts take one thing.
 *
 * @property onPlayPause play/pause handler.
 * @property onSeek absolute-seek handler.
 * @property onSkipForward skip-ahead handler.
 * @property onSkipBack skip-back handler.
 * @property onSkipToNext next-episode handler.
 * @property onSkipToPrevious previous-episode handler.
 * @property onOpenSpeed opens the speed sheet.
 * @property onOpenQuality opens the quality sheet.
 * @property onOpenDownload opens the download sheet.
 * @property onCollapse puts the video away behind the collapsed bar.
 * @property onListen leaves for the audio player.
 */
data class VideoActions(
    val onPlayPause: () -> Unit = {},
    val onSeek: (Long) -> Unit = {},
    val onSkipForward: () -> Unit = {},
    val onSkipBack: () -> Unit = {},
    val onSkipToNext: () -> Unit = {},
    val onSkipToPrevious: () -> Unit = {},
    val onOpenSpeed: () -> Unit = {},
    val onOpenQuality: () -> Unit = {},
    val onOpenDownload: () -> Unit = {},
    val onCollapse: () -> Unit = {},
    val onListen: () -> Unit = {},
)

/**
 * The snackbar sentence for what a download request did.
 *
 * @param message what happened.
 */
@Composable
private fun videoDownloadMessageText(message: VideoDownloadMessage): String = when (message) {
    is VideoDownloadMessage.Queued -> stringResource(
        R.string.video_download_message_queued,
        stringResource(R.string.video_quality_label, message.quality.height),
    )

    VideoDownloadMessage.Deleted -> stringResource(R.string.video_download_message_deleted)

    VideoDownloadMessage.Cancelled -> stringResource(R.string.video_download_message_cancelled)

    VideoDownloadMessage.Failed -> stringResource(R.string.video_download_message_failed)
}

/**
 * The screen, in whichever of its two shapes the window calls for.
 *
 * Portrait is a page: the episode's name under the top bar, the picture centred in the room
 * between that and the transport at the bottom. Landscape is the picture and nothing else, with
 * the transport laid over it and hidden again a few seconds after the last touch.
 *
 * In both, [controlsVisible] says whether anything but the picture is drawn, and a tap flips it.
 *
 * The surface is a slot rather than drawn here, so a preview — and the golden — can put a plain
 * box where a `SurfaceView` bound to the player would be.
 *
 * @param uiState what to render.
 * @param surface draws the player's picture into the modifier it is given.
 * @param actions the controls.
 * @param modifier layout modifier.
 * @param controlsVisible false when only the picture is to be shown.
 * @param onControlsVisibleChange asks for the controls to be shown or hidden; a tap does.
 * @param snackbarHostState where a refusal is shown.
 */
@Composable
internal fun VideoScreen(
    uiState: VideoUiState,
    surface: @Composable (Modifier) -> Unit,
    actions: VideoActions,
    modifier: Modifier = Modifier,
    controlsVisible: Boolean = true,
    onControlsVisibleChange: (Boolean) -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val controls = ControlsVisibility(controlsVisible, onControlsVisibleChange)
    if (isLandscape()) {
        LandscapeVideo(uiState, surface, actions, controls, snackbarHostState, modifier)
    } else {
        PortraitVideo(uiState, surface, actions, controls, snackbarHostState, modifier)
    }
}

/**
 * Whether the controls are shown, and the way to change that, passed to the layouts as one thing.
 *
 * @property visible false when only the picture is drawn.
 * @property onChange asks for the controls to be shown or hidden.
 */
private class ControlsVisibility(val visible: Boolean, val onChange: (Boolean) -> Unit)

/** Whether the window is wider than it is tall, which is what chooses the screen's shape. */
@Composable
private fun isLandscape(): Boolean =
    LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

/**
 * The page shape.
 *
 * A tap on the picture clears the page: the top bar, the names and the transport fade out and the
 * ground under them goes to black, leaving the picture where it was. Faded rather than removed, and
 * laid out against insets that ignore the system bars coming and going, so that nothing the picture
 * is measured against changes — a picture that jumped as its surroundings left would be the most
 * visible thing about the gesture. While cleared, the whole screen is one target that brings
 * everything back.
 *
 * Nothing hides on a timer here, unlike landscape: the page's controls are beside the picture, not
 * over it, so they are only in the way when the user says they are.
 *
 * @param uiState what to render.
 * @param surface draws the picture.
 * @param actions the controls.
 * @param controls whether the page is cleared, and the way to change it.
 * @param snackbarHostState where a refusal is shown.
 * @param modifier layout modifier.
 */
// systemBarsIgnoringVisibility is the experimental half: it is the only inset that stays put while
// the bars hide, which is the whole of why it is used.
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PortraitVideo(
    uiState: VideoUiState,
    surface: @Composable (Modifier) -> Unit,
    actions: VideoActions,
    controls: ControlsVisibility,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    // One number for the whole change, read only in draw, so the fade costs no recomposition.
    val shown = animateFloatAsState(
        targetValue = if (controls.visible) 1f else 0f,
        animationSpec = Motion.fade(),
        label = "video controls",
    )
    // Furniture that has faded out is also taken out of the spoken tree and out of focus order: a
    // button nobody can see is not one a screen reader should land on, or a keyboard should press.
    val furniture = Modifier
        .graphicsLayer { alpha = shown.value }
        .focusProperties { canFocus = controls.visible }
        .then(if (controls.visible) Modifier else Modifier.clearAndSetSemantics { })
    val steadyBars = WindowInsets.systemBarsIgnoringVisibility

    Box(modifier = modifier.background(MaterialTheme.colorScheme.background)) {
        // The black the page fades to, under the page rather than a colour animated through it.
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { alpha = 1f - shown.value }
                .background(Color.Black),
        )
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            contentWindowInsets = steadyBars,
            topBar = {
                TopAppBar(
                    title = {},
                    modifier = furniture,
                    navigationIcon = { CollapseButton(actions.onCollapse) },
                    actions = { ListenButton(actions.onListen) },
                    windowInsets = steadyBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
            ) {
                EpisodeTitles(
                    playback = uiState.playback,
                    modifier = furniture
                        .fillMaxWidth()
                        .padding(horizontal = MegaPodcastPlayerTheme.spacing.screenHorizontal),
                )
                // The picture floats in whatever is left between the names and the transport,
                // rather than sitting against the top bar: on a tall screen that puts it near the
                // middle, where the eye already is. The whole of that room takes the tap, not only
                // the picture: the black beside a narrow picture is as much "the video" to a thumb.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClickLabel = stringResource(R.string.video_hide_controls),
                        ) { controls.onChange(false) }
                        .padding(vertical = MegaPodcastPlayerTheme.spacing.md),
                    contentAlignment = Alignment.Center,
                ) {
                    VideoFrame(playback = uiState.playback, surface = surface, modifier = Modifier.fillMaxWidth())
                }
                VideoControls(
                    uiState = uiState,
                    actions = actions,
                    modifier = furniture.padding(
                        start = MegaPodcastPlayerTheme.spacing.screenHorizontal,
                        end = MegaPodcastPlayerTheme.spacing.screenHorizontal,
                        bottom = MegaPodcastPlayerTheme.spacing.lg,
                    ),
                )
            }
        }
        if (!controls.visible) {
            // Over everything, so a tap where a faded button still sits shows the controls rather
            // than pressing something invisible.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = stringResource(R.string.video_show_controls),
                    ) { controls.onChange(true) },
            )
        }
    }
}

/**
 * The full-screen shape.
 *
 * The whole surface is one tap target that shows and hides the controls; while playing they go
 * away on their own after [CONTROLS_TIMEOUT_MS], and stay while paused, because a paused picture is
 * one the user is about to do something to.
 *
 * @param uiState what to render.
 * @param surface draws the picture.
 * @param actions the controls.
 * @param controls whether the overlay is up, and the way to change it.
 * @param snackbarHostState where a refusal is shown.
 * @param modifier layout modifier.
 */
@Composable
private fun LandscapeVideo(
    uiState: VideoUiState,
    surface: @Composable (Modifier) -> Unit,
    actions: VideoActions,
    controls: ControlsVisibility,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val controlsVisible = controls.visible
    LaunchedEffect(controlsVisible, uiState.playback.isPlaying) {
        if (controlsVisible && uiState.playback.isPlaying) {
            delay(CONTROLS_TIMEOUT_MS)
            controls.onChange(false)
        }
    }
    val toggleLabel = stringResource(
        if (controlsVisible) R.string.video_hide_controls else R.string.video_show_controls,
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                // No ripple: a flash across the whole picture on every tap would be the most
                // visible thing on the screen.
                indication = null,
                onClickLabel = toggleLabel,
            ) { controls.onChange(!controlsVisible) },
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            VideoFrame(playback = uiState.playback, surface = surface)
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(SCRIM, Color.Transparent, Color.Transparent, SCRIM),
                            ),
                        )
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(horizontal = MegaPodcastPlayerTheme.spacing.md),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CollapseButton(actions.onCollapse)
                        EpisodeTitles(
                            playback = uiState.playback,
                            modifier = Modifier.weight(1f),
                            compact = true,
                        )
                        ListenButton(actions.onListen)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    VideoControls(
                        uiState = uiState,
                        actions = actions,
                        modifier = Modifier.padding(bottom = MegaPodcastPlayerTheme.spacing.sm),
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        )
    }
}

/**
 * The picture, in a box of its own shape.
 *
 * The box takes the picture's measured proportions once the decoder has reported them, and 16:9
 * until then — every YouTube rendition this app plays is 16:9, so the guess is right far more often
 * than not and a box of the wrong shape never jumps. Black under and over the surface until the
 * first frame, so nothing of what was behind shows through the hole a `SurfaceView` punches.
 *
 * @param playback what the player is doing; the shape, the shutter and the spinner come from it.
 * @param surface draws the picture.
 * @param modifier layout modifier; the caller decides which edge the box fills.
 */
@Composable
private fun VideoFrame(
    playback: PlaybackState,
    surface: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measured = playback.videoAspectRatio
    Box(
        modifier = modifier
            .aspectRatio(measured ?: DEFAULT_ASPECT_RATIO)
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        surface(Modifier.fillMaxSize())
        if (!playback.isVideo || measured == null) {
            // The shutter: over the surface, which draws whatever it last held until the picture
            // starts — including a frame of the previous episode.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            )
        }
        if (playback.isBuffering || !playback.isVideo) {
            CircularProgressIndicator(color = Color.White)
        }
    }
}

/**
 * The episode's title and its show's.
 *
 * @param playback where the names come from.
 * @param modifier layout modifier.
 * @param compact true over the picture, where there is one line's worth of room between the two
 *   buttons and the colour is the overlay's rather than the page's.
 */
@Composable
private fun EpisodeTitles(
    playback: PlaybackState,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Column(modifier = modifier) {
        Text(
            text = playback.title,
            style = if (compact) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.headlineSmall
            },
            maxLines = if (compact) 1 else 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = playback.showTitle,
            style = MaterialTheme.typography.bodyMedium,
            color = if (compact) {
                LocalContentColor.current.copy(alpha = SECONDARY_TEXT_ALPHA)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The scrubber, the transport and the two settings buttons, stacked.
 *
 * @param uiState what to render.
 * @param actions the transport.
 * @param modifier layout modifier.
 */
@Composable
private fun VideoControls(
    uiState: VideoUiState,
    actions: VideoActions,
    modifier: Modifier = Modifier,
) {
    val playback = uiState.playback
    val durationMs = playback.knownDurationMs

    Column(modifier = modifier.fillMaxWidth()) {
        LabelledWaveScrubber(
            positionMs = playback.positionMs,
            // Zero renders an inert, empty rail, the right face for a duration not yet read.
            durationMs = durationMs ?: 0L,
            playing = playback.isPlaying,
            onSeek = actions.onSeek,
            elapsedLabel = formatPosition(playback.positionMs),
            remainingLabel = formatCountdown(durationMs, playback.positionMs)
                ?: stringResource(R.string.player_unknown_duration),
            enabled = durationMs != null,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MegaPodcastPlayerTheme.spacing.sm),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = actions.onSkipToPrevious) {
                Icon(
                    imageVector = Icons.Rounded.SkipPrevious,
                    contentDescription = stringResource(R.string.player_previous),
                )
            }
            IconButton(onClick = actions.onSkipBack) {
                SkipGlyph(skipMs = uiState.settings.skipBackMs, forward = false)
            }
            PlayPauseButton(
                playing = playback.isPlaying,
                onToggle = { actions.onPlayPause() },
                size = PlayPauseSize.Hero,
                buffering = playback.isBuffering,
                modifier = Modifier.padding(horizontal = MegaPodcastPlayerTheme.spacing.md),
            )
            IconButton(onClick = actions.onSkipForward) {
                SkipGlyph(skipMs = uiState.settings.skipForwardMs, forward = true)
            }
            IconButton(onClick = actions.onSkipToNext) {
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = stringResource(R.string.player_next),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(
                MegaPodcastPlayerTheme.spacing.sm,
                Alignment.CenterHorizontally,
            ),
        ) {
            // Both labels are their own text and both are doors, so both say so when spoken —
            // the shape the player sheet's speed button set.
            val speed = formatSpeed(playback.speed)
            val speedDescription = stringResource(R.string.player_speed, speed)
            TextButton(
                onClick = actions.onOpenSpeed,
                modifier = Modifier.semantics { contentDescription = speedDescription },
            ) {
                Text(text = speed)
            }

            val quality = stringResource(R.string.video_quality_label, uiState.qualityShown.height)
            val qualityDescription = stringResource(R.string.video_quality_button, quality)
            TextButton(
                onClick = actions.onOpenQuality,
                modifier = Modifier.semantics { contentDescription = qualityDescription },
            ) {
                Text(text = quality)
            }

            DownloadButton(download = uiState.videoDownload, onClick = actions.onOpenDownload)
        }
    }
}

/**
 * The door to the download sheet, drawn as what the phone holds of this video.
 *
 * An arrow when nothing is kept, or the last attempt failed; the in-progress glyph while a copy is
 * on its way; a tick once it is here. The icon is the whole of the visible label, so the spoken one
 * carries the percentage and the quality the glyph leaves out.
 *
 * @param download the episode's downloaded video, if any.
 * @param onClick opens the sheet.
 */
@Composable
private fun DownloadButton(download: VideoDownload?, onClick: () -> Unit) {
    val quality = download?.let { stringResource(R.string.video_quality_label, it.quality.height) }
    val (icon, description) = when {
        download == null || quality == null ->
            Icons.Rounded.Download to stringResource(R.string.video_download_button)

        download.isComplete ->
            Icons.Rounded.DownloadDone to stringResource(R.string.video_download_button_done, quality)

        download.state == DownloadState.FAILED ->
            Icons.Rounded.Download to stringResource(R.string.video_download_button)

        else -> Icons.Rounded.Downloading to stringResource(
            R.string.video_download_button_progress,
            download.percent.roundToInt(),
        )
    }
    // Primary, like the speed and quality buttons beside it: the three are the row's settings,
    // set apart from the neutral transport above them.
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary),
    ) {
        Icon(imageVector = icon, contentDescription = description)
    }
}

/**
 * Puts the video away, which keeps the episode playing behind the collapsed bar.
 *
 * A downward chevron rather than a back arrow: it is the glyph the audio player closes with, and
 * what it does here is the same thing — the player shrinks to its bar, it does not go anywhere.
 *
 * @param onCollapse puts the video away.
 */
@Composable
private fun CollapseButton(onCollapse: () -> Unit) {
    IconButton(onClick = onCollapse) {
        Icon(
            imageVector = Icons.Rounded.KeyboardArrowDown,
            contentDescription = stringResource(R.string.video_collapse),
        )
    }
}

/**
 * Leaves for the audio player: the mirror of the *Watch* button there.
 *
 * @param onListen switches the player to audio.
 */
@Composable
private fun ListenButton(onListen: () -> Unit) {
    IconButton(onClick = onListen) {
        Icon(
            imageVector = Icons.Rounded.Headphones,
            contentDescription = stringResource(R.string.video_listen),
        )
    }
}

/**
 * The player's canvas.
 *
 * A plain `SurfaceView` handed to the player through the controller, rather than a Media3 view:
 * the controls are this app's own and the one thing wanted from Media3 here is the pixels. Handed
 * over when made and taken back when released, so the player is never left drawing into a surface
 * that no longer exists.
 *
 * @param onAttach receives the surface once it exists.
 * @param onDetach receives it again before it goes.
 * @param modifier layout modifier.
 */
@Composable
private fun VideoSurface(
    onAttach: (SurfaceView) -> Unit,
    onDetach: (SurfaceView) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context -> SurfaceView(context).also(onAttach) },
        modifier = modifier,
        onRelease = onDetach,
    )
}

/**
 * The player's canvas at the size of the collapsed bar.
 *
 * A `TextureView` where the screen has a `SurfaceView`, because this picture is a piece of a bar:
 * it is clipped to the artwork's corners, fades with the bar and is pulled down with it, and a
 * surface behind a hole in the window does none of those. Handed over when made and taken back when
 * released, like [VideoSurface].
 *
 * @param onAttach receives the texture once it exists.
 * @param onDetach receives it again before it goes.
 * @param modifier layout modifier.
 */
@Composable
internal fun VideoTexture(
    onAttach: (TextureView) -> Unit,
    onDetach: (TextureView) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context -> TextureView(context).also(onAttach) },
        modifier = modifier,
        onRelease = onDetach,
    )
}

/**
 * What the screen does to the window and the activity for as long as it is up.
 *
 * Four things, each undone on the way out. The screen stays on while the picture moves. The
 * orientation follows the sensor even with auto-rotate off, because turning the phone is how a
 * video is made big — and it is undone on leaving so the rest of the app is back under the user's
 * setting. The system bars go whenever only the picture is wanted — always in landscape, and in
 * portrait once the controls have been tapped away — coming back with a swipe. And in landscape
 * the picture is let run under the cutout; there is no content there to lose to it.
 *
 * @param landscape whether the window is currently wider than tall.
 * @param immersive whether the system bars should be out of the picture's way.
 * @param keepScreenOn whether the picture is moving.
 */
@Composable
private fun FullscreenEffects(landscape: Boolean, immersive: Boolean, keepScreenOn: Boolean) {
    val view = LocalView.current
    val activity = LocalActivity.current

    DisposableEffect(view, keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // The orientation found on arrival, saved so it survives the activity being recreated by the
    // very rotation this screen allows; read again after one, it would be this screen's own SENSOR.
    val arrivalOrientation = rememberSaveable {
        activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    DisposableEffect(activity) {
        val host = activity ?: return@DisposableEffect onDispose {}
        host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        onDispose {
            // Only on the way out. Restoring on a rotation would ask the dying activity for the
            // old orientation for a moment, and with auto-rotate off could turn the screen back.
            if (!host.isChangingConfigurations) host.requestedOrientation = arrivalOrientation
        }
    }

    DisposableEffect(activity, view, immersive) {
        val window = activity?.window
        if (window == null || !immersive) return@DisposableEffect onDispose {}
        val controller = WindowInsetsControllerCompat(window, view)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }

    DisposableEffect(activity, landscape) {
        val window = activity?.window
        if (window == null || !landscape) return@DisposableEffect onDispose {}
        val previousCutoutMode = window.attributes.layoutInDisplayCutoutMode
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        onDispose {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = previousCutoutMode
            }
        }
    }
}

/** The shape assumed until the decoder reports one; every YouTube rendition here is 16:9. */
private const val DEFAULT_ASPECT_RATIO = 16f / 9f

/** How long the landscape controls stay after the last touch while the picture is moving. */
private const val CONTROLS_TIMEOUT_MS = 3_000L

/** The overlay's darkening at the top and bottom edges, where the controls sit over the picture. */
private val SCRIM = Color.Black.copy(alpha = 0.6f)

/** How much the show's name recedes behind the episode's over the picture. */
private const val SECONDARY_TEXT_ALPHA = 0.8f

/**
 * The video screen in portrait, on the sample episode, in both schemes and at three font scales.
 *
 * The surface is a grey box: a `SurfaceView` has no player to draw from in a preview, and the
 * golden guards the page around the picture, not the picture.
 */
@ThemePreviews
@FontScalePreviews
@Composable
internal fun VideoScreenPreview() {
    MegaPodcastPlayerTheme {
        VideoScreen(
            uiState = VideoUiState(
                playback = previewPlayback.copy(
                    youTubeVideoId = "niTJ2221aS8",
                    videoQuality = VideoQuality(PREVIEW_HEIGHT),
                    videoWidth = PREVIEW_WIDTH,
                    videoHeight = PREVIEW_HEIGHT,
                ),
                settings = PlaybackSettings(),
                qualities = listOf(VideoQuality(PREVIEW_HEIGHT), VideoQuality(1080)),
            ),
            surface = { surfaceModifier -> Box(modifier = surfaceModifier.background(Color.DarkGray)) },
            actions = VideoActions(),
        )
    }
}

/** The sample picture's size: a 720p frame. */
private const val PREVIEW_WIDTH = 1280

/** The rendition height the previews and goldens show, the default one. */
private const val PREVIEW_HEIGHT = 720
