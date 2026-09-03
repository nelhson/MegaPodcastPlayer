package md.borisveriga.megapodcastplayer.wear.tile

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders
import md.borisveriga.megapodcastplayer.core.wearprotocol.NowPlayingSnapshot
import md.borisveriga.megapodcastplayer.core.wearprotocol.WearCommand

/**
 * The ids a tap on the tile arrives back with.
 *
 * A tile has no callbacks: a tap is delivered as the *next* layout request, carrying the id of
 * whatever was pressed. So these ids are the whole of the tile's input vocabulary, and they have to
 * survive being written by one build and read by another — which is why they are spelled-out
 * strings rather than an enum's ordinal.
 */
internal object TileClicks {

    /** The centre button. */
    const val PLAY_PAUSE = "play_pause"

    /** The right-hand button: jump ahead by the phone's configured interval. */
    const val SKIP_FORWARD = "skip_forward"

    /** The left-hand button: jump back by the phone's configured interval. */
    const val SKIP_BACK = "skip_back"

    /** Anywhere else on the tile, which opens the app rather than asking the phone for anything. */
    const val OPEN_APP = "open_app"
}

/**
 * The names the layout refers to its images by.
 *
 * A tile's images are requested separately from its layout, by id, so both sides need the same
 * names; see [tileResources].
 */
internal object TileImages {
    const val PLAY = "play"
    const val PAUSE = "pause"
    const val SKIP_FORWARD = "skip_forward"
    const val SKIP_BACK = "skip_back"
}

/**
 * Every word the tile can show, already resolved.
 *
 * A tile is rendered in the system's process from a layout that carries literal strings — there is
 * no `Context` at the far end to look up a resource id. Resolving them here, in one small object the
 * service fills from `strings.xml`, is what keeps [nowPlayingTileLayout] free of Android types and
 * therefore testable, without smuggling user-facing English into Kotlin.
 *
 * @property idleTitle heading when the phone has nothing loaded.
 * @property idleBody the sentence under it.
 * @property unreachable shown after a tap that could not be delivered.
 * @property play spoken label for the centre button while paused.
 * @property pause spoken label for the centre button while playing.
 * @property skipBack spoken label for the left button.
 * @property skipForward spoken label for the right button.
 */
internal data class TileCopy(
    val idleTitle: String,
    val idleBody: String,
    val unreachable: String,
    val play: String,
    val pause: String,
    val skipBack: String,
    val skipForward: String,
)

/**
 * The command a tap asks the phone for, if any.
 *
 * Kept apart from the layout and from the service so the mapping can be tested without a renderer
 * or Play Services.
 *
 * @param clickableId the id the tile request carried; empty on an ordinary refresh.
 * @return the command to send, or null when the tap was not one of the buttons.
 */
internal fun tileCommandFor(clickableId: String): WearCommand? = when (clickableId) {
    TileClicks.PLAY_PAUSE -> WearCommand.TogglePlayPause
    TileClicks.SKIP_FORWARD -> WearCommand.SkipForward
    TileClicks.SKIP_BACK -> WearCommand.SkipBack
    else -> null
}

/**
 * The tile's whole layout.
 *
 * A tile is a still picture the system asks for and then draws itself — no recomposition, no state —
 * so everything the wearer sees is decided here, in one pass, from the snapshot the phone last
 * published. The progress bar therefore takes the position *the caller extrapolated*, not the one
 * inside the snapshot: a tile can sit on a screen for a minute after it was built.
 *
 * The design is the app's, one step simplified: the show's colour identifies the show, the title
 * says what it is, and the three buttons are the ones worth pressing without opening anything. The
 * app's waveform does not come along — a tile cannot animate, and a frozen waveform would say
 * "paused" when it is not.
 *
 * @param snapshot what the phone last published.
 * @param positionMs playback position now, extrapolated from the snapshot.
 * @param accentArgb the show's colour, from [md.borisveriga.megapodcastplayer.wear.ui.showAccentArgb].
 * @param copy the resolved strings.
 * @param commandFailed true when the tap that triggered this build could not be delivered, which is
 *   the only way the tile ever learns the phone is unreachable.
 * @param packageName this app's package, for the tap that opens it.
 */
