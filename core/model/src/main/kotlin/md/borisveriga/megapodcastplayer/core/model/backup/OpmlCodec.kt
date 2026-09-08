package md.borisveriga.megapodcastplayer.core.model.backup

import java.io.StringReader
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.parsers.ParserConfigurationException
import md.borisveriga.megapodcastplayer.core.model.xml.untrustedXmlParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

/**
 * One subscription in an OPML document.
 *
 * Two fields, because two is all OPML reliably carries. Everything else this app keeps about a show
 * — its position in the library, its speed, what has been played — is not in the file and is not
 * meant to be: see [OpmlCodec] for what OPML is *for*.
 *
 * @property feedUrl the `xmlUrl` attribute, trimmed; the show's identity in this app as well.
 * @property title the `text` or `title` attribute, or the feed URL when the file names neither.
 */
data class OpmlFeed(
    val feedUrl: String,
    val title: String,
)

/** What [OpmlCodec.decode] made of a document the user picked. */
sealed interface OpmlDecodeResult {

    /**
     * A readable OPML document with at least one usable subscription in it.
     *
     * @property feeds the subscriptions, in document order, de-duplicated by feed URL.
     * @property skipped how many `<outline>` elements were dropped for naming no usable feed —
     *   folders, malformed rows, and anything whose `xmlUrl` was not an `http(s)` URL. Reported
     *   rather than hidden, because an import that silently loses nine of twenty shows is worse
     *   than one that says so.
     */
    data class Decoded(val feeds: List<OpmlFeed>, val skipped: Int) : OpmlDecodeResult

    /** The file is not XML, or is XML that is not OPML. */
    data object NotOpml : OpmlDecodeResult

    /** Readable OPML, but with no subscription in it — an empty export, or a file of folders. */
    data object NoFeeds : OpmlDecodeResult
}

/**
 * Reads and writes OPML subscription lists.
 *
 * OPML is how a library moves between podcast apps, and it is the *only* format anything else here
 * can read. `BackupCodec`'s JSON is richer and completely private to this app: it carries
 * positions, played flags, the queue, downloads and moments, none of which OPML has a place for.
 * The two are not alternatives. The backup is how this app survives a wiped database; OPML is how
 * twenty subscriptions arrive from another app, or leave for one.
 *
 * So an export deliberately loses almost everything — and the row in the plan says so. What it
 * keeps is what another app can act on: a feed URL and a name.
 *
 * **The file is untrusted input.** It was written by another program and picked out of a document
 * provider, so: external entities are off (see
 * [untrustedXmlParserFactory][md.borisveriga.megapodcastplayer.core.model.xml.untrustedXmlParserFactory]),
 * only `http`/`https` feed URLs are accepted — nothing that would send the fetcher at a `file://`
 * path or an internal host — and the number of subscriptions taken from one document is capped, so
 * a hostile or broken file cannot ask this app to fetch a hundred thousand feeds.
 */
object OpmlCodec {

