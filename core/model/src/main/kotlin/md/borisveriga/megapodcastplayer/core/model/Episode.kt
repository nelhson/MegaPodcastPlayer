package md.borisveriga.megapodcastplayer.core.model

import java.time.Instant

/**
 * A single episode of a [Podcast].
 *
 * @property id stable local identifier, derived from the owning podcast and the feed's `guid` by
 *   [episodeIdOf]; survives feed re-fetches so playback progress is never lost.
 * @property podcastId id of the owning [Podcast].
 * @property guid the feed's `<guid>`, or the enclosure URL when the feed omits one.
 * @property title episode title.
 * @property description episode notes; may contain HTML from `content:encoded`.
 * @property audioUrl the `<enclosure>` URL that is streamed or downloaded.
 * @property artworkUrl episode-specific artwork; falls back to the show artwork when null.
 * @property durationMs duration reported by `itunes:duration`, null when the feed omits it.
 * @property publishedAt publication date, null when the feed's `pubDate` is missing or unparseable.
 * @property sizeBytes enclosure `length` in bytes, when advertised.
 * @property positionMs playback position, persisted while playing so playback resumes exactly.
 * @property isPlayed whether the episode has been played to (near) completion.
 * @property isNew whether the episode arrived in a refresh and has not been seen in the UI yet.
 * @property downloadState current offline availability.
 * @property downloadedBytes bytes written so far; mirrors Media3's download index.
 * @property downloadPercent download progress in `0f..100f`.
 */
data class Episode(
    val id: String,
    val podcastId: String,
    val guid: String,
    val title: String,
    val description: String,
    val audioUrl: String,
    val artworkUrl: String?,
    val durationMs: Long?,
    val publishedAt: Instant?,
    val sizeBytes: Long?,
    val positionMs: Long = 0L,
    val isPlayed: Boolean = false,
    val isNew: Boolean = false,
    val downloadState: DownloadState = DownloadState.NOT_DOWNLOADED,
    val downloadedBytes: Long = 0L,
    val downloadPercent: Float = 0f,
) {
    /**
     * Fraction of the episode already played, in `0f..1f`.
     *
     * Returns `0f` when the duration is unknown, which is the safe default for progress bars.
     */
    val playedFraction: Float
        get() = durationMs?.takeIf { it > 0L }?.let { (positionMs.toFloat() / it).coerceIn(0f, 1f) } ?: 0f

    /**
     * Whether the episode has been started but not finished.
     *
     * The "continue listening" shelf and the progress hairline on a row both ask this question, and
     * both got it subtly wrong when they asked it inline: a finished episode keeps its
     * [positionMs], so `positionMs > 0` alone is true for everything ever played.
     */
    val isInProgress: Boolean
        get() = positionMs > 0L && !isPlayed
}

/** Offline availability of an [Episode]'s audio. */
enum class DownloadState {
    /** Not on the device; playing it streams over the network. */
    NOT_DOWNLOADED,

    /** Requested by the user, waiting for its turn or for an unmetered network. */
    QUEUED,

    /** Actively downloading. */
    DOWNLOADING,

    /** Fully available offline. */
    COMPLETED,

    /** The download stopped with an error and can be retried. */
    FAILED,
}
