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

    /** Reads a [DownloadState] back; a row can only hold a name this build wrote. */
    @TypeConverter
    fun stringToDownloadState(value: String): DownloadState = DownloadState.valueOf(value)

    @TypeConverter
    fun podcastSourceToString(source: PodcastSource): String = source.name

    /** Reads a [PodcastSource] back; a row can only hold a name this build wrote. */
    @TypeConverter
    fun stringToPodcastSource(value: String): PodcastSource = PodcastSource.valueOf(value)
}
