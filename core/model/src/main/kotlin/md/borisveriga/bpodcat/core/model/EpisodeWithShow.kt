package md.borisveriga.bpodcat.core.model

/**
 * An [Episode] together with the show it belongs to.
 *
 * Any screen that mixes shows in one list — the Latest feed, downloads, the queue — needs this
 * rather than a bare [Episode]: "Episode 400" means nothing without the show's name next to it.
 * Carrying the two columns the row needs, rather than a whole [Podcast], keeps the read cheap; it
 * is the same trade-off the player's queue read makes.
 *
 * @property episode the episode.
 * @property showTitle title of the owning show.
 * @property showArtworkUrl the show's artwork, used when the episode publishes none of its own.
 */
data class EpisodeWithShow(
    val episode: Episode,
    val showTitle: String,
    val showArtworkUrl: String?,
) {
    /** Episode artwork when the feed provides it, otherwise the show's. */
    val artworkUrl: String?
        get() = episode.artworkUrl ?: showArtworkUrl
}
