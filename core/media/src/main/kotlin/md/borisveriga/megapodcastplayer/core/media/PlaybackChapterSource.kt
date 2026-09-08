package md.borisveriga.megapodcastplayer.core.media

import md.borisveriga.megapodcastplayer.core.model.chapters.Chapter

/**
 * Supplies the playback service with an episode's chapters.
 *
 * The same inversion as [PlaybackQueueSource], and for the same reason: resolving chapters means
 * reading the episode row, and possibly fetching the publisher's document, both of which live in
 * `:core:data` — which depends on this module and not the other way round. The service holds the
 * interface, the data layer implements it, and Hilt joins them.
 *
 * The service is a second caller of the resolution the player screen already does, not a different
 * one. Both must give the same answer, or the notification's *next* and the app's would move to
 * different places in the same episode.
 */
interface PlaybackChapterSource {

    /**
     * Resolves one episode's chapters.
     *
     * @param episodeId the episode currently loaded in the player.
     * @return the chapters in start order; empty when the episode has none, when it is no longer in
     *   the database, or when the list could not be fetched. A caller cannot tell those apart and
     *   has no reason to: each one means *previous* and *next* move between episodes.
     */
    suspend fun chaptersFor(episodeId: String): List<Chapter>
}
