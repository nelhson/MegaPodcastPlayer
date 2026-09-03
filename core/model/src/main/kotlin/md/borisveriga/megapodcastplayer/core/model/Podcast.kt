package md.borisveriga.megapodcastplayer.core.model

import java.time.Instant

/**
 * A podcast the user has added to their library.
 *
 * @property id stable local identifier, derived from [feedUrl] by [podcastIdOf] so that the same
 *   show added twice (once by search, once by pasted link) collapses into one row.
 * @property itunesId Apple's `collectionId`, when the show was resolved through the iTunes API.
 *   Null for shows added by raw RSS URL.
 * @property title show title as published in the feed.
 * @property author show author (`itunes:author` / Apple's `artistName`).
 * @property feedUrl RSS feed URL; the unique key from the user's point of view.
 * @property artworkUrl highest-resolution artwork known for the show, if any.
 * @property description show description, plain text.
 * @property addedAt when the user added the show.
 * @property lastRefreshAt when the feed was last successfully fetched, null if never.
 * @property etag `ETag` returned by the last successful feed fetch, used for conditional GETs.
 * @property lastModified `Last-Modified` returned by the last successful feed fetch.
 * @property autoRefresh whether the periodic refresh worker should include this show.
 * @property source where the episode list comes from. Defaults to [PodcastSource.RSS] so the
 *   many places that build a [Podcast] for a preview or a test need no change.
 */
data class Podcast(
    val id: String,
    val itunesId: Long?,
    val title: String,
    val author: String,
    val feedUrl: String,
    val artworkUrl: String?,
    val description: String,
    val addedAt: Instant,
    val lastRefreshAt: Instant?,
    val etag: String?,
    val lastModified: String?,
    val autoRefresh: Boolean,
    val source: PodcastSource = PodcastSource.RSS,
)

/**
 * A podcast plus the aggregate counts the library screen displays.
 *
 * @property podcast the show itself.
 * @property episodeCount total number of episodes known locally.
 * @property newEpisodeCount episodes discovered by a refresh that the user has not seen yet.
 * @property downloadedCount episodes fully downloaded to the device.
 */
data class PodcastWithCounts(
    val podcast: Podcast,
    val episodeCount: Int,
    val newEpisodeCount: Int,
    val downloadedCount: Int,
)
