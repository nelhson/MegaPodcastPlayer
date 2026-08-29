package md.borisveriga.bpodcat.core.data.mapper

import java.time.Instant
import md.borisveriga.bpodcat.core.database.model.EpisodeEntity
import md.borisveriga.bpodcat.core.database.model.PodcastEntity
import md.borisveriga.bpodcat.core.model.PodcastSource
import md.borisveriga.bpodcat.core.model.episodeIdOf
import md.borisveriga.bpodcat.core.model.podcastIdOf
import md.borisveriga.bpodcat.core.network.rss.FeedChannel
import md.borisveriga.bpodcat.core.network.rss.FeedItem

/**
 * Builds the database row for a newly added podcast.
 *
 * @param feedUrl the resolved feed URL; also the source of the podcast's stable id.
 * @param itunesId Apple's collection id when the show came from Apple, else null.
 * @param now the moment the user added the show.
 * @param fallbackTitle title to use when the feed's `<title>` is empty — Apple's `collectionName`
 *   is a better label than a blank row.
 * @param fallbackArtworkUrl artwork to use when the feed publishes none; Apple's 600px artwork is
 *   usually higher quality than the feed's anyway.
 * @param source where the episode list came from, which decides how the show's audio is obtained
 *   and whether the UI badges it.
 */
fun FeedChannel.asPodcastEntity(
    feedUrl: String,
    itunesId: Long?,
    now: Instant,
    fallbackTitle: String? = null,
    fallbackArtworkUrl: String? = null,
    source: PodcastSource = PodcastSource.RSS,
): PodcastEntity = PodcastEntity(
    id = podcastIdOf(feedUrl),
    itunesId = itunesId,
    title = title.ifBlank { fallbackTitle.orEmpty() },
    author = author,
    feedUrl = feedUrl,
    artworkUrl = fallbackArtworkUrl ?: artworkUrl,
    description = description,
    addedAt = now.toEpochMilli(),
    lastRefreshAt = now.toEpochMilli(),
    etag = null,
    lastModified = null,
    autoRefresh = true,
    source = source,
)

/**
 * Maps a parsed feed item to a database row.
 *
 * Rows are always produced with `isNew = true`; `EpisodeDao.upsertFromFeed` only honours that flag
 * for episodes that did not already exist, so an unchanged episode is never re-flagged.
 *
 * Deliberately source-agnostic. A YouTube entry arrives here already carrying a
 * [md.borisveriga.bpodcat.core.model.youTubeAudioSentinel] in `audioUrl`, which is the whole point
 * of both parsers emitting the same [FeedItem]: nothing below this line has to know the
 * difference.
 *
 * @param podcastId the owning podcast's id.
 */
fun FeedItem.asEpisodeEntity(podcastId: String): EpisodeEntity = EpisodeEntity(
    id = episodeIdOf(podcastId, guid),
    podcastId = podcastId,
    guid = guid,
    title = title,
    description = description,
    audioUrl = audioUrl,
    artworkUrl = artworkUrl,
    durationMs = durationMs,
    publishedAt = publishedAt?.toEpochMilli(),
    sizeBytes = audioLengthBytes,
    isNew = true,
)
