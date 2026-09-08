package md.borisveriga.megapodcastplayer

import android.content.Intent
import androidx.core.net.toUri
import md.borisveriga.megapodcastplayer.core.model.PodcastLinkParser

/**
 * Reads a podcast link out of an intent someone else sent us.
 *
 * Two doors lead here, and both were shut before: a link *shared* from a browser or a chat
 * (`ACTION_SEND` with `text/plain`), and a link *tapped* on one of the hosts the manifest claims
 * (`ACTION_VIEW`). Adding a show used to mean copying the link, leaving the app you were in,
 * opening this one, tapping add and pasting.
 *
 * Everything reaching this function is untrusted, so it is treated the way the paste field's text
 * is: classified by [PodcastLinkParser], and rejected outright if it is not one of the shapes the
 * app can resolve. Nothing is added here — the link is carried to the Search screen with the field
 * filled in, where adding it is still a deliberate tap.
 *
 * A shared message is often a sentence with a link in it ("listen to this: https://…"), so shared
 * text is scanned word by word rather than being handed over whole. The first word that classifies
 * wins; a message with two links is a message whose sender meant the first one.
 *
 * @return the link, exactly as written, or null when the intent carries nothing usable.
 */
fun Intent.podcastLinkOrNull(): String? = when (action) {
    Intent.ACTION_SEND -> getStringExtra(Intent.EXTRA_TEXT)?.firstPodcastLink()

    // The URI, not its text: a VIEW intent's data is the link, and the manifest has already
    // narrowed the hosts to the ones the parser can resolve. `podcast://` and `feed://` are
    // rewritten to https, which is what those schemes have always meant.
    Intent.ACTION_VIEW -> data?.toString()?.asFeedScheme()?.takeIf(::classifies)

    else -> null
}

/**
 * The first whitespace-separated word of this text that is a podcast link.
 *
 * @return the word, or null when none of them classifies.
 */
private fun String.firstPodcastLink(): String? =
    split(WHITESPACE).map(String::trim).firstOrNull(::classifies)

/**
 * Rewrites the two conventional feed schemes to the transport they actually mean.
 *
 * `podcast://example.com/rss` and `feed://example.com/rss` are how a directory page offers a feed
 * to whatever app will take it; neither is a scheme anything can fetch. The convention is that the
 * rest of the URL is an `https` one.
 *
 * @return the URL to classify.
 */
private fun String.asFeedScheme(): String {
    val scheme = toUri().scheme?.lowercase() ?: return this
    return if (scheme in FEED_SCHEMES) HTTPS_PREFIX + substringAfter("://") else this
}

/** True when [PodcastLinkParser] recognises [candidate] as something the app can add. */
private fun classifies(candidate: String): Boolean =
    candidate.isNotBlank() && PodcastLinkParser.parse(candidate) != null

private val WHITESPACE = Regex("""\s+""")

private val FEED_SCHEMES = setOf("podcast", "feed")

private const val HTTPS_PREFIX = "https://"
