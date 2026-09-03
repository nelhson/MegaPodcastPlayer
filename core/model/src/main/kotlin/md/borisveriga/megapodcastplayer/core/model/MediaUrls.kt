package md.borisveriga.megapodcastplayer.core.model

/**
 * The scheme allowlist for every URL that reaches the media stack.
 *
 * Feed content is untrusted input from an arbitrary host, and the URL it supplies does not stop at
 * the parser: `episodes.audio_url` is handed to a Media3 `DefaultDataSource.Factory`, which resolves
 * `file:`, `content:`, `asset:`, `rawresource:` and `rtmp:` in addition to HTTP(S). A hostile feed
 * publishing
 *
 * ```xml
 * <enclosure type="audio/mpeg"
 *     url="file:///data/data/md.borisveriga.megapodcastplayer/databases/megapodcastplayer.db"/>
 * ```
 *
 * would otherwise have the app open — and, on download, *copy into the download cache* — any file
 * readable by the app's own UID. `content://` is worse still: it reaches other apps' exported
 * providers under MegaPodcastPlayer's identity. Artwork URLs are the same shape of problem one severity lower,
 * because Coil resolves `file:` and `content:` too.
 *
 * The rule is therefore an allowlist, not a denylist: a scheme nobody has thought of yet must fail
 * closed.
 *
 * This lives in `:core:model` rather than in the parser because it is enforced twice, in two
 * modules, on purpose — see [isPlayableMediaUrl].
 */

/**
 * Longest URL accepted.
 *
 * Nothing downstream imposes a limit, and the string is stored in the database, published to the
 * watch over the Data Layer (whose message payloads are capped) and rendered in the UI. Real
 * enclosure URLs are a couple of hundred characters; 2048 is the de-facto browser ceiling and is
 * generous by an order of magnitude here.
 */
private const val MAX_MEDIA_URL_LENGTH = 2048

/**
 * Whether [url] may be handed to the player, the downloader or the image loader.
 *
 * Accepts exactly three things:
 *  - `http://…` and `https://…`, the only schemes a podcast enclosure legitimately uses. The scheme
 *    is compared case-insensitively because RFC 3986 defines it that way and publishers do write
 *    `HTTP://`.
 *  - the internal `youtube://video/<id>` sentinel (see [youTubeAudioSentinel]) carrying a
 *    well-formed id ([isYouTubeVideoId]). The sentinel never reaches a data source as a URL: the
 *    YouTube data source intercepts it before Media3 tries to resolve it.
 *
 * A feed *could* publish the sentinel spelling itself, and that is deliberately allowed: the result
 * is an episode that resolves a YouTube video, which the same feed could achieve with an ordinary
 * `https://` link. It grants no access the app does not already offer.
 *
 * Whitespace and control characters are rejected outright. They have no place in a URL, and a
 * newline inside one is the classic way to confuse a downstream parser into seeing two.
 *
 * ## Where this is enforced
 *
 * Twice, and both are load-bearing:
 *  1. In the feed parsers, so a bad enclosure never becomes a row in the database. This is the
 *     primary gate.
 *  2. In `PlayableEpisode.toMediaItem()`, as defence in depth — a database written before this
 *     guard existed may still hold bad rows, and `MIGRATION_2_3` cleans those up with a `DELETE`
 *     whose `LIKE` patterns must stay in step with the rules here.
 *
 * @param url the candidate URL, exactly as the feed supplied it.
 * @return `true` when the URL is safe to resolve.
 */
fun isPlayableMediaUrl(url: String): Boolean {
    if (url.isEmpty() || url.length > MAX_MEDIA_URL_LENGTH) return false
    if (url.any { it.isWhitespace() || it.isISOControl() }) return false

    val videoId = youTubeVideoIdOrNull(url)
    if (videoId != null) return isYouTubeVideoId(videoId)
    return isHttpUrl(url)
}

/** What separates an HTTP scheme from its authority. */
private const val AUTHORITY_SEPARATOR = "//"

/**
 * Whether [url] is `http://` or `https://` followed by at least one character of authority.
 *
 * Written against the raw string rather than a URI parser because `:core:model` is a plain JVM
 * module with no `android.net.Uri`, and because a parser would have to be *more* permissive than
 * this to be useful — the question here is not "what does this URL mean" but "is it one of exactly
 * two shapes".
 */
private fun isHttpUrl(url: String): Boolean {
    val schemeEnd = url.indexOf(':')
    if (schemeEnd < 0) return false

    val scheme = url.substring(0, schemeEnd)
    if (!scheme.equals("http", ignoreCase = true) && !scheme.equals("https", ignoreCase = true)) {
        return false
    }
    // Rejects `https:` and `https://` with nothing after them. An authority-less HTTP URL is not
    // resolvable, and letting one through would only move the failure downstream.
    return url.startsWith(AUTHORITY_SEPARATOR, startIndex = schemeEnd + 1) &&
        url.length > schemeEnd + 1 + AUTHORITY_SEPARATOR.length
}
