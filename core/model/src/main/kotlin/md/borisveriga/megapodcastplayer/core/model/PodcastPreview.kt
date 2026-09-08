package md.borisveriga.megapodcastplayer.core.model

/**
 * A show as it would be if it were added — fetched, parsed, and stored nowhere.
 *
 * Subscribing used to be the only way to find out what a show was. A search result carries a title,
 * an author and a cover, which is enough to recognise a show you already know and nothing like
 * enough to decide about one you do not; the way to read the description or see what the last few
 * episodes were was to subscribe, look, and unsubscribe — and unsubscribing is the one action in
 * this app that cannot be undone.
 *
 * The values here are exactly the ones a subscription would write. [Podcast.id] and each episode's
 * id are derived the same way, from the feed URL and the item's guid, so an episode played from a
 * preview and the same episode played after subscribing are the same episode as far as the player,
 * the cache and the download stack are concerned.
 *
 * @property podcast the show, filled in as the stored row would be. Not in the database.
 * @property episodes the most recent few episodes, newest first — enough to see what the show
 *   publishes and how often, and no more: this is a glance, not the show page.
 * @property totalEpisodeCount how many the feed carries in all, which is what tells a five-episode
 *   show apart from a five-hundred-episode one when only five are listed.
 */
data class PodcastPreview(
    val podcast: Podcast,
    val episodes: List<Episode>,
    val totalEpisodeCount: Int,
) {
    companion object {
        /**
         * How many episodes a preview carries.
         *
         * Enough to see the shape of the show — what it publishes, and roughly how often — while
         * still fitting in a sheet the user can read without scrolling twice.
         */
        const val EPISODE_LIMIT = 5
    }
}