internal fun nowPlayingTileLayout(
    snapshot: NowPlayingSnapshot,
    positionMs: Long,
    accentArgb: Int,
    copy: TileCopy,
    commandFailed: Boolean,
    packageName: String,
): LayoutElement {
    val content = LayoutElementBuilders.Column.Builder()
        .setWidth(DimensionBuilders.expand())
        .setHeight(DimensionBuilders.wrap())
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)

    if (snapshot.isIdle) {
        content.addContent(plainText(copy.idleTitle, TITLE_SIZE_SP, WHITE, IDLE_MAX_LINES))
        content.addContent(spacerHeight(GAP_SMALL_DP))
        content.addContent(plainText(copy.idleBody, SHOW_SIZE_SP, MUTED, IDLE_MAX_LINES))
    } else {
        content.addContent(plainText(snapshot.title, TITLE_SIZE_SP, WHITE, TITLE_MAX_LINES))
        if (snapshot.showTitle.isNotBlank()) {
            content.addContent(spacerHeight(GAP_SMALL_DP))
            content.addContent(plainText(snapshot.showTitle, SHOW_SIZE_SP, accentArgb, 1))
        }
        content.addContent(spacerHeight(GAP_MEDIUM_DP))
        content.addContent(progressBar(snapshot.progressAt(positionMs), accentArgb))
        content.addContent(spacerHeight(GAP_MEDIUM_DP))
        content.addContent(transportRow(snapshot.isPlaying, accentArgb, copy))
    }

    if (commandFailed) {
        content.addContent(spacerHeight(GAP_SMALL_DP))
        content.addContent(plainText(copy.unreachable, SHOW_SIZE_SP, FAILURE, IDLE_MAX_LINES))
    }

    // The tile as a whole opens the app, except where a button sits on top of it and takes the tap
    // first. That is the tile convention, and it is also the only route to the queue, which does not
    // fit here.
    return LayoutElementBuilders.Box.Builder()
        .setWidth(DimensionBuilders.expand())
        .setHeight(DimensionBuilders.expand())
        .setModifiers(
            ModifiersBuilders.Modifiers.Builder()
                .setPadding(
                    ModifiersBuilders.Padding.Builder()
                        .setAll(DimensionBuilders.dp(TILE_PADDING_DP))
                        .build(),
                )
                .setClickable(
                    ModifiersBuilders.Clickable.Builder()
                        .setId(TileClicks.OPEN_APP)
                        .setOnClick(
                            ActionBuilders.LaunchAction.Builder()
                                .setAndroidActivity(
                                    ActionBuilders.AndroidActivity.Builder()
                                        .setPackageName(packageName)
                                        .setClassName(MAIN_ACTIVITY)
                                        .build(),
                                )
                                .build(),
                        )
                        .build(),
                )
                .build(),
        )
        .addContent(content.build())
        .build()
}

/**
 * One line — or a few — of text.
 *
 * @param text what to draw, already resolved.
 * @param sizeSp its size.
 * @param colorArgb its colour.
 * @param maxLines where to ellipsise.
 */
private fun plainText(text: String, sizeSp: Float, colorArgb: Int, maxLines: Int): LayoutElement =
    LayoutElementBuilders.Text.Builder()
        .setText(text)
        .setMaxLines(maxLines)
        .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
        .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE)
        .setFontStyle(
            LayoutElementBuilders.FontStyle.Builder()
                .setSize(DimensionBuilders.sp(sizeSp))
                .setColor(ColorBuilders.argb(colorArgb))
                .build(),
        )
        .build()

/**
 * The progress bar, built as two weighted halves of a row.
 *
 * Protolayout ships no straight progress element — the one it has is the ring around the bezel,
 * which belongs to a different layout — so the bar is a filled box beside an unfilled one, each
 * taking its share of the width. Both weights are floored: a zero-weight child collapses, and the
 * renderer then stretches the other across the whole width, which would show a *full* bar for an
 * episode that has not started.
 *
 * @param progress fraction played, in `0f..1f`.
 * @param accentArgb the colour of the played part.
 */
private fun progressBar(progress: Float, accentArgb: Int): LayoutElement {
    val played = playedBarWeight(progress)

    return LayoutElementBuilders.Row.Builder()
        .setWidth(DimensionBuilders.expand())
        .setHeight(DimensionBuilders.dp(PROGRESS_HEIGHT_DP))
        .addContent(barSegment(DimensionBuilders.weight(played), accentArgb))
        .addContent(barSegment(DimensionBuilders.weight(1f - played), TRACK))
        .build()
}

