package md.borisveriga.megapodcastplayer.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import md.borisveriga.megapodcastplayer.R
import md.borisveriga.megapodcastplayer.core.designsystem.theme.citronDarkScheme
import md.borisveriga.megapodcastplayer.core.designsystem.theme.citronLightScheme

/**
 * The home-screen widget: what you are listening to, a transport, and what to carry on with.
 *
 * The one surface of this app that is not drawn by this app. Glance compiles a composition down to
 * `RemoteViews`, which the launcher inflates in *its* process — so nothing in `:core:designsystem`
 * can appear here. No `WaveScrubber`, no `PlayPauseButton`, no `PodcastArtwork`: a progress bar is
 * the platform's, a button is an `Image` in a tinted circle, and a cover is a `Bitmap` that had to
 * be fetched before the tree crossed the process boundary. The brand travels as the palette and
 * nothing else, which is why `citronLightScheme` and `citronDarkScheme` are public.
 *
 * What it shows is decided in `WidgetSnapshot.kt` and is the same answer the launcher's *Resume*
 * shortcut gives; this file is the drawing of it.
 */
internal class NowPlayingWidget : GlanceAppWidget() {

    /**
     * Two shapes, chosen by the room the user gave it.
     *
     * `Responsive` rather than `Exact` because the launcher then asks for both trees once and
     * switches between them itself, without waking this app every time a home screen is rotated.
     */
    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(CompactSize, FullSize))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshots = context.widgetEntryPoint().widgetSnapshots()

        provideContent {
            // The composition stays alive while the widget is on a home screen, so this collection
            // is what makes the widget follow playback rather than a periodic poll of it.
            val snapshot by snapshots.collectAsState(initial = WidgetSnapshot())

            GlanceTheme(colors = MegaPodcastPlayerGlanceColors) {
                WidgetBody(snapshot)
            }
        }
    }

    private companion object {
        /** Two cells wide, one tall: the episode and one button. */
        val CompactSize = DpSize(width = 180.dp, height = 100.dp)

        /** Enough for the transport and the shelf under it. */
        val FullSize = DpSize(width = 250.dp, height = 190.dp)
    }
}

/**
 * The app's palette, as Glance wants it.
 *
 * Built once at class-init rather than per composition: it is two `ColorScheme`s that never change,
 * and a widget composition may run several times a minute while an episode plays.
 */
private val MegaPodcastPlayerGlanceColors =
    ColorProviders(light = citronLightScheme, dark = citronDarkScheme)

/**
 * Everything inside the widget's frame.
 *
 * Internal rather than private so `NowPlayingWidgetTest` can render it directly from a snapshot.
 * The seam is the same one the screenshot suite uses on the app's `@Preview`s: what is worth
 * asserting is the tree a given state produces, and the state is the parameter.
 *
 * @param snapshot what to draw.
 */
@Composable
internal fun WidgetBody(snapshot: WidgetSnapshot) {
    val context = LocalContext.current

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(WidgetCornerRadius)
            .padding(WidgetPadding)
            .clickable(
                actionStartActivity(
                    if (snapshot.episode == null) openAppIntent(context) else openPlayerIntent(context),
                ),
            ),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        if (snapshot.episode == null) {
            EmptyBody()
        } else {
            EpisodeRow(episode = snapshot.episode, progressPercent = snapshot.progressPercent)
            Spacer(modifier = GlanceModifier.height(WidgetGap))
            TransportRow(isLoaded = snapshot.isLoaded, isPlaying = snapshot.isPlaying)
            // Only where there is room for it, and only when there is something in it. An empty
            // shelf drawn as a strip of nothing would push the transport off centre to say so.
            if (hasRoomForShelf() && snapshot.continueListening.isNotEmpty()) {
                Spacer(modifier = GlanceModifier.height(WidgetGap))
                ShelfRow(episodes = snapshot.continueListening)
            }
        }
    }
}

/**
 * Whether this rendering is the tall one.
 *
 * Read from [LocalSize], which under `SizeMode.Responsive` is the size Glance is currently building
 * a tree for rather than the live size of anything — the launcher picks between the trees later.
 */
@Composable
private fun hasRoomForShelf(): Boolean = LocalSize.current.height >= ShelfMinimumHeight

/**
 * What the widget says when there is nothing to carry on with.
 *
 * A fresh install, or a player dismissed with an empty library behind it. The whole body is already
 * a tap target that opens the app, so this is a label rather than a button: an empty state with
 * nothing to press is a dead end, and an empty state with a button *inside* another button is two
 * answers to one press.
 */
