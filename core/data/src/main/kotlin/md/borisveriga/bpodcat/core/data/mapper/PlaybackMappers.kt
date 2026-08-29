package md.borisveriga.bpodcat.core.data.mapper

import md.borisveriga.bpodcat.core.database.model.EpisodeWithShowEntity
import md.borisveriga.bpodcat.core.database.model.asExternalModel
import md.borisveriga.bpodcat.core.media.PlayableEpisode

/** Maps a joined episode row to the form the player consumes. */
fun EpisodeWithShowEntity.asPlayableEpisode(): PlayableEpisode = PlayableEpisode(
    episode = episode.asExternalModel(),
    showTitle = showTitle,
    showArtworkUrl = showArtworkUrl,
)