    /**
     * Writes a subscription list.
     *
     * Flat: no folders, because this app has no folders, and inventing one to hold everything would
     * be a category the receiving app then has to show. `type="rss"` is written even for a YouTube
     * playlist, because that is what the feed *is* — an Atom document at an `http` URL — and an
     * app that has never heard of this one should be able to subscribe to it.
     *
     * @param podcasts the library, in whatever order the caller wants it read.
     * @param title what to call the document, shown by some apps when importing.
     * @param exportedAtMs when it was written; OPML's `dateCreated`, in RFC 822 as the spec asks.
     * @return the document, ready to be written to the picked file.
     */
    fun encode(podcasts: List<BackupPodcast>, title: String, exportedAtMs: Long): String {
        val outlines = podcasts.joinToString(separator = "\n") { podcast ->
            "        <outline type=\"rss\" text=\"${podcast.title.escaped()}\" " +
                "title=\"${podcast.title.escaped()}\" xmlUrl=\"${podcast.feedUrl.escaped()}\" />"
        }

        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<opml version="2.0">""")
            appendLine("    <head>")
            appendLine("        <title>${title.escaped()}</title>")
            appendLine("        <dateCreated>${rfc822(exportedAtMs)}</dateCreated>")
            appendLine("    </head>")
            appendLine("    <body>")
            if (outlines.isNotEmpty()) appendLine(outlines)
            appendLine("    </body>")
            append("</opml>")
        }
    }

    /**
     * Reads a subscription list.
     *
     * Every `<outline>` in the document is considered, at any depth: other apps group subscriptions
     * into folders, and a folder is an `<outline>` with children and no `xmlUrl`. Flattening them
     * is not a loss, because this app has nowhere to put a folder.
     *
     * @param text the document.
     * @return what could be made of it.
     */
    fun decode(text: String): OpmlDecodeResult {
        val handler = parseOrNull(text)
        return when {
            handler == null || !handler.sawOpml -> OpmlDecodeResult.NotOpml
            handler.feeds.isEmpty() -> OpmlDecodeResult.NoFeeds
            else -> OpmlDecodeResult.Decoded(feeds = handler.feeds, skipped = handler.skipped)
        }
    }

    /**
     * Runs the parser, or gives up.
     *
     * Null for both failures rather than two results, because they are the same answer to the user:
     * a file that is not XML and a file whose XML the parser will not build both mean "that is not
     * a subscription list". The distinction only matters to a bug report, and there is no path here
     * that could act on it.
     *
     * @param text the document.
     * @return the filled handler, or null if the document could not be parsed at all.
     */
    private fun parseOrNull(text: String): OutlineHandler? = try {
        OutlineHandler().also { handler ->
            untrustedXmlParserFactory()
                .newSAXParser()
                .parse(InputSource(StringReader(text)), handler)
        }
    } catch (_: SAXException) {
        null
    } catch (_: ParserConfigurationException) {
        null
    }

    /**
     * How many subscriptions one document may contribute.
     *
     * Far past any real library — the largest OPML anyone exports by hand is a few hundred — and
     * low enough that a file which is hostile, generated or simply wrong cannot turn one tap into
     * a hundred thousand feed fetches.
     */
    const val MAX_FEEDS = 1_000
}

/**
 * Collects `<outline>` elements with a usable feed.
 *
 * SAX rather than a DOM for the same reason `RssParser` uses it: this is somebody else's file, it
 * can be any size, and the failure mode has to be "skip this row" rather than "lose the import".
 */
private class OutlineHandler : DefaultHandler() {

    /** Whether an `<opml>` element was seen; without one this is some other XML entirely. */
    var sawOpml = false
        private set

    /** How many outlines named no usable feed. */
    var skipped = 0
        private set

    private val collected = mutableListOf<OpmlFeed>()

    /** Feed URLs already taken, so a subscription filed under two folders is imported once. */
    private val seen = mutableSetOf<String>()

    /** What was found, in document order. */
    val feeds: List<OpmlFeed> get() = collected

    override fun startElement(
        uri: String?,
        localName: String?,
        qName: String?,
        attributes: Attributes,
    ) {
        when (nameOf(localName, qName)) {
            "opml" -> sawOpml = true
            "outline" -> readOutline(attributes)
        }
    }

    /**
     * Takes one outline, or counts it as skipped.
     *
     * @param attributes the outline's attributes.
     */
    private fun readOutline(attributes: Attributes) {
        if (collected.size >= OpmlCodec.MAX_FEEDS) return

        val feedUrl = attributes.value("xmlUrl")?.trim().orEmpty()
        if (!isImportableFeedUrl(feedUrl)) {
            // A folder is an outline with children and no xmlUrl, and is the ordinary case rather
            // than a fault — but so is a row this app would refuse to fetch, and the caller is told
            // the same number either way because the user's question is "did everything arrive".
            skipped++
            return
        }
        if (!seen.add(feedUrl)) return

        val title = attributes.value("text")?.trim()?.takeIf { it.isNotEmpty() }
            ?: attributes.value("title")?.trim()?.takeIf { it.isNotEmpty() }
            ?: feedUrl
        collected += OpmlFeed(feedUrl = feedUrl, title = title)
    }

    /** An attribute by local name, whichever namespace prefix the writer happened to use. */
    private fun Attributes.value(name: String): String? =
        getValue(name) ?: (0 until length).firstOrNull { getLocalName(it) == name }?.let(::getValue)

    /** The element's local name, falling back to the qualified one for non-namespace-aware input. */
    private fun nameOf(localName: String?, qName: String?): String =
        localName?.takeIf { it.isNotEmpty() } ?: qName?.substringAfterLast(':').orEmpty()
}

/**
 * Whether a feed URL out of an OPML file may be fetched.
 *
 * `http` and `https` only, and the reason is not tidiness. This URL is about to be handed to the
 * app's own fetcher, so a `file:///` path would make an import read the device's storage and a
 * `content://` URI would make it read another app's — both on the say-so of a document the user
 * merely picked. The same rule `isPlayableMediaUrl` applies to audio, applied where feeds enter.
 *
 * @param url the outline's `xmlUrl`, already trimmed.
 * @return true when it is safe to subscribe to.
 */
private fun isImportableFeedUrl(url: String): Boolean {
    if (url.isEmpty()) return false
    val lowercase = url.lowercase()
    return lowercase.startsWith("http://") || lowercase.startsWith("https://")
}

/** XML attribute escaping; the five characters that cannot appear raw in a double-quoted value. */
private fun String.escaped(): String = buildString(length) {
    this@escaped.forEach { character ->
        when (character) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(character)
        }
    }
}

/**
 * The OPML spec's date format, which is RFC 822 and not ISO 8601.
 *
 * Written in UTC rather than the exporting phone's zone: a subscription list is not an appointment,
 * and a fixed zone is one fewer thing for a reader to get wrong.
 *
 * @param epochMs when the document was written.
 * @return the formatted date.
 */
private fun rfc822(epochMs: Long): String =
    RFC_822.format(Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC))

/** RFC 822, with the English day and month names the format requires. */
private val RFC_822: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