@Composable
private fun EmptyBody() {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
    ) {
        Text(
            text = LocalContext.current.getString(R.string.widget_empty_title),
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = TitleFontSize,
                fontWeight = FontWeight.Medium,
            ),
        )
        Text(
            text = LocalContext.current.getString(R.string.widget_empty_body),
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = BodyFontSize,
            ),
        )
    }
}

/**
 * The cover, the two titles and the progress line under them.
 *
 * @param episode what to name.
 * @param progressPercent how far through it, `0..100`.
 */
@Composable
private fun EpisodeRow(episode: WidgetEpisode, progressPercent: Int) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Artwork(url = episode.artworkUrl, sizeDp = ArtworkSize)

        Spacer(modifier = GlanceModifier.width(WidgetGap))

        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = episode.title,
                maxLines = 2,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = TitleFontSize,
                    fontWeight = FontWeight.Medium,
                ),
            )
            Text(
                text = episode.showTitle,
                maxLines = 1,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = BodyFontSize,
                ),
            )
            Spacer(modifier = GlanceModifier.height(ProgressGap))
            LinearProgressIndicator(
                progress = progressPercent / PERCENT,
                modifier = GlanceModifier.fillMaxWidth().height(ProgressHeight),
                color = GlanceTheme.colors.primary,
                backgroundColor = GlanceTheme.colors.surfaceVariant,
            )
        }
    }
}

/**
 * Skip back, play or pause, skip ahead.
 *
 * The two skips are drawn only when an episode is actually loaded. What they would mean otherwise
 * is "move the playhead of the thing that is not playing", which is a button that does nothing —
 * and the same reasoning made [WidgetSnapshot.isLoaded] a field rather than a null check.
 *
 * @param isLoaded whether the player holds the episode being drawn.
 * @param isPlaying whether audio is coming out of it.
 */
@Composable
private fun TransportRow(isLoaded: Boolean, isPlaying: Boolean) {
    val context = LocalContext.current

    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        if (isLoaded) {
            TransportButton(
                iconRes = R.drawable.ic_widget_skip_back,
                description = context.getString(R.string.widget_skip_back),
                onClick = actionRunCallback<SkipBackAction>(),
            )
            Spacer(modifier = GlanceModifier.width(WidgetGap))
        }

        TransportButton(
            iconRes = if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
            description = context.getString(
                when {
                    isPlaying -> R.string.widget_pause
                    isLoaded -> R.string.widget_play
                    else -> R.string.widget_resume
                },
            ),
            onClick = if (isLoaded) {
                actionRunCallback<TogglePlayPauseAction>()
            } else {
                actionRunCallback<ResumeAction>()
            },
            emphasised = true,
        )

        if (isLoaded) {
            Spacer(modifier = GlanceModifier.width(WidgetGap))
            TransportButton(
                iconRes = R.drawable.ic_widget_skip_forward,
                description = context.getString(R.string.widget_skip_forward),
                onClick = actionRunCallback<SkipForwardAction>(),
            )
        }
    }
}

/**
 * One round button.
 *
 * @param iconRes the glyph, drawn white and tinted here so one drawable serves both schemes.
 * @param description what a screen reader says; the only place the skip interval is named, since
 *   the glyphs carry no number — see `ic_widget_skip_back.xml`.
 * @param onClick what pressing it does.
 * @param emphasised whether it is the primary action, which wears the accent.
 */
@Composable
private fun TransportButton(
    iconRes: Int,
    description: String,
    onClick: Action,
    emphasised: Boolean = false,
) {
    val background = if (emphasised) GlanceTheme.colors.primary else GlanceTheme.colors.surfaceVariant
    val tint = if (emphasised) GlanceTheme.colors.onPrimary else GlanceTheme.colors.onSurfaceVariant
    val size = if (emphasised) PrimaryButtonSize else ButtonSize

    Image(
        provider = ImageProvider(iconRes),
        contentDescription = description,
        colorFilter = ColorFilter.tint(tint),
        modifier = GlanceModifier
            .size(size)
            .background(background)
            .cornerRadius(size / 2)
            .padding(ButtonPadding)
            .clickable(onClick),
    )
}

