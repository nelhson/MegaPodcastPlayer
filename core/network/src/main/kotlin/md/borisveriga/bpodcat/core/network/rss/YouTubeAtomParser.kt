package md.borisveriga.bpodcat.core.network.rss

import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import javax.xml.parsers.SAXParserFactory
import md.borisveriga.bpodcat.core.model.isYouTubeVideoId
import md.borisveriga.bpodcat.core.model.youTubeAudioSentinel
import md.borisveriga.bpodcat.core.model.youTubeThumbnailUrl
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

/**
 * Streaming parser for YouTube's per-playlist Atom feed.
 *
 * A deliberate sibling of [RssParser] rather than an extension of it. The two formats disagree at
 * the root — `<rss><channel><item>` against `<feed><entry>` — and every convenience [RssParser] has
 * built up (requiring a `<channel>`, dropping items with no `<enclosure>`) is RSS-shaped. Sharing
 * one handler would mean two sets of rules guarding each other; sharing the *output* types costs
 * nothing and buys everything.
 *
 * That output is the point: this emits the same [FeedChannel] and [FeedItem] as [RssParser], so the
 * entire pipeline below the parser — the entity mappers, `upsertFromFeed`, duplicate detection, the
 * player, the download stack — cannot tell a YouTube playlist from a podcast and needs no change.
 *
 * Handled namespaces:
 *  - Atom: `http://www.w3.org/2005/Atom` (the default namespace in this feed)
 *  - YouTube: `http://www.youtube.com/xml/schemas/2015` (`yt:videoId`, `yt:playlistId`)
 *  - Media RSS: `http://search.yahoo.com/mrss/` (`media:group`, `media:title`, `media:description`)
 */
@Singleton
class YouTubeAtomParser @Inject constructor() {

    /**
     * Parses a playlist feed.
     *
     * @param input the feed body. Closed by the caller, not by this method.
     * @return the parsed playlist, shaped as a podcast channel.
     * @throws RssParseException when the document is not parseable XML or is not an Atom feed —
     *   which is what a private playlist, a deleted playlist, or an HTML error page served with a
     *   200 looks like from here.
     */
    fun parse(input: InputStream): FeedChannel {
        val handler = PlaylistHandler()
        try {
            val factory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = true
                // Same reasoning as RssParser: this body comes from a remote host and must never be
                // allowed to resolve external entities.
                disableIfSupported("http://xml.org/sax/features/external-general-entities")
                disableIfSupported("http://xml.org/sax/features/external-parameter-entities")
            }
            factory.newSAXParser().parse(InputSource(input), handler)
        } catch (e: SAXException) {
            throw RssParseException("Not a readable YouTube playlist feed: ${e.message}", e)
        } catch (e: javax.xml.parsers.ParserConfigurationException) {
            throw RssParseException("No usable XML parser available: ${e.message}", e)
        }

        if (!handler.sawFeed) {
            throw RssParseException("Document contains no <feed> element")
        }
        return handler.toChannel()
    }
}

/** SAX namespace URI for Atom. */
private const val NS_ATOM = "http://www.w3.org/2005/Atom"

/** SAX namespace URI for YouTube's own Atom extensions. */
private const val NS_YOUTUBE = "http://www.youtube.com/xml/schemas/2015"

/** SAX namespace URI for Media RSS. */
private const val NS_MEDIA = "http://search.yahoo.com/mrss/"

/**
 * Accumulates playlist and entry state while SAX walks the document.
 *
 * Text is buffered per element because SAX may deliver one text node across several [characters]
 * callbacks — a certainty here, since `media:description` carries the whole video description,
 * frequently several kilobytes of Cyrillic with timestamps and links.
 */
private class PlaylistHandler : DefaultHandler() {

    var sawFeed: Boolean = false
        private set

    private val text = StringBuilder()

    /**
     * Whether the walk is currently inside an `<entry>`.
     *
     * Load-bearing for more than dispatch: `<title>`, `<id>`, `<published>` and `<author><name>` all
     * appear at *both* feed and entry level. Without this flag the last video's uploader would end
     * up as the playlist's author.
     */
    private var inEntry = false

    /** Whether the walk is inside an `<author>`, whose `<name>` is the only child we read. */
    private var inAuthor = false

    private var feedTitle: String? = null
    private var feedAuthor: String? = null

    private val items = mutableListOf<FeedItem>()

    /**
     * The first entry's video id.
     *
     * The playlist feed carries no image of its own — not at feed level, not anywhere — so the
     * newest video's thumbnail is the only artwork available for the show.
     */
    private var firstVideoId: String? = null

