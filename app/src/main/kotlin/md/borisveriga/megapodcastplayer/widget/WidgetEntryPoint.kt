package md.borisveriga.megapodcastplayer.widget

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import md.borisveriga.megapodcastplayer.core.data.playback.EpisodePlayer
import md.borisveriga.megapodcastplayer.core.data.repository.PodcastRepository
import md.borisveriga.megapodcastplayer.core.media.PlaybackConnection
import md.borisveriga.megapodcastplayer.core.media.PlaybackQueueSource

/**
 * How the widget reaches the app's graph.
 *
 * An entry point rather than `@AndroidEntryPoint` on the receiver, because the things that need
 * these are a [androidx.glance.appwidget.GlanceAppWidget] and a set of
 * [androidx.glance.appwidget.action.ActionCallback]s — objects Glance constructs itself, given
 * nothing but a `Context`. Every one of them starts from the application context, so every one of
 * them can ask for this.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface WidgetEntryPoint {

    /** The live player: what is loaded, whether it is running, and the transport commands. */
    fun playbackConnection(): PlaybackConnection

    /** The durable answer to "what would you carry on with". */
    fun playbackQueueSource(): PlaybackQueueSource

    /** The *Continue listening* shelf. */
    fun podcastRepository(): PodcastRepository

    /** Starts an episode, and answers *Resume* the way the launcher shortcut answers it. */
    fun episodePlayer(): EpisodePlayer
}

/** The graph, from any context a widget or an action callback was handed. */
internal fun Context.widgetEntryPoint(): WidgetEntryPoint =
    EntryPointAccessors.fromApplication(applicationContext, WidgetEntryPoint::class.java)

/**
 * How many episodes the shelf under the transport may hold.
 *
 * Four, against the Listen screen's twelve, and for a different reason than screen space: a widget
 * is glanced at from a home screen, and a fifth cover is one the user would have scrolled to on the
 * Listen screen anyway. There is no scrolling here.
 */
internal const val WIDGET_SHELF_LIMIT = 4

/**
 * What the widget draws, as a flow.
 *
 * Three sources, and they are not the same kind of thing. The player and the shelf are live and are
 * observed; what the app would *carry on with* is a suspend read of the database, taken only when
 * the player has nothing loaded — which is exactly when it is cheap, and the only time the widget
 * needs an answer to it.
 *
 * `distinctUntilChanged` is not a tidiness: [PlaybackState][md.borisveriga.megapodcastplayer.core.media.PlaybackState]
 * re-emits twice a second while playing, and every snapshot that differs from the last is a
 * `RemoteViews` tree crossing into the launcher's process. The snapshot is built to be *equal* to
 * its predecessor most of the time — see `asPercent` in `WidgetSnapshot.kt` — and this is the line
 * that turns that into silence.
 *
 * Collected inside the widget's own composition, so the widget redraws when playback does. Joining
 * [PlaybackConnection.playbackState] costs nothing extra: `NowPlayingPublisher` already holds a
 * permanent subscription to it for the watch, so the controller is bound for the life of the
 * process either way.
 *
 * @return a snapshot whenever what the widget shows changes, starting with one.
 */
internal fun WidgetEntryPoint.widgetSnapshots(): Flow<WidgetSnapshot> = combine(
    playbackConnection().playbackState,
    podcastRepository().observeInProgressEpisodes(WIDGET_SHELF_LIMIT + 1),
) { playback, inProgress ->
    val resumable = if (playback.episodeId == null) {
        playbackQueueSource().resumableQueue().firstOrNull()
    } else {
        null
    }
    widgetSnapshot(
        playback = playback,
        resumable = resumable,
        inProgress = inProgress,
        shelfLimit = WIDGET_SHELF_LIMIT,
    )
}.distinctUntilChanged()
