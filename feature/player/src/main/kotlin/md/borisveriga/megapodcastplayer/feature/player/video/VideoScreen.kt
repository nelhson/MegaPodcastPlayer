package md.borisveriga.megapodcastplayer.feature.player.video

import android.content.pm.ActivityInfo
import android.view.SurfaceView
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import md.borisveriga.megapodcastplayer.core.common.format.formatCountdown
import md.borisveriga.megapodcastplayer.core.common.format.formatPosition
import md.borisveriga.megapodcastplayer.core.common.format.formatSpeed
import md.borisveriga.megapodcastplayer.core.designsystem.component.DownloadFolderPicker
import md.borisveriga.megapodcastplayer.core.designsystem.component.LabelledWaveScrubber
import md.borisveriga.megapodcastplayer.core.designsystem.component.PlayPauseButton
import md.borisveriga.megapodcastplayer.core.designsystem.component.PlayPauseSize
import md.borisveriga.megapodcastplayer.core.designsystem.component.PodcastArtwork
import md.borisveriga.megapodcastplayer.core.designsystem.component.VideoDownloadSheet
import md.borisveriga.megapodcastplayer.core.designsystem.component.rememberDownloadFolderChoice
import md.borisveriga.megapodcastplayer.core.designsystem.theme.FontScalePreviews
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.designsystem.theme.Motion
import md.borisveriga.megapodcastplayer.core.designsystem.theme.ThemePreviews
import md.borisveriga.megapodcastplayer.core.media.PlaybackState
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings
import md.borisveriga.megapodcastplayer.core.model.PlayerMode
import md.borisveriga.megapodcastplayer.core.model.VideoDownload
import md.borisveriga.megapodcastplayer.core.model.VideoQuality
import md.borisveriga.megapodcastplayer.core.model.format.formatVideoQuality
import md.borisveriga.megapodcastplayer.feature.player.ModeSwitch
import md.borisveriga.megapodcastplayer.feature.player.R
import md.borisveriga.megapodcastplayer.feature.player.SkipGlyph
import md.borisveriga.megapodcastplayer.feature.player.SpeedSheet
import md.borisveriga.megapodcastplayer.feature.player.previewPlayback
import md.borisveriga.megapodcastplayer.feature.player.toText

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
 * The *Audio* half of the top bar's switch changes what the player is: the picture stops, and the
 * caller opens the audio player in its place. Which of the two the player is in is the caller's to
 * remember; this screen only reports the choice.
 *
 * A tap on the picture hides everything but the picture, and a tap anywhere brings it back. So
 * does Back, on a cleared page: it returns the controls first, and minimises only from a page that
 * has them.
 *
 * The frame is never an unexplained black box. Until the player has drawn a frame it shows the
 * episode's artwork, and when the picture cannot be shown it says so there, with the two things
 * that can be done about it.
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
    // A playback error is said here while this screen is up: the sheet that usually says it is
    // hidden behind the picture, and an error nobody is shown reads as the app having frozen.
    // LocalResources rather than the context's, so a configuration change invalidates the read.
    val resources = LocalResources.current
    LaunchedEffect(uiState.playback.error) {
        val playback = uiState.playback
        val error = playback.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(error.toText(resources, playback.errorMessage))
        viewModel.onErrorShown()
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
    val episodeId = uiState.playback.episodeId
    if (downloadOpen && episodeId != null) {
        // Where the download goes, chosen in the sheet: the episode's folder, else the default,
        // until the user picks another.
        val folderChoice = rememberDownloadFolderChoice(uiState.downloadFolders, episodeId)
        VideoDownloadSheet(
            qualities = uiState.qualities,
            failed = uiState.qualitiesFailed,
            download = uiState.videoDownload,
            onDownload = { quality ->
                downloadOpen = false
                viewModel.downloadVideo(quality, folderChoice.destination)
            },
            folderPicker = {
                DownloadFolderPicker(
                    folders = uiState.downloadFolders,
                    selectedFolderId = folderChoice.folderId,
                    onSelect = folderChoice.onChoose,
                )
            },
            onDelete = {
                downloadOpen = false
                viewModel.deleteVideoDownload()
            },
            onDismiss = { downloadOpen = false },
        )
    }

    // Whether the user asked for the picture to fill the screen, which holds the window in
    // landscape whichever way the phone is held. Saved, because asking is what recreates the
    // activity.
    var fullscreen by rememberSaveable { mutableStateOf(false) }

    // Whether the names, the transport and the buttons are on screen, or only the picture — kept
    // as *the shape they were hidden in*, or null while they show. Saved, so a cleared picture
    // survives the Fold opening; but a turn recreates the activity and restores whatever was
    // saved, and the overlay hides its controls on a timer, so a plain flag would turn back to
    // the page as a black one where the player was. Hidden in the other shape reads as showing.
    val overlay = showsOverlay(fullscreen)
    var hiddenInOverlay by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val controlsVisible = hiddenInOverlay != overlay

    FullscreenEffects(
        overlay = overlay,
        immersive = overlay || !controlsVisible,
        keepScreenOn = uiState.playback.isPlaying,
        lockLandscape = fullscreen,
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
            onRetry = viewModel::enter,
            onToggleFullscreen = { fullscreen = !fullscreen },
        ),
        modifier = modifier,
        fullscreen = fullscreen,
        controlsVisible = controlsVisible,
        onControlsVisibleChange = { visible -> hiddenInOverlay = if (visible) null else overlay },
        snackbarHostState = snackbarHostState,
        // A sheet is the user doing something to the picture; its controls stay for when it closes.
        holdControls = speedOpen || qualityOpen || downloadOpen,
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
 * @property onRetry asks for the picture again, after it could not be shown.
 * @property onToggleFullscreen holds the window in landscape, or lets it go again.
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
    val onRetry: () -> Unit = {},
    val onToggleFullscreen: () -> Unit = {},
)

