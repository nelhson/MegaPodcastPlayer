package md.borisveriga.megapodcastplayer.wearsync

import md.borisveriga.megapodcastplayer.core.media.PlayableEpisode
import md.borisveriga.megapodcastplayer.core.media.PlaybackState
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings
import md.borisveriga.megapodcastplayer.core.wearprotocol.NowPlayingSnapshot
import md.borisveriga.megapodcastplayer.core.wearprotocol.QueuedEpisode

/**
 * How many queue entries the watch is sent.
 *
 * A data item is capped at 100 KB by the Data Layer and a full queue could be hundreds of episodes,
 * but nobody scrolls that far on a watch. Twenty is well past what anyone reaches and keeps the
 * payload in the low kilobytes, which is what matters over Bluetooth.
 */
private const val MAX_QUEUE_ENTRIES = 20

/**
 * Flattens the phone's three sources of playback truth into the one object the watch reads.
 *
 * Kept as a free function with no dependencies so the mapping — which is where the fiddly parts
 * live, like where "up next" starts — can be tested without a player, a database or a watch.
 *
 * @param playback what the phone's player is doing right now.
 * @param settings the user's speed and skip preferences, sent so the watch can label its own
 *   buttons with the same intervals the phone would apply.
 * @param queue the durable queue, in play order, including the episode playing.
 * @param publishedAtMs the phone's wall clock, which makes each published item unique.
 */
internal fun nowPlayingSnapshot(
    playback: PlaybackState,
    settings: PlaybackSettings,
    queue: List<PlayableEpisode>,
    publishedAtMs: Long,
): NowPlayingSnapshot {
    // The playing episode splits the durable queue into "played" and "up next". When nothing is
    // playing there is no split, and the whole queue is what the user could start from the watch.
    val currentIndex = queue.indexOfFirst { it.episode.id == playback.episodeId }
    val upNext = if (currentIndex >= 0) queue.drop(currentIndex + 1) else queue

    return NowPlayingSnapshot(
        episodeId = playback.episodeId,
        title = playback.title,
        showTitle = playback.showTitle,
        isPlaying = playback.isPlaying,
        isBuffering = playback.isBuffering,
        positionMs = playback.positionMs,
        durationMs = playback.durationMs,
        speed = playback.speed,
        skipForwardMs = settings.skipForwardMs,
        skipBackMs = settings.skipBackMs,
        hasNext = playback.hasNext,
        hasPrevious = playback.queueIndex > 0,
        upNext = upNext.take(MAX_QUEUE_ENTRIES).map { it.toQueuedEpisode() },
        publishedAtMs = publishedAtMs,
    )
}

/** Strips a queue entry down to the three fields a watch row can actually show. */
private fun PlayableEpisode.toQueuedEpisode() = QueuedEpisode(
    id = episode.id,
    title = episode.title,
    showTitle = showTitle,
)
