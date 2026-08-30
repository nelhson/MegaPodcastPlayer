package md.borisveriga.bpodcat.core.data.mapper

import md.borisveriga.bpodcat.core.database.model.EpisodeWithShowEntity
import md.borisveriga.bpodcat.core.database.model.asExternalModel
import md.borisveriga.bpodcat.core.model.EpisodeWithShow

/** Maps a joined episode row to the form the downloads screen consumes. */
fun EpisodeWithShowEntity.asEpisodeWithShow(): EpisodeWithShow = EpisodeWithShow(
    episode = episode.asExternalModel(),
    showTitle = showTitle,
    showArtworkUrl = showArtworkUrl,
)
