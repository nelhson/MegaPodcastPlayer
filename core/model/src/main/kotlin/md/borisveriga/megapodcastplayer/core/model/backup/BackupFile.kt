package md.borisveriga.megapodcastplayer.core.model.backup

import kotlinx.serialization.Serializable
import md.borisveriga.megapodcastplayer.core.model.PodcastSource

/**
 * A library's subscriptions, and nothing else.
 *
 * This document once carried listening state, the queue, downloads and moments as well. It does
 * not any more, and the absence is the point: a show is a *link* — an RSS feed URL or a YouTube
 * playlist's — and everything else about it is derived from fetching that link. Exporting the
 * derivations meant a file that could disagree with the feed it came from, a restore that wrote
 * rows for episodes the publisher had since pruned, and four kinds of tally in a summary nobody
 * read. What leaves the app now is the list of links; what comes back in is the list of links.
 *
 * It is not a file the user picks any more either. [OpmlCodec] writes and reads what they pick;
 * this is the shape that document is turned into on its way to the restorer, and the JSON is a
 * handover between one process and the worker that outlives it.
 *
 * Every show is named by its feed URL rather than by a row id. That is not a portability nicety:
 * `podcastIdOf` is a pure function of exactly that string, so re-adding a feed regenerates a
 * byte-identical id with no matching heuristics at all.
 *
 * @property exportedAtMs when the document was written, epoch milliseconds.
 * @property podcasts every show in the library.
 */
@Serializable
data class BackupFile(
    val exportedAtMs: Long,
    val podcasts: List<BackupPodcast> = emptyList(),
)

/**
 * A subscribed show.
 *
 * Four fields, and three of them exist to make the fourth usable: a URL is what is restored, the
 * title is how a feed that could not be fetched is named in the report, the source decides whether
 * the show is played as RSS or as YouTube, and the order is the one thing about the *list* that a
 * user arranged by hand.
 *
 * @property feedUrl the RSS or YouTube Atom feed URL; the show's identity, stored verbatim so that
 *   re-adding it hashes to the same podcast id.
 * @property source whether the episode list comes from RSS or a YouTube playlist.
 * @property title show title, used to name the show in a restore's failure report before any feed
 *   has been fetched.
 * @property sortOrder the show's place in the hand-ordered library, smallest first.
 */
@Serializable
data class BackupPodcast(
    val feedUrl: String,
    val source: PodcastSource,
    val title: String,
    val sortOrder: Int = 0,
)