/**
 * The *Continue listening* shelf: covers only, in the order the Listen screen has them.
 *
 * Covers and no titles. At this size a title is two words and an ellipsis, and the cover is how a
 * show is recognised anyway — the same reason the library's grid layout exists. The spoken label
 * carries the episode, so nothing is lost to a screen reader.
 *
 * @param episodes what to draw, already trimmed and already free of the episode above.
 */
@Composable
private fun ShelfRow(episodes: List<WidgetEpisode>) {
    val context = LocalContext.current

    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        episodes.forEachIndexed { index, episode ->
            if (index > 0) Spacer(modifier = GlanceModifier.width(WidgetGap))
            Artwork(
                url = episode.artworkUrl,
                sizeDp = ShelfArtworkSize,
                description = context.getString(R.string.widget_play_episode, episode.title),
                modifier = GlanceModifier.clickable(
                    actionRunCallback<PlayEpisodeAction>(
                        actionParametersOf(episodeIdKey to episode.id),
                    ),
                ),
            )
        }
    }
}

/**
 * A cover, fetched as a bitmap because `RemoteViews` cannot fetch one for itself.
 *
 * The load runs inside the composition rather than before it, because which cover is wanted is a
 * function of the snapshot and the snapshot arrives after the composition has started. Until it
 * lands — and forever, for a show with no artwork — the placeholder stands in, which is the same
 * bargain `PodcastArtwork` makes in the app.
 *
 * @param url the cover, or null.
 * @param sizeDp how large to draw it.
 * @param description what a screen reader says, or null when the row around it already says.
 * @param modifier layout modifier, carrying any click.
 */
@Composable
private fun Artwork(
    url: String?,
    sizeDp: Dp,
    description: String? = null,
    modifier: GlanceModifier = GlanceModifier,
) {
    val context = LocalContext.current
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(url) { bitmap = url?.let { loadArtwork(context, it) } }

    val loaded = bitmap
    Image(
        provider = if (loaded != null) {
            ImageProvider(loaded)
        } else {
            ImageProvider(R.drawable.ic_widget_artwork_placeholder)
        },
        contentDescription = description,
        contentScale = ContentScale.Crop,
        colorFilter = if (loaded == null) ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant) else null,
        modifier = modifier
            .size(sizeDp)
            .background(GlanceTheme.colors.surfaceVariant)
            .cornerRadius(ArtworkCornerRadius),
    )
}

/**
 * Fetches one cover through the app's own Coil loader.
 *
 * The app's loader rather than a fresh one, so a cover the library screen has already downloaded is
 * read from the same disk cache instead of the network — and so the widget inherits the shared
 * `OkHttpClient` `MegaPodcastPlayerApplication` built.
 *
 * Failure is null, not an exception: a widget with a placeholder where a cover should be is a
 * widget, and a widget that threw is a launcher showing "Problem loading widget".
 *
 * @param context any context.
 * @param url the cover to fetch.
 * @return the bitmap, or null if it could not be had.
 */
private suspend fun loadArtwork(context: Context, url: String): Bitmap? {
    val request = ImageRequest.Builder(context)
        .data(url)
        .size(ARTWORK_PIXELS, ARTWORK_PIXELS)
        .build()
    val result = SingletonImageLoader.get(context).execute(request)
    return (result as? SuccessResult)?.image?.toBitmap()
}

/**
 * How large a fetched cover is, in pixels.
 *
 * Fixed rather than derived from the drawn size, because a `RemoteViews` bitmap crosses a process
 * boundary and the platform caps how much may cross: 256 px is larger than the widget draws at any
 * density it will meet, and small enough that four of them on the shelf are not the reason the
 * launcher drops the widget.
 */
private const val ARTWORK_PIXELS = 256

/** One hundred, as a float, for turning a percentage back into a fraction. */
private const val PERCENT = 100f

private val WidgetCornerRadius = 20.dp
private val WidgetPadding = 12.dp
private val WidgetGap = 8.dp
private val ArtworkSize = 56.dp
private val ShelfArtworkSize = 44.dp
private val ArtworkCornerRadius = 8.dp
private val ButtonSize = 40.dp
private val PrimaryButtonSize = 48.dp
private val ButtonPadding = 8.dp
private val ProgressGap = 6.dp
private val ProgressHeight = 4.dp
private val TitleFontSize = 14.sp
private val BodyFontSize = 12.sp

/** Below this the widget is the episode and its buttons; above it the shelf fits too. */
private val ShelfMinimumHeight = 150.dp
