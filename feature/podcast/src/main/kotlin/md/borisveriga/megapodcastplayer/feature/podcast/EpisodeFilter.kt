package md.borisveriga.megapodcastplayer.feature.podcast

import androidx.annotation.StringRes
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.Episode

/**
 * Which episodes of a show are on screen.
 *
 * Client-side over the episodes already in `PodcastDetailUiState`, deliberately: a show's list is
 * bounded by the feed, it is already in memory, and a database query per chip would make a filter
 * that is meant to feel instant wait on Room.
 *
 * @property labelResId the chip's caption.
 */
enum class EpisodeFilter(@param:StringRes val labelResId: Int) {

    /** Everything the feed carries. */
    ALL(R.string.podcast_filter_all),

    /** Never started. What "is there anything new" means. */
    UNPLAYED(R.string.podcast_filter_unplayed),

    /** Started and not finished — the ones worth resuming. */
    IN_PROGRESS(R.string.podcast_filter_in_progress),

    /** On the device, and therefore playable with no connection. */
    DOWNLOADED(R.string.podcast_filter_downloaded),
    ;

    /**
     * Whether one episode belongs in this filter.
     *
     * @param episode the episode to test.
     * @return true when it should be shown.
     */
    fun matches(episode: Episode): Boolean = when (this) {
        ALL -> true

        // Not `!isPlayed`: an episode abandoned half way through is not new, and putting it under
        // "Unplayed" is how a list of things to start becomes a list of things already begun.
        UNPLAYED -> !episode.isPlayed && episode.positionMs <= 0L

        IN_PROGRESS -> episode.isInProgress

        DOWNLOADED -> episode.downloadState == DownloadState.COMPLETED
    }
}

/**
 * Applies a filter to a show's episodes.
 *
 * A function rather than a `filter` call at the call site so the rule has one home and can be
 * tested without a composition.
 *
 * @param filter the chip the user picked.
 * @return the episodes to show, in the order they arrived.
 */
fun List<Episode>.filterBy(filter: EpisodeFilter): List<Episode> =
    if (filter == EpisodeFilter.ALL) this else filter { filter.matches(it) }
