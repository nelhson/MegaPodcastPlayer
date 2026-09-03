package md.borisveriga.megapodcastplayer.core.network.rss

import java.time.Instant

/**
 * A parsed RSS channel — the show plus every item the feed currently exposes.
 *
 * @property title `<channel><title>`.
 * @property author `<itunes:author>`, falling back to `<managingEditor>`.
 * @property description `<channel><description>`, HTML stripped.
 * @property artworkUrl `<itunes:image href>`, falling back to `<image><url>`.
 * @property items the feed's episodes, in document order (newest first in practice).
 */
data class FeedChannel(
    val title: String,
    val author: String,
    val description: String,
    val artworkUrl: String?,
    val items: List<FeedItem>,
)

/**
 * A single `<item>` from an RSS feed that carries playable audio.
 *
 * Items without an `<enclosure url>` are dropped during parsing, so [audioUrl] is always present.
 *
 * @property guid `<guid>`, falling back to [audioUrl] when the publisher omits one.
 * @property title `<title>`.
 * @property description `<content:encoded>`, falling back to `<description>`; may contain HTML.
 * @property audioUrl the `<enclosure url>`.
 * @property audioLengthBytes the `<enclosure length>`, when advertised and numeric.
 * @property artworkUrl episode-level `<itunes:image href>`, when present.
 * @property durationMs `<itunes:duration>` normalised to milliseconds, null when absent or unparseable.
 * @property publishedAt `<pubDate>`, null when absent or in a format we cannot read.
 */
data class FeedItem(
    val guid: String,
    val title: String,
    val description: String,
    val audioUrl: String,
    val audioLengthBytes: Long?,
    val artworkUrl: String?,
    val durationMs: Long?,
    val publishedAt: Instant?,
)

/**
 * Thrown when a document is not a usable RSS feed at all (wrong content, truncated download,
 * an HTML error page served with a 200).
 *
 * Individual malformed *items* never throw — they are skipped — because one bad episode must not
 * cost the user the rest of the show.
 *
 * @param message human-readable reason, surfaced in the "couldn't add this feed" UI.
 * @param cause the underlying parser failure, if any.
 */
class RssParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
