package md.borisveriga.megapodcastplayer.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The order a show's episodes are listed in.
 *
 * Two, not four. A podcast has one meaningful axis — when it was published — and the two directions
 * of it answer two different kinds of show: a news feed is read newest first, and a serial, an
 * audiobook or a course only makes sense from the beginning. Sorting by title or duration are
 * questions nobody asks of a chronology.
 */
@Serializable
enum class EpisodeSort {

    /** Most recent at the top. What a subscription feed is for. */
    NEWEST_FIRST,

    /** Episode one at the top. What a serialised show needs and never had. */
    OLDEST_FIRST,
}

/**
 * Reverses a show's episodes when the user asked for oldest first.
 *
 * Named `orderedBy` rather than `sortedBy` so it cannot be mistaken for the standard library's,
 * which takes a selector and would silently win an overload resolution one refactor from now.
 *
 * Applied over the loaded list rather than in SQL for the same reason [filterBy] is: the list is in
 * memory, bounded by the feed, and a re-query would put a spinner behind a toggle that should feel
 * like flipping the page over.
 *
 * The incoming order is the authority for what "newest" means — newest-first from the feed for an
 * RSS show, the user's own arrangement for a YouTube playlist — so this reverses rather than
 * re-sorts by date. An episode with no publication date would otherwise be flung to one end by a
 * comparator, and there is at least one feed in this library that omits `pubDate` on half its rows.
 *
 * @param sort the direction the user chose.
 * @return the episodes in that order.
 */
fun List<Episode>.orderedBy(sort: EpisodeSort): List<Episode> =
    if (sort == EpisodeSort.NEWEST_FIRST) this else asReversed()

/**
 * The decisions the user has made about one show.
 *
 * Per show rather than per app because that is how they are actually made: one podcast is listened
 * to at 1.8× and one is not, one is a serial read from the beginning and one is a news feed. The
 * app-wide values in `PlaybackSettings` and `DownloadSettings` remain the default for every show
 * that has said nothing.
 *
 * Stored as preferences rather than as a database table, which is not an accident: the database
 * here has no migrations and a version bump wipes the library, so a schema change costs the user
 * everything they have added. A remembered sort order is not worth that, and these values are
 * preferences in the ordinary sense — they describe how the user wants to be shown something, not
 * what the app knows.
 *
 * Absent means default: a show with no stored row behaves exactly as it did before this existed,
 * and clearing a show's settings is deleting its entry rather than writing a row full of nulls.
 *
 * @property episodeSort which end of the show the list starts at.
 * @property episodeFilter which episodes are shown; remembered so that a show being worked through
 *   offline does not reset to *All* on every visit.
 * @property autoDownload whether new episodes of this show are fetched automatically; null follows
 *   the app-wide setting. Three states rather than two because the app-wide answer is the one most
 *   shows should keep, and a plain switch would force every show to be an exception to it.
 * @property speed the rate this show plays at, or null to play at the app's speed. The setting most
 *   worth having per show: one host is followable at 2× and the next is not.
 * @property notifyNewEpisodes whether a background refresh that finds an episode of this show says
 *   so. A daily show and a quarterly one do not deserve the same interruption.
 * @property skipIntroMs how much of the start of every episode to skip. A show with a
 *   forty-second musical opening is the case; zero, the default, skips nothing.
 */
@Serializable
data class ShowSettings(
    val episodeSort: EpisodeSort = EpisodeSort.NEWEST_FIRST,
    val episodeFilter: EpisodeFilter = EpisodeFilter.ALL,
    val autoDownload: Boolean? = null,
    val speed: Float? = null,
    val notifyNewEpisodes: Boolean = true,
    val skipIntroMs: Long = 0L,
) {
    /** Whether this is indistinguishable from having said nothing, and can therefore be deleted. */
    val isDefault: Boolean get() = this == DEFAULT

    /**
     * The rate this show should play at.
     *
     * @param appSpeed the app-wide rate, used when this show has no opinion.
     * @return the rate to apply, clamped to what the player accepts.
     */
    fun speedOr(appSpeed: Float): Float =
        (speed ?: appSpeed).coerceIn(PlaybackSettings.SPEED_RANGE)

    /**
     * Whether a newly discovered episode of this show should be downloaded.
     *
     * @param appAutoDownload the app-wide answer, used when this show has no opinion.
     * @return true when the episode should be queued.
     */
    fun autoDownloadOr(appAutoDownload: Boolean): Boolean = autoDownload ?: appAutoDownload

    companion object {
        /** What every show behaves like until the user changes something. */
        val DEFAULT = ShowSettings()
    }
}

/**
 * Reads and writes the per-show settings map as one preference value.
 *
 * One key holding every show rather than a key per show, because the screen that reads it wants
 * one flow and the library is tens of rows, not thousands. The whole map is rewritten on each
 * change, which at this size is cheaper than the bookkeeping that would avoid it.
 *
 * Decoding is deliberately forgiving in one direction only: a blob this app cannot read becomes an
 * empty map, so every show falls back to its defaults. That is the correct failure for a
 * preference — the user sees their shows sorted the way they were before they ever touched the
 * control — where refusing to load would put an error on screen about a sort order.
 */
object ShowSettingsCodec {

    private val json = Json {
        // Written and read by the same build; a value this app does not recognise is corruption,
        // and the empty-map fallback below is what handles it.
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    /**
     * Encodes the map for storage.
     *
     * @param settings every show that has settings, keyed by podcast id.
     * @return the JSON to store.
     */
    fun encode(settings: Map<String, ShowSettings>): String = json.encodeToString(settings)

    /**
     * Decodes a stored map.
     *
     * @param stored the JSON previously written by [encode], or null when nothing has been stored.
     * @return the settings per podcast id; empty when there is nothing stored or nothing readable.
     */
    fun decode(stored: String?): Map<String, ShowSettings> {
        if (stored.isNullOrEmpty()) return emptyMap()
        return try {
            json.decodeFromString<Map<String, ShowSettings>>(stored)
        } catch (_: SerializationException) {
            emptyMap()
        } catch (_: IllegalArgumentException) {
            emptyMap()
        }
    }
}
