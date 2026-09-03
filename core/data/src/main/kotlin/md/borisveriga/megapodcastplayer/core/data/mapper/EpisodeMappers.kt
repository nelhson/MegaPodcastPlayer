package md.borisveriga.megapodcastplayer.core.data.mapper

import md.borisveriga.megapodcastplayer.core.database.model.EpisodeWithShowEntity
import md.borisveriga.megapodcastplayer.core.database.model.asExternalModel
import md.borisveriga.megapodcastplayer.core.model.EpisodeWithShow

/** Maps a joined episode row to the form the downloads screen consumes. */
fun EpisodeWithShowEntity.asEpisodeWithShow(): EpisodeWithShow = EpisodeWithShow(
    episode = episode.asExternalModel(),
    showTitle = showTitle,
    showArtworkUrl = showArtworkUrl,
)
