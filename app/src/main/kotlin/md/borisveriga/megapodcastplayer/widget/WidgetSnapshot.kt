package md.borisveriga.megapodcastplayer.widget

import kotlin.math.roundToInt
import md.borisveriga.megapodcastplayer.core.media.PlayableEpisode
import md.borisveriga.megapodcastplayer.core.media.PlaybackState
import md.borisveriga.megapodcastplayer.core.model.EpisodeWithShow

/**
 * One episode, in as much detail as a widget can draw.
 *
 * Deliberately not `Episode`. A widget is redrawn from a snapshot the launcher may hold for hours,
 * and everything in that snapshot is something the widget has to be able to render out of
 * `RemoteViews` — so it carries four strings and no chapters, no download state and no `Instant`.
 *
 * @property id what a press on it plays.
 * @property title the episode.
 * @property showTitle the show it came from.
 * @property artworkUrl the cover, or null; the widget draws its own placeholder when there is none.
 */
internal data class WidgetEpisode(
    val id: String,
    val title: String,
    val showTitle: String,
    val artworkUrl: String?,
)

/**
 * Everything the widget draws, at one moment.
 *
 * @property episode what the transport acts on: the loaded episode when there is one, otherwise
 *   the episode the app would carry on with. Null only on a fresh install, or after the player has
 *   been dismissed and nothing has ever been played.
 * @property isLoaded whether [episode] is in the player *now*, as opposed to being what the player
 *   would pick up. It is the difference between a transport and a single *Resume* button; see
 *   [widgetSnapshot].
 * @property isPlaying whether audio is actually coming out.
 * @property progressPercent how far through [episode] the listener is, `0..100`.
 * @property continueListening the shelf under the transport, newest first, never including
 *   [episode] itself.
 */
internal data class WidgetSnapshot(
    val episode: WidgetEpisode? = null,
    val isLoaded: Boolean = false,
    val isPlaying: Boolean = false,
    val progressPercent: Int = 0,
    val continueListening: List<WidgetEpisode> = emptyList(),
) {
    /** True when there is nothing to play and nothing to carry on with; the widget says so. */
    val isEmpty: Boolean get() = episode == null && continueListening.isEmpty()
}

/**
 * Builds what the widget draws from the three things that know it.
 *
 * The interesting decision is what the widget shows when the player is *idle*, which on a phone is
 * most of the time — the process is not even alive between sessions. It could show nothing, which
 * makes a widget that is blank whenever it is worth looking at. It could invent an answer to "what
 * would you carry on with", which is a third answer to a question the launcher shortcut and the
 * system's own resumption tile already answer through
 * [resumableQueue][md.borisveriga.megapodcastplayer.core.media.PlaybackQueueSource.resumableQueue].
 * So it shows *that* answer: the same episode, resumed by the same call, and the two cannot
 * disagree because there is only one of them.
 *
 * That is why [WidgetSnapshot.isLoaded] exists and is not simply `episode != null`. A loaded
 * episode has a real position, a real play/pause state and a queue to skip within, so it gets a
 * transport. An episode that is merely *next* has none of those — skipping forward in something
 * that is not playing is a button that means nothing — so it gets one button that says carry on.
 *
 * The shelf never repeats the episode above it. Half-finished episodes are exactly what the shelf
 * is made of, and the one being played is the most half-finished of all, so without this the widget
 * would spend most of its life showing the same cover twice.
 *
 * @param playback the live player.
 * @param resumable what the app would carry on with; the head of the durable queue.
 * @param inProgress the *Continue listening* shelf, newest first.
 * @param shelfLimit how many the shelf may hold, after the episode above it has been removed.
 * @return the snapshot to draw.
 */
internal fun widgetSnapshot(
    playback: PlaybackState,
    resumable: PlayableEpisode?,
    inProgress: List<EpisodeWithShow>,
    shelfLimit: Int,
): WidgetSnapshot {
    val loaded = playback.episodeId?.let { id ->
        WidgetEpisode(
            id = id,
            title = playback.title,
            showTitle = playback.showTitle,
            artworkUrl = playback.artworkUrl,
        )
    }
    val episode = loaded ?: resumable?.asWidgetEpisode()

    return WidgetSnapshot(
        episode = episode,
        isLoaded = loaded != null,
        // Only a loaded episode can be playing. A resumable one is by definition not.
        isPlaying = loaded != null && playback.isPlaying,
        progressPercent = when {
            loaded != null -> playback.progress

            // The stored fraction, which is what the row on the Listen screen draws for the same
            // episode — the widget and the shelf agree about how far through it you are.
            else -> resumable?.episode?.playedFraction ?: 0f
        }.asPercent(),
        continueListening = inProgress
            .filter { it.episode.id != episode?.id }
            .take(shelfLimit)
            .map { it.asWidgetEpisode() },
    )
}

/**
 * A fraction as whole percent, which is the only resolution a widget may have.
 *
 * Not a rounding for the sake of tidiness. The player re-emits its position twice a second, and a
 * snapshot carrying the raw fraction would therefore be a *different* snapshot twice a second — and
 * every different snapshot is a `RemoteViews` tree crossing a process boundary into the launcher.
 * At whole percent an hour-long episode produces a redraw every thirty-six seconds, and the widget
 * shows the same bar it would have shown anyway.
 *
 * @return the fraction in `0..100`.
 */
private fun Float.asPercent(): Int = (this * PERCENT).roundToInt().coerceIn(0, PERCENT)

/** One hundred, named because it is a unit here rather than a magic number. */
private const val PERCENT = 100

/** The playable episode as the widget sees it. */
private fun PlayableEpisode.asWidgetEpisode(): WidgetEpisode = WidgetEpisode(
    id = episode.id,
    title = episode.title,
    showTitle = showTitle,
    artworkUrl = artworkUrl,
)

/** The shelf entry as the widget sees it. */
private fun EpisodeWithShow.asWidgetEpisode(): WidgetEpisode = WidgetEpisode(
    id = episode.id,
    title = episode.title,
    showTitle = showTitle,
    artworkUrl = artworkUrl,
)