/**
 * The played segment's share of the progress bar.
 *
 * Pulled out of [progressBar] because the clamping is the part with a bug in it: a weight of zero
 * collapses a row child, and the renderer then stretches the *other* one across the whole width — so
 * an episode that had not started would show a full bar, and a finished one an empty bar.
 *
 * @param progress fraction played, in `0f..1f`.
 * @return the share to give the played segment; the unplayed one takes the rest.
 */
internal fun playedBarWeight(progress: Float): Float =
    progress.coerceIn(MIN_BAR_WEIGHT, 1f - MIN_BAR_WEIGHT)

/**
 * One half of [progressBar].
 *
 * @param width its share of the row.
 * @param colorArgb played or unplayed.
 */
private fun barSegment(
    width: DimensionBuilders.ExpandedDimensionProp,
    colorArgb: Int,
): LayoutElement = LayoutElementBuilders.Box.Builder()
    .setWidth(width)
    .setHeight(DimensionBuilders.dp(PROGRESS_HEIGHT_DP))
    .setModifiers(
        ModifiersBuilders.Modifiers.Builder()
            .setBackground(
                ModifiersBuilders.Background.Builder()
                    .setColor(ColorBuilders.argb(colorArgb))
                    .setCorner(
                        ModifiersBuilders.Corner.Builder()
                            .setRadius(DimensionBuilders.dp(PROGRESS_HEIGHT_DP * HALF))
                            .build(),
                    )
                    .build(),
            )
            .build(),
    )
    .build()

/**
 * Skip back, play/pause, skip forward — the same three the app puts under the thumb.
 *
 * @param isPlaying which way round the centre button points.
 * @param accentArgb the show's colour, which the centre button wears.
 * @param copy the spoken labels.
 */
private fun transportRow(isPlaying: Boolean, accentArgb: Int, copy: TileCopy): LayoutElement =
    LayoutElementBuilders.Row.Builder()
        .setWidth(DimensionBuilders.wrap())
        .setHeight(DimensionBuilders.wrap())
        .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
        .addContent(
            transportButton(
                clickId = TileClicks.SKIP_BACK,
                imageId = TileImages.SKIP_BACK,
                description = copy.skipBack,
                sizeDp = BUTTON_SIZE_DP,
                backgroundArgb = SECONDARY_BUTTON,
                tintArgb = WHITE,
            ),
        )
        .addContent(spacerWidth(BUTTON_GAP_DP))
        .addContent(
            transportButton(
                clickId = TileClicks.PLAY_PAUSE,
                imageId = if (isPlaying) TileImages.PAUSE else TileImages.PLAY,
                description = if (isPlaying) copy.pause else copy.play,
                sizeDp = PLAY_BUTTON_SIZE_DP,
                backgroundArgb = accentArgb,
                // Black on the show's colour rather than white: every colour in that palette is a
                // light one, chosen to carry text on a black screen, so white on it would not.
                tintArgb = BLACK,
            ),
        )
        .addContent(spacerWidth(BUTTON_GAP_DP))
        .addContent(
            transportButton(
                clickId = TileClicks.SKIP_FORWARD,
                imageId = TileImages.SKIP_FORWARD,
                description = copy.skipForward,
                sizeDp = BUTTON_SIZE_DP,
                backgroundArgb = SECONDARY_BUTTON,
                tintArgb = WHITE,
            ),
        )
        .build()

/**
 * One round button.
 *
 * @param clickId the id this tap comes back as; see [TileClicks].
 * @param imageId the image mapping id, resolved by [tileResources].
 * @param description the spoken label.
 * @param sizeDp the button's diameter.
 * @param backgroundArgb the disc behind the glyph.
 * @param tintArgb the glyph itself.
 */
