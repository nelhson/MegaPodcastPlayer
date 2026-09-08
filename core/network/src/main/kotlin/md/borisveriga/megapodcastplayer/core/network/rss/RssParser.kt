package md.borisveriga.megapodcastplayer.core.network.rss

import java.io.InputStream
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import md.borisveriga.megapodcastplayer.core.model.chapters.Chapter
import md.borisveriga.megapodcastplayer.core.model.chapters.MAX_CHAPTERS
import md.borisveriga.megapodcastplayer.core.model.format.parseTimecodeMs
import md.borisveriga.megapodcastplayer.core.model.isPlayableMediaUrl
import md.borisveriga.megapodcastplayer.core.model.xml.untrustedXmlParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

/**
 * Streaming RSS 2.0 parser with iTunes podcast extensions.
 *
 * Written by hand against SAX rather than pulled from a library because podcast feeds are reliably
 * sloppy and the failure mode has to be "skip this item", never "lose this show". SAX (rather than
 * Android's `XmlPullParser`) keeps the class pure JVM, so it is unit-testable without Robolectric
 * and never loads a multi-megabyte feed into memory.
 *
 * Handled namespaces:
 *  - iTunes: `http://www.itunes.com/dtds/podcast-1.0.dtd` (`author`, `image`, `duration`, `summary`)
 *  - content: `http://purl.org/rss/1.0/modules/content/` (`encoded`)
 *  - podcast: `https://podcastindex.org/namespace/1.0` (`chapters`)
 *  - psc: `http://podlove.org/simple-chapters` (`chapter`)
 *
 * Namespace *prefixes* are ignored — publishers use `itunes:`, `im:` and occasionally none at all —
 * so elements are matched on namespace URI plus local name, with a prefix-insensitive fallback.
 */
@Singleton
class RssParser @Inject constructor() {

    /**
     * Parses a feed.
     *
     * @param input the feed body. Closed by the caller, not by this method.
     * @return the parsed channel.
     * @throws RssParseException when the document is not parseable XML or contains no `<channel>`.
     */
    fun parse(input: InputStream): FeedChannel {
        val handler = FeedHandler()
        try {
            // Feeds are untrusted input from arbitrary hosts: never resolve external entities.
            // The hardening is shared with the OPML codec, which reads a file the user picked out
            // of another app and needs exactly the same treatment.
            untrustedXmlParserFactory().newSAXParser().parse(InputSource(input), handler)
        } catch (e: SAXException) {
            throw RssParseException("Not a readable RSS feed: ${e.message}", e)
        } catch (e: javax.xml.parsers.ParserConfigurationException) {
            throw RssParseException("No usable XML parser available: ${e.message}", e)
        }

        if (!handler.sawChannel) {
            throw RssParseException("Document contains no <channel> element")
        }
        return handler.toChannel()
    }
}

/** SAX namespace URI for the iTunes podcast extensions. */
private const val NS_ITUNES = "http://www.itunes.com/dtds/podcast-1.0.dtd"

/** SAX namespace URI for the `content:encoded` extension. */
private const val NS_CONTENT = "http://purl.org/rss/1.0/modules/content/"

/** SAX namespace URI for the Podcasting 2.0 extensions; only `podcast:chapters` is read. */
private const val NS_PODCAST = "https://podcastindex.org/namespace/1.0"

/** SAX namespace URI for Podlove Simple Chapters, the older inline form. */
private const val NS_PSC = "http://podlove.org/simple-chapters"

/** The only `podcast:chapters` document type this app can read. */
private const val CHAPTERS_JSON_TYPE = "application/json+chapters"

/**
 * Accumulates channel and item state while SAX walks the document.
 *
 * Text is buffered per element because SAX may deliver a single text node in several
 * [characters] callbacks — a real hazard with the long, Cyrillic show notes in this library.
 */
private class FeedHandler : DefaultHandler() {

    var sawChannel: Boolean = false
        private set

    private val text = StringBuilder()
    private var inItem = false
    private var inChannelImage = false

    private var channelTitle: String? = null
    private var channelAuthor: String? = null
    private var channelDescription: String? = null
    private var channelArtwork: String? = null
    private var channelImageUrl: String? = null

    private val items = mutableListOf<FeedItem>()

    private var itemGuid: String? = null
    private var itemTitle: String? = null
    private var itemDescription: String? = null
    private var itemContentEncoded: String? = null
    private var itemAudioUrl: String? = null
    private var itemAudioLength: Long? = null
    private var itemArtwork: String? = null
    private var itemDuration: String? = null
    private var itemPubDate: String? = null
    private var itemChaptersUrl: String? = null
    private var itemChapters = mutableListOf<Chapter>()

    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
        text.setLength(0)
        val name = localName?.takeIf { it.isNotEmpty() } ?: qName?.substringAfter(':').orEmpty()