/**
 * The snackbar sentence for what a download request did.
 *
 * @param message what happened.
 */
@Composable
private fun videoDownloadMessageText(message: VideoDownloadMessage): String = when (message) {
    is VideoDownloadMessage.Queued -> stringResource(
        if (message.waitingForWifi) {
            R.string.video_download_message_waiting_for_wifi
        } else {
            R.string.video_download_message_queued
        },
        formatVideoQuality(message.quality.height),
    )

    VideoDownloadMessage.Deleted -> stringResource(R.string.video_download_message_deleted)

    VideoDownloadMessage.Cancelled -> stringResource(R.string.video_download_message_cancelled)

    VideoDownloadMessage.Failed -> stringResource(R.string.video_download_message_failed)
}

/**
 * The screen, in whichever of its two shapes the window calls for.
 *
 * A window with the height for it gets a page: the episode's name under the top bar, the picture
 * centred in the room between that and the transport at the bottom. A window too short for one —
 * a phone on its side — and any window the user asked to fill, gets the picture and nothing else,
 * with the transport laid over it and hidden again a few seconds after the last touch. Which of
 * the two is [videoShowsOverlay]'s to say, from the window's size and not from which way it is
 * turned: the Fold opened out is wider than it is tall and has all the room a page wants.
 *
 * In both, [controlsVisible] says whether anything but the picture is drawn, and a tap flips it.
 * In both, too, the controls go on their own [CONTROLS_TIMEOUT_MS] after the last touch while the
 * picture is moving; see [videoControlsAutoHide] for when they stay. Any touch anywhere on the
 * screen — on the picture, on a button, along the scrubber — starts the wait again, so the controls
 * never vanish from under a finger that is using them.
 *
 * The surface is a slot rather than drawn here, so a preview — and the golden — can put a plain
 * box where a `SurfaceView` bound to the player would be.
 *
 * @param uiState what to render.
 * @param surface draws the player's picture into the modifier it is given.
 * @param actions the controls.
 * @param modifier layout modifier.
 * @param fullscreen true while the window is held in landscape at the user's asking.
 * @param controlsVisible false when only the picture is to be shown.
 * @param onControlsVisibleChange asks for the controls to be shown or hidden; a tap does.
 * @param snackbarHostState where a playback error or a download message is shown.
 * @param holdControls true while something opened from the controls — a sheet — is up, which keeps
 *   them from hiding underneath it.
 */
