package md.borisveriga.bpodcat.core.media

import md.borisveriga.bpodcat.core.model.Episode

/**
 * An episode together with the show details the player needs to display it.
 *
 * [Episode] alone is not enough for a media notification: the lock screen and Bluetooth head unit
 * both want the show name as the "artist", and an episode without its own artwork has to fall back
 * to the show's. Rather than let `:core:media` reach into the database for that join, the caller
 * supplies it.
 *
 * @property episode the episode to play.
 * @property showTitle the owning podcast's title.
 * @property showArtworkUrl the owning podcast's artwork, used when the episode has none.
 */
data class PlayableEpisode(
    val episode: Episode,
    val showTitle: String,
    val showArtworkUrl: String?,
) {
    /** Artwork to display: the episode's own if it has any, otherwise the show's. */
    val artworkUrl: String? get() = episode.artworkUrl ?: showArtworkUrl
}
