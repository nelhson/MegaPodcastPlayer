package md.borisveriga.megapodcastplayer.core.model.backup

import kotlinx.serialization.Serializable
import md.borisveriga.megapodcastplayer.core.model.PodcastSource

/**
 * The user-exported backup document.
 *
 * The database has no migrations — a schema change recreates every table — so this file is the only
 * thing that carries a library across a version that changes the schema. It therefore stores
 * *state*, never content: episodes are re-fetched from their feeds on restore, and only the columns
 * the user themselves created (a position, a played flag, a hand-built queue) are written here.
 *
 * Every reference is a `(feedUrl, guid)` pair rather than a row id. That is not a portability
 * nicety: `podcastIdOf` and `episodeIdOf` are pure functions of exactly those two strings, so
 * re-adding a feed regenerates byte-identical ids and the state below can be re-applied by primary
 * key with no matching heuristics at all.
 *
 * @property version the document's schema version; see [CURRENT_BACKUP_VERSION].
 * @property exportedAtMs when the document was written, epoch milliseconds.
 * @property podcasts every show in the library.
 * @property episodes listening state, only for episodes the user has actually touched.
 * @property queue the hand-built play queue, in order.
 * @property downloads episodes that were downloaded, as an intention only — no audio is stored.
 * @property moments saved timestamped bookmarks.
 */
@Serializable
data class BackupFile(
    val version: Int = CURRENT_BACKUP_VERSION,
    val exportedAtMs: Long,
    val podcasts: List<BackupPodcast> = emptyList(),
    val episodes: List<BackupEpisodeState> = emptyList(),
    val queue: List<BackupQueueEntry> = emptyList(),
    val downloads: List<BackupDownload> = emptyList(),
    val moments: List<BackupMoment> = emptyList(),
)

/**
 * A subscribed show.
 *
 * @property feedUrl the RSS or YouTube Atom feed URL; the show's identity, stored verbatim so that
 *   re-adding it hashes to the same podcast id.
 * @property source whether the episode list comes from RSS or a YouTube playlist.
 * @property title show title, used to name the show in a restore's failure report before any feed
 *   has been fetched.
 * @property author show author.
 * @property itunesId Apple's `collectionId`, when the show was resolved through the iTunes API.
 * @property sortOrder the show's place in the hand-ordered library, smallest first.
 * @property autoRefresh whether the periodic refresh worker should include this show.
 * @property addedAtMs when the user originally added the show, epoch milliseconds.
 */
@Serializable
data class BackupPodcast(
    val feedUrl: String,
    val source: PodcastSource,
    val title: String,
    val author: String = "",
    val itunesId: Long? = null,
    val sortOrder: Int = 0,
    val autoRefresh: Boolean = true,
    val addedAtMs: Long = 0L,
)

/**
 * Listening state for one episode.
 *
 * Written only when the user has actually touched the episode — a position past zero or a played
 * flag. A library of twenty shows holds thousands of untouched rows whose state is entirely
 * implied by their absence.
 *
 * @property feedUrl the owning show's feed URL.
 * @property guid the episode's feed `guid`, the half of its id the publisher controls.
 * @property positionMs stored playback position.
 * @property isPlayed whether the episode was marked played.
 */
@Serializable
data class BackupEpisodeState(
    val feedUrl: String,
    val guid: String,
    val positionMs: Long = 0L,
    val isPlayed: Boolean = false,
)

/**
 * One entry of the hand-built play queue.
 *
 * @property feedUrl the owning show's feed URL.
 * @property guid the episode's feed `guid`.
 * @property position the entry's place in the queue, smallest first.
 */
@Serializable
data class BackupQueueEntry(
    val feedUrl: String,
    val guid: String,
    val position: Int,
)

/**
 * An episode that was downloaded when the backup was written.
 *
 * The audio itself is not in the file, so this is a record of intent: restoring it re-queues the
 * download rather than recovering anything. That is why the restore sheet makes acting on this list
 * an explicit, off-by-default choice.
 *
 * @property feedUrl the owning show's feed URL.
 * @property guid the episode's feed `guid`.
 */
@Serializable
data class BackupDownload(
    val feedUrl: String,
    val guid: String,
)

/**
 * A saved timestamped bookmark.
 *
 * Present from version 1 of this document, as an empty list until the Moments feature exists, so
 * that a file written by either build decodes in the other.
 *
 * @property feedUrl the owning show's feed URL.
 * @property guid the episode's feed `guid`.
 * @property positionMs the marked position within the episode.
 * @property note the user's note, if they added one.
 * @property createdAtMs when the moment was saved, epoch milliseconds.
 */
@Serializable
data class BackupMoment(
    val feedUrl: String,
    val guid: String,
    val positionMs: Long,
    val note: String? = null,
    val createdAtMs: Long = 0L,
)

/**
 * The version this build writes.
 *
 * Incremented only when a field's *meaning* changes; adding an optional field does not need it,
 * because a reader older than the writer tolerates unknown keys and a reader newer than the writer
 * fills the gap from the property default.
 */
const val CURRENT_BACKUP_VERSION: Int = 1