@Composable
internal fun VideoScreen(
    uiState: VideoUiState,
    surface: @Composable (Modifier) -> Unit,
    actions: VideoActions,
    modifier: Modifier = Modifier,
    fullscreen: Boolean = false,
    controlsVisible: Boolean = true,
    onControlsVisibleChange: (Boolean) -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    holdControls: Boolean = false,
) {
    val controls = ControlsVisibility(controlsVisible, onControlsVisibleChange)
    // Bumped by every touch; a change restarts the wait below.
    var touches by remember { mutableIntStateOf(0) }
    // True while a finger is on the screen: a scrub or a press held past the timeout must not have
    // the controls taken from under it. The lift both clears it and restarts the wait.
    var fingerDown by remember { mutableStateOf(false) }
    val autoHide = videoControlsAutoHide(
        visible = controlsVisible,
        playback = uiState.playback,
        refused = uiState.refused,
        held = holdControls || fingerDown,
    )
    // Stretched for anyone whose accessibility settings ask for more time to act, which for a
    // screen reader is "never": a control that disappears while it is being found is no control.
    val timeoutMs = LocalAccessibilityManager.current?.calculateRecommendedTimeoutMillis(
        originalTimeoutMillis = CONTROLS_TIMEOUT_MS,
        containsIcons = true,
        containsText = true,
        containsControls = true,
    ) ?: CONTROLS_TIMEOUT_MS
    val onChange by rememberUpdatedState(onControlsVisibleChange)
    LaunchedEffect(autoHide, touches, timeoutMs) {
        if (!autoHide) return@LaunchedEffect
        delay(timeoutMs)
        onChange(false)
    }
    val watched = modifier.pointerInput(Unit) {
        // Watched on the way down, before any child sees it, and never consumed: this only notes
        // that the user is here, it takes nothing from the button or the scrubber being touched.
        // Presses and lifts only: a drag along the scrubber would otherwise recompose the screen on
        // every move, and the lift that ends it starts the wait again anyway.
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press || event.type == PointerEventType.Release) {
                    touches += 1
                    fingerDown = event.changes.any { it.pressed }
                }
            }
        }
    }
    if (showsOverlay(fullscreen)) {
        OverlayVideo(uiState, surface, actions, controls, fullscreen, snackbarHostState, watched)
    } else {
        PageVideo(uiState, surface, actions, controls, fullscreen, snackbarHostState, watched)
    }
}

/**
 * Whether the video screen's controls should hide on their own after a while untouched.
 *
 * Only while they are up and the picture is moving. A paused picture is one the user is about to do
 * something to; a buffering one — which includes one the player is retrying — or a failed one is one
 * they are waiting on, and the spinner and what they might press about it should stay; a refused
 * one is saying something with buttons of its own. And not while [held]: a sheet opened from the
 * controls is the user busy with them, and coming back from it to find them gone would be the
 * screen moving on without them.
 *
 * Pure, so each of those cases is asserted without a clock.
 *
 * @param visible whether the controls are showing.
 * @param playback what the player is doing.
 * @param refused whether the picture could not be shown.
 * @param held whether something opened from the controls is up.
 * @return true when the wait should run.
 */
internal fun videoControlsAutoHide(
    visible: Boolean,
    playback: PlaybackState,
    refused: Boolean,
    held: Boolean,
): Boolean = visible &&
    playback.isPlaying &&
    !playback.isBuffering &&
    playback.error == null &&
    !refused &&
    !held

/**
 * Whether the controls are shown, and the way to change that, passed to the layouts as one thing.
 *
 * @property visible false when only the picture is drawn.
 * @property onChange asks for the controls to be shown or hidden.
 */
private class ControlsVisibility(val visible: Boolean, val onChange: (Boolean) -> Unit)

/**
 * Whether this window gets the overlay rather than the page; see [videoShowsOverlay].
 *
 * Read from the window rather than from the configuration's orientation, and in one place, so the
 * route's effects and the screen's layout cannot come to different answers about the same window.
 *
 * @param fullscreen true while the user has asked for the picture to fill the screen.
 */
@Composable
private fun showsOverlay(fullscreen: Boolean): Boolean {
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    return videoShowsOverlay(windowHeight = windowHeight, fullscreen = fullscreen)
}

