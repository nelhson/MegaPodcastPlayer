package md.borisveriga.bpodcat.core.testing

import java.time.Instant
import md.borisveriga.bpodcat.core.model.DownloadState
import md.borisveriga.bpodcat.core.model.Episode
import md.borisveriga.bpodcat.core.model.Podcast
import md.borisveriga.bpodcat.core.model.PodcastSource

/**
 * Fixture builders for the `:core:model` types.
 *
 * Both [Episode] and [Podcast] have a dozen non-defaulted constructor parameters, so building one
 * inline in a test buries the single field the test is actually about. These give every field a
 * plausible default and let a test name only what it cares about.
 *
 * The defaults are deliberately boring and deterministic — no clocks, no randomness — so an
 * assertion on an untouched field stays stable.
 */

/**
 * Fixed point in time used by the fixtures so tests never depend on the wall clock.
 *
 * Not a `const` — [Instant.parse] runs at class-init time — so it follows the camelCase property
 * convention rather than the SCREAMING_CASE constant one.
 */
val testInstant: Instant = Instant.parse("2026-01-01T00:00:00Z")

/**
 * Builds an [Episode] with plausible defaults.
 *
 * @param id the episode id; also seeds [Episode.guid] and [Episode.title] so failures are readable.
 * @return an episode that is unplayed, not downloaded and streamable over HTTPS.
 */
fun testEpisode(
    id: String = "ep-1",
    podcastId: String = "pod-1",
    guid: String = id,
    title: String = "Episode $id",
    description: String = "Notes for $id",
    audioUrl: String = "https://example.com/$id.mp3",
    artworkUrl: String? = null,
    durationMs: Long? = 60_000L,
    publishedAt: Instant? = testInstant,
    sizeBytes: Long? = null,
    positionMs: Long = 0L,
    isPlayed: Boolean = false,
    isNew: Boolean = false,
    downloadState: DownloadState = DownloadState.NOT_DOWNLOADED,
    downloadedBytes: Long = 0L,
    downloadPercent: Float = 0f,
): Episode = Episode(
    id = id,
    podcastId = podcastId,
    guid = guid,
    title = title,
    description = description,
    audioUrl = audioUrl,
    artworkUrl = artworkUrl,
    durationMs = durationMs,
    publishedAt = publishedAt,
    sizeBytes = sizeBytes,
    positionMs = positionMs,
    isPlayed = isPlayed,
    isNew = isNew,
    downloadState = downloadState,
    downloadedBytes = downloadedBytes,
    downloadPercent = downloadPercent,
)

/**
 * Builds a [Podcast] with plausible defaults.
 *
 * @param id the podcast id; also seeds [Podcast.title] and [Podcast.feedUrl].
 * @return an RSS-sourced show that has never been refreshed and carries no HTTP validators.
 */
fun testPodcast(
    id: String = "pod-1",
    itunesId: Long? = null,
    title: String = "Show $id",
    author: String = "Author of $id",
    feedUrl: String = "https://example.com/$id.xml",
    artworkUrl: String? = null,
    description: String = "About $id",
    addedAt: Instant = testInstant,
    lastRefreshAt: Instant? = null,
    etag: String? = null,
    lastModified: String? = null,
    autoRefresh: Boolean = true,
    source: PodcastSource = PodcastSource.RSS,
    isPinned: Boolean = false,
): Podcast = Podcast(
    id = id,
    itunesId = itunesId,
    title = title,
    author = author,
    feedUrl = feedUrl,
    artworkUrl = artworkUrl,
    description = description,
    addedAt = addedAt,
    lastRefreshAt = lastRefreshAt,
    etag = etag,
    lastModified = lastModified,
    autoRefresh = autoRefresh,
    source = source,
    isPinned = isPinned,
)
