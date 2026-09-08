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
 * [newEpisodeCount] and [unplayedCount] are two different facts and are deliberately both here.
 * *New* means arrived since the user last looked; *unplayed* means never started. A show can have
 * nothing new and forty unplayed episodes, and the library is the one screen where that difference
 * decides what to do next — so the badge counts the first and the sort order counts the second.
 *
 * @property podcast the show itself.
 * @property episodeCount total number of episodes known locally.
 * @property newEpisodeCount episodes discovered by a refresh that the user has not seen yet.
 * @property downloadedCount episodes fully downloaded to the device.
 * @property unplayedCount episodes never started — neither played nor left part way through, the
 *   same rule `EpisodeFilter.UNPLAYED` applies on the show page. Defaulted because the previews and
 *   tests that exercise the badge have no opinion about it.
 * @property latestPublishedAt the publication date of the show's most recent dated episode, or null
 *   when the feed dates none of them. Defaulted for the same reason.
 */
data class PodcastWithCounts(
    val podcast: Podcast,
    val episodeCount: Int,
    val newEpisodeCount: Int,
    val downloadedCount: Int,
    val unplayedCount: Int = 0,
    val latestPublishedAt: Instant? = null,
)