    private var entryVideoId: String? = null
    private var entryId: String? = null
    private var entryTitle: String? = null
    private var entryMediaTitle: String? = null
    private var entryDescription: String? = null
    private var entryPublished: String? = null

    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
        text.setLength(0)
        val name = localName?.takeIf { it.isNotEmpty() } ?: qName?.substringAfter(':').orEmpty()

        when {
            name == "feed" -> sawFeed = true

            name == "entry" -> {
                inEntry = true
                resetEntry()
            }

            name == "author" -> inAuthor = true
        }
        // media:content is a Flash player URL, not audio, and media:thumbnail is the letterboxed
        // 4:3 hqdefault. Both are deliberately ignored; the thumbnail is derived from the video id
        // instead, which yields a true 16:9 frame. See youTubeThumbnailUrl.
    }

    override fun characters(ch: CharArray?, start: Int, length: Int) {
        if (ch != null) text.appendRange(ch, start, start + length)
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        val name = localName?.takeIf { it.isNotEmpty() } ?: qName?.substringAfter(':').orEmpty()
        val value = text.toString().trim()
        text.setLength(0)

        if (name == "author") {
            inAuthor = false
            return
        }

        if (inEntry) {
            when {
                name == "videoId" && uri == NS_YOUTUBE -> entryVideoId = value.ifEmpty { null }
                name == "id" && uri == NS_ATOM -> entryId = value.ifEmpty { null }
                name == "title" && uri == NS_MEDIA -> entryMediaTitle = value.ifEmpty { null }
                name == "title" && uri == NS_ATOM -> entryTitle = value.ifEmpty { null }
                name == "description" && uri == NS_MEDIA -> entryDescription = value.ifEmpty { null }
                // <updated> also exists but tracks edits, not publication, and would reorder the
                // list every time a publisher fixed a typo.
                name == "published" && uri == NS_ATOM -> entryPublished = value.ifEmpty { null }
                name == "entry" -> {
                    finishEntry()
                    inEntry = false
                }
            }
            return
        }

        when {
            name == "title" && uri == NS_ATOM -> if (feedTitle == null) feedTitle = value.ifEmpty { null }
            name == "name" && inAuthor -> if (feedAuthor == null) feedAuthor = value.ifEmpty { null }
        }
    }

    /**
     * Builds the parsed [FeedChannel] once the document has been walked.
     *
     * `description` is empty because the feed publishes none: a playlist's description is simply not
     * part of this endpoint's output.
     */
    fun toChannel(): FeedChannel = FeedChannel(
        title = feedTitle.orEmpty(),
        author = feedAuthor.orEmpty(),
        description = "",
        artworkUrl = firstVideoId?.let(::youTubeThumbnailUrl),
        items = items.toList(),
    )

    /**
     * Commits the entry currently being read.
     *
     * An entry with no `yt:videoId` — or with one that is not a well-formed id — is dropped
     * silently, the analogue of [RssParser] dropping an item with no audio enclosure. Without a
     * usable video id there is nothing to resolve audio from, so such an entry could never be
     * played.
     */
    private fun finishEntry() {
        // The id is remote, untrusted text that goes on to be concatenated into the audio sentinel,
        // used as the Media3 cache key and handed to the extractor. A malformed one is dropped here
        // rather than sanitised, so that exactly one shape of id exists below this line.
        val videoId = entryVideoId?.takeIf(::isYouTubeVideoId) ?: return
        if (firstVideoId == null) firstVideoId = videoId

        items += FeedItem(
            // Atom's <id> is already YouTube-namespaced ("yt:video:niTJ2221aS8"), which keeps the
            // episodeIdOf hash from ever colliding with an RSS guid. Synthesised identically when
            // the element is missing, so the id is stable either way.
            guid = entryId ?: "yt:video:$videoId",
            title = entryMediaTitle ?: entryTitle.orEmpty(),
            description = entryDescription.orEmpty(),
            // The durable stand-in for audio. A real stream URL expires within hours and is bound
            // to the requesting IP, so it is resolved at playback time and never stored.
            audioUrl = youTubeAudioSentinel(videoId),
            // The feed advertises no byte length. Nothing renders it, and Media3 learns the real
            // length from the response headers when a download starts.
            audioLengthBytes = null,
            artworkUrl = youTubeThumbnailUrl(videoId),
            // The feed publishes no duration either. It fills itself in on first play, via
            // EpisodeDao.fillMissingDuration.
            durationMs = null,
            publishedAt = entryPublished?.let(::parseRfc822Date),
        )
    }

    private fun resetEntry() {
        entryVideoId = null
        entryId = null
        entryTitle = null
        entryMediaTitle = null
        entryDescription = null
        entryPublished = null
    }
}
