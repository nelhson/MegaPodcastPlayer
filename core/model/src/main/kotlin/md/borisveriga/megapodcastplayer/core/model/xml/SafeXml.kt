package md.borisveriga.megapodcastplayer.core.model.xml

import javax.xml.parsers.SAXParserFactory
import org.xml.sax.SAXNotRecognizedException
import org.xml.sax.SAXNotSupportedException

/**
 * How this app reads XML it did not write.
 *
 * Two things here parse XML from outside: the RSS parser in `:core:network`, which reads feeds from
 * arbitrary hosts, and the OPML codec in `backup`, which reads a file the user picked out of
 * another app. Both are untrusted input, both want the same parser hardening, and neither module
 * can see the other — so the hardening lives here, in the one module both already depend on.
 *
 * It moved here from `RssParser`, whose own comment predicted this: *"any second XML source added
 * here will need exactly the same treatment"*.
 */

/**
 * A [SAXParserFactory] that will not resolve external entities.
 *
 * The attack this closes is XXE: a document that declares an entity pointing at a local file or an
 * internal URL, and gets the parser to fetch it and paste it into the result. A podcast feed and an
 * OPML file are both documents someone else wrote, so neither may be allowed to make this app read
 * anything on its behalf.
 *
 * Each feature is turned off *if the implementation has heard of it*. The two SAX implementations
 * this code runs on differ — Xerces on the JVM knows these knobs, Android's parser does not expose
 * them and does not resolve external entities in the first place — and an unrecognised feature must
 * not be the reason a feed fails to load.
 *
 * @return a namespace-aware factory with entity resolution off.
 */
fun untrustedXmlParserFactory(): SAXParserFactory = SAXParserFactory.newInstance().apply {
    isNamespaceAware = true
    disableIfSupported(EXTERNAL_GENERAL_ENTITIES)
    disableIfSupported(EXTERNAL_PARAMETER_ENTITIES)
}

/**
 * Turns a SAX feature off, ignoring implementations that have never heard of it.
 *
 * @param feature the SAX feature URI.
 */
private fun SAXParserFactory.disableIfSupported(feature: String) {
    try {
        setFeature(feature, false)
    } catch (_: SAXNotRecognizedException) {
        // Android's parser does not expose this knob; it does not resolve external entities anyway.
    } catch (_: SAXNotSupportedException) {
        // Recognised but not configurable on this implementation.
    }
}

/** SAX feature naming the entities a document declares in its own DTD. */
private const val EXTERNAL_GENERAL_ENTITIES = "http://xml.org/sax/features/external-general-entities"

/** SAX feature naming the entities a document's DTD pulls in from elsewhere. */
private const val EXTERNAL_PARAMETER_ENTITIES =
    "http://xml.org/sax/features/external-parameter-entities"
