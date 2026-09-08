package md.borisveriga.megapodcastplayer.core.database.model

import androidx.room.ColumnInfo
import androidx.room.Embedded
import md.borisveriga.megapodcastplayer.core.model.MomentWithEpisode

/**
 * A moment joined with everything the moments screen displays and everything a share needs.
 *
 * Shaped the way [EpisodeWithShowEntity] is: the join is done in SQL because a moments list is
 * always about episodes that are always about shows, and doing it per row in Kotlin would be one
 * query per moment.
 *
 * The two URLs travel with the row rather than being looked up when a share is tapped. They are not
 * display data, but they are what makes a moment worth keeping — a shared or exported moment
 * carries a link built from [audioUrl] and a show identified by [feedUrl] — and fetching them
 * separately would mean the export doing a second read of the same two joins.
 *
 * @property moment the moment's own columns.
 * @property episodeTitle the episode's title.
 * @property showTitle the show's title.
 * @property showArtworkUrl the show's artwork.
 * @property feedUrl the show's feed URL: its identity, and the string that re-adds it.
 * @property audioUrl the episode's stored audio URL, real or the YouTube sentinel.
 */
data class MomentWithEpisodeEntity(
    @Embedded val moment: MomentEntity,
    @ColumnInfo(name = "episode_title") val episodeTitle: String,
    @ColumnInfo(name = "show_title") val showTitle: String,
    @ColumnInfo(name = "show_artwork_url") val showArtworkUrl: String?,
    @ColumnInfo(name = "feed_url") val feedUrl: String,
    @ColumnInfo(name = "audio_url") val audioUrl: String,
)

/** Maps the joined row to the domain model. */
fun MomentWithEpisodeEntity.asExternalModel(): MomentWithEpisode = MomentWithEpisode(
    moment = moment.asExternalModel(),
    episodeTitle = episodeTitle,
    showTitle = showTitle,
    showArtworkUrl = showArtworkUrl,
    feedUrl = feedUrl,
    audioUrl = audioUrl,
)
