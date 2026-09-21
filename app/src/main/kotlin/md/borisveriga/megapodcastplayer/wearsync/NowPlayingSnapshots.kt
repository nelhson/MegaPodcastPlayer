package md.borisveriga.megapodcastplayer.wearsync

import md.borisveriga.megapodcastplayer.core.media.PlayableEpisode
import md.borisveriga.megapodcastplayer.core.media.PlaybackState
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.EpisodeWithShow
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings
import md.borisveriga.megapodcastplayer.core.wearprotocol.NowPlayingSnapshot
import md.borisveriga.megapodcastplayer.core.wearprotocol.WatchEpisode

/**
 * How many entries of each list the watch is sent.
 *
 * A data item is capped at 100 KB by the Data Layer and a full queue — or a library's worth of
 * downloads — could be hundreds of episodes, but nobody scrolls that far on a watch. Twenty each is
 * well past what anyone reaches and keeps the payload in the low kilobytes, which is what matters
 * over Bluetooth.
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
 * @param downloads every episode the download stack is tracking, in the order the phone's downloads
 *   screen lists them. Only the finished ones reach the watch: a transfer still running is not
 *   something a wrist can play, and a failed one is not something it can fix.
 * @param publishedAtMs the phone's wall clock, which makes each published item unique.
 */
internal fun nowPlayingSnapshot(
    playback: PlaybackState,
    settings: PlaybackSettings,
    queue: List<PlayableEpisode>,
    downloads: List<EpisodeWithShow>,
    publishedAtMs: Long,
): NowPlayingSnapshot {
    // The playing episode splits the durable queue into "played" and "up next". When nothing is
    // playing there is no split, and the whole queue is what the user could start from the watch.
    val currentIndex = queue.indexOfFirst { it.episode.id == playback.episodeId }
    val upNext = if (currentIndex >= 0) queue.drop(currentIndex + 1) else queue

    // Everything already queued is left out of the downloaded list — including the episode playing,
    // which is in the queue — so the two sections never offer the same episode twice with two
    // different meanings. The whole queue and not just `upNext`: an episode behind the playhead is
    // still queued, and offering to queue it again from below would do nothing visible.
    val queuedIds = queue.mapTo(mutableSetOf()) { it.episode.id }
    val downloaded = downloads
        .filter { it.episode.downloadState == DownloadState.COMPLETED }
        .filterNot { it.episode.id in queuedIds }

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
        upNext = upNext.take(MAX_QUEUE_ENTRIES).map { it.toWatchEpisode() },
        downloaded = downloaded.take(MAX_QUEUE_ENTRIES).map { it.toWatchEpisode() },
        publishedAtMs = publishedAtMs,
    )
}

/** Strips a queue entry down to the three fields a watch row can actually show. */
private fun PlayableEpisode.toWatchEpisode() = WatchEpisode(
    id = episode.id,
    title = episode.title,
    showTitle = showTitle,
)

/** The same three fields for a download, which the phone carries in a type of its own. */
private fun EpisodeWithShow.toWatchEpisode() = WatchEpisode(
    id = episode.id,
    title = episode.title,
    showTitle = showTitle,
)
