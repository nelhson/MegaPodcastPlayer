package md.borisveriga.bpodcat.core.model

/**
 * A downloaded [Episode] together with the show it belongs to.
 *
 * The downloads screen lists episodes from every show at once, so a bare [Episode] is not enough to
 * render a row: "Episode 400" means nothing without the show's name next to it. Carrying the two
 * columns the row needs — rather than a whole [Podcast] — keeps the read cheap, the same trade-off
 * the player's queue read makes.
 *
 * @property episode the downloaded episode.
 * @property showTitle title of the owning show.
 * @property showArtworkUrl the show's artwork, used when the episode publishes none of its own.
 */
data class DownloadedEpisode(
    val episode: Episode,
    val showTitle: String,
    val showArtworkUrl: String?,
) {
    /** Episode artwork when the feed provides it, otherwise the show's. */
    val artworkUrl: String?
        get() = episode.artworkUrl ?: showArtworkUrl
}