        when {
            name == "channel" -> sawChannel = true

            name == "item" -> {
                inItem = true
                resetItem()
            }

            name == "image" && uri == NS_ITUNES -> {
                // <itunes:image href="..."/> — the preferred artwork at both channel and item level.
                val href = attributes?.getValue("href")?.takeIf(::isPlayableMediaUrl)
                if (href != null) {
                    if (inItem) itemArtwork = href else channelArtwork = href
                }
            }

            name == "image" && !inItem -> inChannelImage = true

            // Both chapter forms are empty elements, so everything they carry is in the attributes
            // and there is nothing for endElement to do.
            inItem && startChapterElement(uri, name, attributes) -> Unit

            name == "enclosure" && inItem -> {
                val type = attributes?.getValue("type").orEmpty()
                val url = attributes?.getValue("url")
                // Feeds occasionally attach images or PDFs; only audio enclosures are playable, and
                // a missing type is common enough on hand-rolled feeds to be treated as audio.
                val isAudio = type.isEmpty() || type.startsWith("audio")
                // The scheme check is the primary gate keeping `file:`/`content:` URLs out of the
                // database and therefore out of the media stack — see [isPlayableMediaUrl]. An
                // enclosure that fails it leaves itemAudioUrl null, so finishItem drops the whole
                // item, which is the same treatment an item with no enclosure at all gets.
                if (url != null && isAudio && isPlayableMediaUrl(url)) {
                    itemAudioUrl = url
                    itemAudioLength = attributes.getValue("length")?.toLongOrNull()?.takeIf { it > 0L }
                }
            }
        }
    }

    /**
     * Reads the two chapter elements, both of which are empty and attribute-only.
     *
     * Extracted from [startElement] rather than inlined into its `when`: that method is already at
     * the edge of what detekt allows for length and branching, and chapters add two more shapes to
     * it that have nothing to do with the rest.
     *
     * @param uri the element's namespace.
     * @param name the element's local name.
     * @param attributes its attributes.
     * @return true when the element was a chapter element and has been handled.
     */
    private fun startChapterElement(uri: String?, name: String, attributes: Attributes?): Boolean =
        when {
            name == "chapters" && uri == NS_PODCAST -> {
                val type = attributes?.getValue("type").orEmpty()
                val url = attributes?.getValue("url")
                // A document type we cannot parse is worse than none: it would win the priority
                // order against the description chapters that might actually work.
                val readable = type.isEmpty() || type == CHAPTERS_JSON_TYPE
                // This URL is about to be fetched, so it goes through the same gate an enclosure
                // does — a `file:` chapters URL is exactly the shape isPlayableMediaUrl exists for.
                if (url != null && readable && isPlayableMediaUrl(url)) {
                    itemChaptersUrl = url
                }
                true
            }

            name == "chapter" && uri == NS_PSC -> {
                val startMs = attributes?.getValue("start")?.let(::parseTimecodeMs)
                val title = attributes?.getValue("title")?.trim()
                // A chapter that will not parse is dropped and the item survives — the same
                // discipline the parser applies to items within a feed, one level further down.
                if (startMs != null && !title.isNullOrEmpty()) {
                    itemChapters += Chapter(
                        startMs = startMs,
                        title = title,
                        imageUrl = attributes.getValue("image")?.takeIf(::isPlayableMediaUrl),
                        url = attributes.getValue("href")?.takeIf(::isPlayableMediaUrl),
                    )
                }
                true
            }

            else -> false
        }

    override fun characters(ch: CharArray?, start: Int, length: Int) {
        if (ch != null) text.appendRange(ch, start, start + length)
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        val name = localName?.takeIf { it.isNotEmpty() } ?: qName?.substringAfter(':').orEmpty()
        val value = text.toString().trim()
        text.setLength(0)

        if (inItem) {
            when {
                name == "guid" -> itemGuid = value.ifEmpty { null }
                name == "title" -> itemTitle = value.ifEmpty { null }
                name == "description" || (name == "summary" && uri == NS_ITUNES) ->
                    if (itemDescription.isNullOrEmpty()) itemDescription = value
                name == "encoded" && uri == NS_CONTENT -> itemContentEncoded = value.ifEmpty { null }
                name == "duration" && uri == NS_ITUNES -> itemDuration = value.ifEmpty { null }
                name == "pubDate" -> itemPubDate = value.ifEmpty { null }
                name == "item" -> {
                    finishItem()
                    inItem = false
                }
            }
            return
        }

        when {
            name == "title" -> if (channelTitle == null) channelTitle = value.ifEmpty { null }
            name == "description" -> if (channelDescription == null) channelDescription = value.ifEmpty { null }
            name == "author" && uri == NS_ITUNES -> if (channelAuthor == null) channelAuthor = value.ifEmpty { null }
            name == "managingEditor" -> if (channelAuthor == null) channelAuthor = value.ifEmpty { null }
            name == "url" && inChannelImage -> channelImageUrl = value.takeIf(::isPlayableMediaUrl)
            name == "image" -> inChannelImage = false
        }
    }

    /** Builds the parsed [FeedChannel] once the document has been walked. */
    fun toChannel(): FeedChannel = FeedChannel(
        title = channelTitle.orEmpty(),
        author = channelAuthor.orEmpty(),
        description = channelDescription.orEmpty(),
        artworkUrl = channelArtwork ?: channelImageUrl,
        items = items.toList(),
    )

    /**
     * Commits the item currently being read.
     *
     * Items with no audio enclosure are silently dropped: publishers use bare `<item>` entries for
     * announcements, and they are not episodes.
     */
    private fun finishItem() {
        val audioUrl = itemAudioUrl ?: return
        items += FeedItem(
            // A missing <guid> is common on hand-rolled feeds; the enclosure URL is the next most
            // stable identifier available.
            guid = itemGuid ?: audioUrl,
            title = itemTitle.orEmpty(),
            description = itemContentEncoded ?: itemDescription.orEmpty(),
            audioUrl = audioUrl,
            audioLengthBytes = itemAudioLength,
            artworkUrl = itemArtwork,
            durationMs = itemDuration?.let(::parseItunesDurationMs),
            publishedAt = itemPubDate?.let(::parseRfc822Date),
            chaptersUrl = itemChaptersUrl,
            chapters = itemChapters.sortedBy { it.startMs }.take(MAX_CHAPTERS),
        )
    }

    private fun resetItem() {
        itemGuid = null
        itemTitle = null
        itemDescription = null
        itemContentEncoded = null
        itemAudioUrl = null
        itemAudioLength = null
        itemArtwork = null
        itemDuration = null
        itemPubDate = null
        itemChaptersUrl = null
        itemChapters = mutableListOf()
    }
}