/**
 * Which of its two shapes the video screen takes: the overlay, or the page.
 *
 * The page needs height: a top bar, two lines of names, the picture and three rows of transport,
 * stacked. A window shorter than Material's medium height has no room for that under a picture of
 * any size, and gets the overlay — which is every phone on its side, and a phone's half of a split
 * screen. Everything taller gets the page, however wide: the Fold opened out is a landscape window
 * by its proportions and used to get the overlay for it, a picture with its controls hidden on a
 * timer on the one screen with room to show both.
 *
 * Asking for full screen is asking for the overlay, on any window. On a phone the window turns as
 * well and would be short enough anyway; on a large one nothing turns, and without this the button
 * would do nothing at all.
 *
 * Pure, so the windows this app meets can be asserted without a screenshot of each.
 *
 * @param windowHeight how tall the window is.
 * @param fullscreen true while the user has asked for the picture to fill the screen.
 * @return true for the overlay, false for the page.
 */
internal fun videoShowsOverlay(windowHeight: Dp, fullscreen: Boolean): Boolean =
    fullscreen || windowHeight < PageMinHeight

/** The least window height the page is laid out in: Material 3's medium height breakpoint. */
private val PageMinHeight: Dp = WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND.dp

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
 * The page also clears itself after a while untouched while the picture plays, as the overlay does;
 * see [VideoScreen]. A moving picture is watched with nothing else on the screen.
 *
 * Back, on a cleared page, brings the controls back before it does anything else: minimising from a
 * page with no minimise button in sight would be leaving a screen by a door that was not showing.
 * The overlay does not do this: it is the picture alone by design, and Back there leaves it.
 *
 * @param uiState what to render.
 * @param surface draws the picture.
 * @param actions the controls.
 * @param controls whether the page is cleared, and the way to change it.
 * @param fullscreen true while the window is held in landscape at the user's asking.
 * @param snackbarHostState where a playback error or a download message is shown.
 * @param modifier layout modifier.
 */