private fun transportButton(
    clickId: String,
    imageId: String,
    description: String,
    sizeDp: Float,
    backgroundArgb: Int,
    tintArgb: Int,
): LayoutElement = LayoutElementBuilders.Box.Builder()
    .setWidth(DimensionBuilders.dp(sizeDp))
    .setHeight(DimensionBuilders.dp(sizeDp))
    .setModifiers(
        ModifiersBuilders.Modifiers.Builder()
            .setBackground(
                ModifiersBuilders.Background.Builder()
                    .setColor(ColorBuilders.argb(backgroundArgb))
                    .setCorner(
                        ModifiersBuilders.Corner.Builder()
                            .setRadius(DimensionBuilders.dp(sizeDp * HALF))
                            .build(),
                    )
                    .build(),
            )
            .setClickable(
                ModifiersBuilders.Clickable.Builder()
                    .setId(clickId)
                    // A load action, not a launch one: the tap has to come back here so the command
                    // can be sent and the tile redrawn with what it changed.
                    .setOnClick(ActionBuilders.LoadAction.Builder().build())
                    .build(),
            )
            .setSemantics(
                ModifiersBuilders.Semantics.Builder()
                    .setContentDescription(description)
                    .build(),
            )
            .build(),
    )
    .addContent(
        LayoutElementBuilders.Image.Builder()
            .setResourceId(imageId)
            .setWidth(DimensionBuilders.dp(ICON_SIZE_DP))
            .setHeight(DimensionBuilders.dp(ICON_SIZE_DP))
            .setColorFilter(
                LayoutElementBuilders.ColorFilter.Builder()
                    .setTint(ColorBuilders.argb(tintArgb))
                    .build(),
            )
            .build(),
    )
    .build()

/** Vertical space. */
private fun spacerHeight(heightDp: Float): LayoutElement = LayoutElementBuilders.Spacer.Builder()
    .setHeight(DimensionBuilders.dp(heightDp))
    .build()

/** Horizontal space. */
private fun spacerWidth(widthDp: Float): LayoutElement = LayoutElementBuilders.Spacer.Builder()
    .setWidth(DimensionBuilders.dp(widthDp))
    .build()

/**
 * Fraction played at a position the caller extrapolated.
 *
 * The snapshot's own `progressAfter` measures from the moment the phone published; a tile has
 * usually been standing still for longer than that, so it works from an absolute position instead.
 *
 * @param positionMs where playback has reached.
 */
private fun NowPlayingSnapshot.progressAt(positionMs: Long): Float {
    val duration = knownDurationMs ?: return 0f
    return (positionMs.toFloat() / duration).coerceIn(0f, 1f)
}

/** The activity a tap opens, spelled out because a tile launches by class name. */
private const val MAIN_ACTIVITY = "md.borisveriga.megapodcastplayer.wear.MainActivity"

/** Room between the tile's content and the edge of a round screen. */
private const val TILE_PADDING_DP = 16f

private const val TITLE_SIZE_SP = 15f
private const val SHOW_SIZE_SP = 12f

/** Two lines of title before the ellipsis, which is what fits above the buttons. */
private const val TITLE_MAX_LINES = 2

/** The wordy states have no buttons under them, so they may run longer. */
private const val IDLE_MAX_LINES = 3

private const val GAP_SMALL_DP = 4f
private const val GAP_MEDIUM_DP = 10f

private const val PROGRESS_HEIGHT_DP = 6f

/** The side buttons: a comfortable target without crowding the centre one. */
private const val BUTTON_SIZE_DP = 44f

/** The centre button is larger for the app's reason: it is the one pressed without looking. */
private const val PLAY_BUTTON_SIZE_DP = 52f

private const val BUTTON_GAP_DP = 8f
private const val ICON_SIZE_DP = 24f

/** Half, for the corner radius that turns a square into a circle. */
private const val HALF = 0.5f

/**
 * The least of the bar either segment may take.
 *
 * One percent is invisible at this size and cannot lie; see [progressBar] for what a zero does.
 */
private const val MIN_BAR_WEIGHT = 0.01f

private const val WHITE = 0xFFFFFFFF.toInt()
private const val BLACK = 0xFF000000.toInt()

/** Secondary text: bright enough to read on black, dim enough not to compete with the title. */
private const val MUTED = 0xFF9E9E9E.toInt()

/** The unplayed part of the progress bar. */
private const val TRACK = 0xFF3A3A3A.toInt()

/** The two side buttons: present, but plainly not the one to press. */
private const val SECONDARY_BUTTON = 0xFF2E2E2E.toInt()

/** A failed command, in the red the app uses for one. */
private const val FAILURE = 0xFFFF8A80.toInt()
