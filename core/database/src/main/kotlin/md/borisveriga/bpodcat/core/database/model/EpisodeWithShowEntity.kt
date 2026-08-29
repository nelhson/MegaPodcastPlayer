package md.borisveriga.bpodcat.core.database.model

import androidx.room.ColumnInfo
import androidx.room.Embedded

/**
 * An episode row joined with the two columns of its show that the player needs.
 *
 * The media notification labels an episode with the show's name and falls back to the show's
 * artwork, so every read that feeds the player has to make that join. Pulling only two columns
 * rather than embedding a whole [PodcastEntity] keeps the row small — a 500-episode queue read would
 * otherwise carry 500 copies of the show description.
 *
 * @property episode the episode row.
 * @property showTitle the owning podcast's title.
 * @property showArtworkUrl the owning podcast's artwork, if it has any.
 */
data class EpisodeWithShowEntity(
    @Embedded val episode: EpisodeEntity,
    @ColumnInfo(name = "show_title") val showTitle: String,
    @ColumnInfo(name = "show_artwork_url") val showArtworkUrl: String?,
)