// systemBarsIgnoringVisibility is the experimental half: it is the only inset that stays put while
// the bars hide, which is the whole of why it is used.
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PageVideo(
    uiState: VideoUiState,
    surface: @Composable (Modifier) -> Unit,
    actions: VideoActions,
    controls: ControlsVisibility,
    fullscreen: Boolean,
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

    BackHandler(enabled = !controls.visible) { controls.onChange(true) }

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
                    actions = {
                        ModeSwitch(
                            selected = PlayerMode.VIDEO,
                            onSwitch = actions.onListen,
                            // With the bar's own inset, as far from the edge as the sheet's is.
                            modifier = Modifier.padding(end = MegaPodcastPlayerTheme.spacing.xs),
                        )
                    },
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
                    // As large as the room allows in both directions. On a phone held upright
                    // that is the full width; on a wide window the height runs out first, and a
                    // frame told to fill the width would be squeezed out of its proportions.
                    VideoFrame(uiState = uiState, surface = surface, actions = actions)
                }
                VideoControls(
                    uiState = uiState,
                    actions = actions,
                    fullscreen = fullscreen,
                    overlay = false,
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
 * away on their own, which [VideoScreen] arranges for both shapes.
 *
 * @param uiState what to render.
 * @param surface draws the picture.
 * @param actions the controls.
 * @param controls whether the overlay is up, and the way to change it.
 * @param fullscreen true while the window is held in landscape at the user's asking.
 * @param snackbarHostState where a playback error or a download message is shown.
 * @param modifier layout modifier.
 */
@Composable
private fun OverlayVideo(
    uiState: VideoUiState,
    surface: @Composable (Modifier) -> Unit,
    actions: VideoActions,
    controls: ControlsVisibility,
    fullscreen: Boolean,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val controlsVisible = controls.visible
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
            VideoFrame(uiState = uiState, surface = surface, actions = actions)
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
                        ModeSwitch(selected = PlayerMode.VIDEO, onSwitch = actions.onListen)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    VideoControls(
                        uiState = uiState,
                        actions = actions,
                        fullscreen = fullscreen,
                        overlay = true,
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
 * than not and a box of the wrong shape never jumps.
 *
 * What the box holds is one of three things, and never nothing. Until the player has drawn a frame
 * on this surface, the episode's artwork covers it — for a YouTube episode that is the video's own
 * thumbnail, so it is the poster it looks like — with a spinner while the picture is on its way.
 * Once there is a frame, the picture. And when the picture was asked for and cannot be shown, the
 * poster stays and says so, with a way to ask again and a way to stop asking.
 *
 * The cover goes by the first frame *drawn*, not by the size the decoder reported: the size
 * outlives the surface it was measured on, and a cover lifted on its say-so showed an empty
 * surface after a rotation, playing sound under a black box.
 *
 * @param uiState what the player is doing and whether the picture was refused.
 * @param surface draws the picture.
 * @param actions the controls; the unavailable state uses two of them.
 * @param modifier layout modifier; the caller decides which edge the box fills.
 */
@Composable
private fun VideoFrame(
    uiState: VideoUiState,
    surface: @Composable (Modifier) -> Unit,
    actions: VideoActions,
    modifier: Modifier = Modifier,
) {
    val playback = uiState.playback
    Box(
        modifier = modifier
            .aspectRatio(playback.videoAspectRatio ?: DEFAULT_ASPECT_RATIO)
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        surface(Modifier.fillMaxSize())
        if (!playback.pictureReady) {
            // The poster: over the surface, which draws whatever it last held until the picture
            // starts — including a frame of the previous episode. The frame is a rectangle, so the
            // artwork's own rounded mask would show the surface at its corners.
            PodcastArtwork(
                url = playback.artworkUrl,
                modifier = Modifier.fillMaxSize(),
                shape = RectangleShape,
            )
        }
        when {
            uiState.refused && !playback.pictureReady ->
                PictureUnavailable(onRetry = actions.onRetry, onListen = actions.onListen)

            // Not while a ready picture sits paused: a spinner there would promise something.
            playback.isBuffering || !playback.isVideo -> CircularProgressIndicator(color = Color.White)
        }
    }
}

/**
 * What the frame says when the picture cannot be shown.
 *
 * In the frame rather than in a snackbar, because it is the answer to "why is there no picture"
 * and stays true for as long as there is none. It says what did *not* change — the sound — and
 * offers the two ways on: ask again, or stop asking and listen.
 *
 * @param onRetry asks for the picture again.
 * @param onListen leaves for the audio player.
 */
@Composable
private fun PictureUnavailable(onRetry: () -> Unit, onListen: () -> Unit) {
    // White on a scrim whatever the theme, like the landscape overlay: this sits on a poster.
    val onPoster = ButtonDefaults.textButtonColors(contentColor = Color.White)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SCRIM)
            .padding(horizontal = MegaPodcastPlayerTheme.spacing.md),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.video_refused),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.sm)) {
            TextButton(onClick = onRetry, colors = onPoster) {
                Text(text = stringResource(R.string.video_retry))
            }
            TextButton(onClick = onListen, colors = onPoster) {
                Text(text = stringResource(R.string.player_switch_to_audio))
            }
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
 * The scrubber, the transport and the settings buttons, stacked.
 *
 * @param uiState what to render.
 * @param actions the transport.
 * @param fullscreen true while the window is held in landscape at the user's asking.
 * @param overlay true when laid over the picture rather than under it on the page.
 * @param modifier layout modifier.
 */
@Composable
private fun VideoControls(
    uiState: VideoUiState,
    actions: VideoActions,
    fullscreen: Boolean,
    overlay: Boolean,
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

            val quality = formatVideoQuality(uiState.qualityShown.height)
            val qualityDescription = stringResource(R.string.video_quality_button, quality)
            TextButton(
                onClick = actions.onOpenQuality,
                modifier = Modifier.semantics { contentDescription = qualityDescription },
            ) {
                Text(text = quality)
            }

            DownloadButton(download = uiState.videoDownload, onClick = actions.onOpenDownload)

            // Offered where it does something. Over a picture the phone was simply turned on its
            // side for, the way back is to turn it again; a button there could only fight the
            // sensor.
            if (fullscreen || !overlay) {
                FullscreenButton(fullscreen = fullscreen, onClick = actions.onToggleFullscreen)
            }
        }
    }
}

/**
 * Makes the picture fill the screen, or lets it go back to the page.
 *
 * Turning the phone does the same with auto-rotate on. This is for when it is off — the screen then
 * stays as the user set it until they ask — and for watching lying down, where the sensor is wrong.
 *
 * @param fullscreen true while the window is held in landscape.
 * @param onClick flips it.
 */