/**
 * Normalises an `<itunes:duration>` value to milliseconds.
 *
 * Publishers use `HH:MM:SS`, `MM:SS` and bare seconds interchangeably, and some emit fractional
 * seconds.
 *
 * @param raw the raw element text.
 * @return duration in milliseconds, or `null` when the value is not a duration.
 */
internal fun parseItunesDurationMs(raw: String): Long? {
    val value = raw.trim()
    if (value.isEmpty()) return null

    val parts = value.split(':')
    if (parts.size > 3) return null

    var totalSeconds = 0.0
    for (part in parts) {
        val number = part.trim().toDoubleOrNull() ?: return null
        if (number < 0) return null
        totalSeconds = totalSeconds * 60 + number
    }
    return (totalSeconds * 1000).toLong().takeIf { it > 0L }
}

/**
 * Date formats seen in the wild for `<pubDate>`.
 *
 * RFC-822 is the spec, but publishers drop the day name, drop the seconds, use `UT`/`GMT`/numeric
 * offsets, and pad the day inconsistently.
 */
private val PUB_DATE_FORMATS: List<java.time.format.DateTimeFormatter> = listOf(
    java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME,
    java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME,
    java.time.format.DateTimeFormatter.ISO_INSTANT,
    java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss zzz", java.util.Locale.ENGLISH),
    java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", java.util.Locale.ENGLISH),
    java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm zzz", java.util.Locale.ENGLISH),
    java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss Z", java.util.Locale.ENGLISH),
    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", java.util.Locale.ENGLISH)
        .withZone(java.time.ZoneOffset.UTC),
)

/**
 * Parses an RSS `<pubDate>` leniently.
 *
 * @param raw the raw element text.
 * @return the instant, or `null` if no known format matches — a missing date costs sort order, not
 *   the episode.
 */
internal fun parseRfc822Date(raw: String): Instant? {
    val value = raw.trim()
    if (value.isEmpty()) return null

    for (format in PUB_DATE_FORMATS) {
        try {
            return Instant.from(format.parse(value))
        } catch (_: java.time.format.DateTimeParseException) {
            // Try the next known shape.
        } catch (_: java.time.DateTimeException) {
            // Formatter matched but produced no instant (e.g. no zone); try the next one.
        }
    }
    return null
}
