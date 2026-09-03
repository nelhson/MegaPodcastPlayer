package md.borisveriga.megapodcastplayer.core.database

import androidx.room.TypeConverter
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.PodcastSource

/**
 * Room type converters.
 *
 * Enums are stored by name rather than ordinal so that reordering one can never silently
 * reinterpret existing rows.
 */
class MegaPodcastPlayerTypeConverters {

    @TypeConverter
    fun downloadStateToString(state: DownloadState): String = state.name

    /**
     * Reads a [DownloadState] back.
     *
     * Unknown values (a row written by a newer build, then downgraded) fall back to
     * [DownloadState.NOT_DOWNLOADED], which is always safe: the episode is simply shown as
     * streamable.
     */
    @TypeConverter
    fun stringToDownloadState(value: String): DownloadState =
        DownloadState.entries.firstOrNull { it.name == value } ?: DownloadState.NOT_DOWNLOADED

    @TypeConverter
    fun podcastSourceToString(source: PodcastSource): String = source.name

    /**
     * Reads a [PodcastSource] back.
     *
     * Unknown values fall back to [PodcastSource.RSS] for the same reason as above, and because
     * RSS is the behaviour that needs no extra machinery: the row is fetched and parsed as an
     * ordinary feed rather than reaching for an extractor that may not understand it.
     */
    @TypeConverter
    fun stringToPodcastSource(value: String): PodcastSource =
        PodcastSource.entries.firstOrNull { it.name == value } ?: PodcastSource.RSS
}