@Composable
private fun FullscreenButton(fullscreen: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary),
    ) {
        Icon(
            imageVector = if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
            contentDescription = stringResource(
                if (fullscreen) R.string.video_fullscreen_exit else R.string.video_fullscreen_enter,
            ),
        )
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
    val quality = download?.let { formatVideoQuality(it.quality.height) }
    val (icon, description) = when {
        download == null || quality == null ->
            Icons.Rounded.Download to stringResource(R.string.video_download_button)

        download.isComplete ->
            Icons.Rounded.DownloadDone to stringResource(R.string.video_download_button_done, quality)

        // Said as the failure it is, not as a button that was never pressed.
        download.state == DownloadState.FAILED ->
            Icons.Rounded.Download to stringResource(R.string.video_download_button_failed)

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
 * orientation is left to the user's own auto-rotate setting unless they ask for full screen, which
 * holds the window in landscape until they ask again or leave — it used to follow the sensor
 * regardless, which turned the screen on people who had told the phone not to, and recreated the
 * activity under a video that had only just started. The system bars go whenever only the picture
 * is wanted — always in the overlay, and on the page once the controls have been tapped away —
 * coming back with a swipe. And in the overlay the picture is let run under the cutout; there is
 * no content there to lose to it.
 *
 * @param overlay whether the screen is the picture with its controls laid over it.
 * @param immersive whether the system bars should be out of the picture's way.
 * @param keepScreenOn whether the picture is moving.
 * @param lockLandscape whether the user asked for the picture to fill the screen.
 */
@Composable
private fun FullscreenEffects(
    overlay: Boolean,
    immersive: Boolean,
    keepScreenOn: Boolean,
    lockLandscape: Boolean,
) {
    val view = LocalView.current
    val activity = LocalActivity.current

    DisposableEffect(view, keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // The orientation found on arrival, saved so it survives the activity being recreated by the
    // very rotation full screen asks for; read again after one, it would be this screen's own.
    val arrivalOrientation = rememberSaveable {
        activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    DisposableEffect(activity, lockLandscape) {
        val host = activity
        if (host == null || !lockLandscape) return@DisposableEffect onDispose {}
        // Either landscape, so the phone can still be turned the other way up.
        host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            // Only when the lock ends or the screen goes. Restoring on the rotation the lock
            // itself caused would ask the dying activity to turn straight back.
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

    DisposableEffect(activity, overlay) {
        val window = activity?.window
        if (window == null || !overlay) return@DisposableEffect onDispose {}
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

/** How long the controls stay after the last touch while the picture is moving. */
private const val CONTROLS_TIMEOUT_MS = 5_000L

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
                    pictureReady = true,
                ),
                settings = PlaybackSettings(),
                qualities = listOf(VideoQuality(PREVIEW_HEIGHT), VideoQuality(1080)),
            ),
            surface = { surfaceModifier -> Box(modifier = surfaceModifier.background(Color.DarkGray)) },
            actions = VideoActions(),
        )
    }
}

/**
 * The video screen on a window wider than it is tall and tall enough for the page: the Fold 7
 * opened out, near enough.
 *
 * Its own preview because what it shows is a decision — that this window gets the page and not the
 * overlay its proportions used to earn it — and a picture sized by the height it was left rather
 * than by the width it could have had.
 */
@Preview(name = "Unfolded", showBackground = true, widthDp = 882, heightDp = 830)
@Composable
internal fun VideoScreenWidePreview() {
    VideoScreenPreview()
}

/**
 * The video screen when the picture could not be shown: the poster, the sentence, the two ways on.
 *
 * At three font scales because the frame's height is fixed by its proportions and the sentence and
 * its buttons have to fit inside it.
 */
@ThemePreviews
@FontScalePreviews
@Composable
internal fun VideoScreenUnavailablePreview() {
    MegaPodcastPlayerTheme {
        VideoScreen(
            uiState = VideoUiState(
                playback = previewPlayback.copy(youTubeVideoId = "niTJ2221aS8"),
                settings = PlaybackSettings(),
                refused = true,
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
