package md.borisveriga.megapodcastplayer.feature.podcast

import androidx.annotation.StringRes
import md.borisveriga.megapodcastplayer.core.model.EpisodeFilter

/**
 * The caption on each filter chip.
 *
 * The rule a filter applies is a fact about episodes and lives in `:core:model`; the words for it
 * are a fact about this screen and live here, with the `strings.xml` that holds them. Splitting
 * them is what let the chosen filter become something the app remembers per show without dragging
 * an Android resource id into a pure-JVM module.
 */
@get:StringRes
val EpisodeFilter.labelResId: Int
    get() = when (this) {
        EpisodeFilter.ALL -> R.string.podcast_filter_all
        EpisodeFilter.UNPLAYED -> R.string.podcast_filter_unplayed
        EpisodeFilter.IN_PROGRESS -> R.string.podcast_filter_in_progress
        EpisodeFilter.DOWNLOADED -> R.string.podcast_filter_downloaded
    }
