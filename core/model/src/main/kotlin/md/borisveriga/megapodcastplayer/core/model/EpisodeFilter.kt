package md.borisveriga.megapodcastplayer.core.model

import kotlinx.serialization.Serializable

/**
 * Which episodes of a show are on screen.
 *
 * Applied client-side over the episodes already loaded, deliberately: a show's list is bounded by
 * the feed, it is already in memory, and a database query per chip would make a filter that is
 * meant to feel instant wait on Room.
 *
 * It lives here rather than beside the screen that draws the chips because the choice is now
 * remembered per show (see [ShowSettings]), which makes it a fact about the user's library rather
 * than a piece of one screen's state. The chip captions stay in the feature, where the strings are.
 */
@Serializable
enum class EpisodeFilter {

    /** Everything the feed carries. */
    ALL,

    /** Never started. What "is there anything new" means. */
    UNPLAYED,

    /** Started and not finished — the ones worth resuming. */
    IN_PROGRESS,

    /** On the device, and therefore playable with no connection. */
    DOWNLOADED,
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
