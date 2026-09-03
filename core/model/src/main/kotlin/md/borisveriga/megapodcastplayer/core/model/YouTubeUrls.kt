package md.borisveriga.megapodcastplayer.core.model

/**
 * The one place that knows the shapes of YouTube's URLs.
 *
 * Three separate invariants depend on these strings agreeing with each other, so they are built in
 * exactly one place rather than interpolated at each call site:
 *
 *  - [youTubePlaylistFeedUrl] is stored as [Podcast.feedUrl], which means it feeds [podcastIdOf] and
 *    the unique index on `podcasts.feed_url`. Two spellings of the same playlist would become two
 *    shows.
 *  - [youTubeAudioSentinel] is stored as `episodes.audio_url` *and* doubles as the Media3 cache key
 *    for both streaming and downloads. If the minting and the parsing ever disagreed, a downloaded
 *    episode would silently miss its own cache entry.
 *  - [youTubeThumbnailUrl] is the only artwork a playlist feed offers.
 */

/**
 * Scheme and path prefix of the audio sentinel stored in `episodes.audio_url`.
 *
 * The video id is a *path segment* rather than the authority on purpose: as an authority it would be
 * a host, and every URI implementation lowercases hosts — while YouTube video ids are case-sensitive
 * (`niTJ2221aS8`).
 */
private const val SENTINEL_PREFIX = "youtube://video/"

/**
 * YouTube's per-playlist Atom feed.
 *
 * **No longer fetched.** That endpoint returns the first fifteen entries of a playlist and offers no
 * pagination at all, so it can neither import a longer playlist nor ever notice a video added past
 * position fifteen. Episodes come from the extractor instead — see
 * `md.borisveriga.megapodcastplayer.core.youtube.YouTubePlaylistFetcher`.
 *
 * The string survives because it is an *identity*, not an address: it is stored in
 * `podcasts.feed_url`, which [podcastIdOf] hashes and the unique index compares. Changing its
 * spelling would give every stored YouTube show a new id and orphan its episodes.
 */
private const val PLAYLIST_FEED_PREFIX = "https://www.youtube.com/feeds/videos.xml?playlist_id="

/** The playlist's own page, which is the URL the extractor accepts. */
private const val PLAYLIST_PAGE_PREFIX = "https://www.youtube.com/playlist?list="

/**
 * The alphabet a YouTube video id is drawn from.
 *
 * Video ids are base64url: eleven characters of `A-Za-z0-9_-`. The *character set* is the part that
 * matters for safety — the id arrives from a remote Atom feed and is concatenated into a URI, used
 * as a Media3 cache key and handed to the extractor, so a `/`, a `?`, a `.` or a space in it would
 * change the meaning of every one of those. The *length* is left loose deliberately: eleven has held
 * for two decades but is not a documented guarantee, and rejecting a longer id would silently drop
 * every video in a playlist rather than fail visibly.
 */
private const val MAX_VIDEO_ID_LENGTH = 64

/**
 * Whether [videoId] is a well-formed YouTube video id.
 *
 * Applied where an id first arrives from a feed ([md.borisveriga.megapodcastplayer.core.network] parses
 * `yt:videoId` out of untrusted XML) rather than at every use, so that a malformed id never becomes
 * a sentinel, a cache key or an extractor argument. See [MAX_VIDEO_ID_LENGTH] for why this checks
 * the alphabet strictly and the length loosely.
 *
 * @param videoId the candidate id, exactly as the feed supplied it.
 */
fun isYouTubeVideoId(videoId: String): Boolean =
    videoId.isNotEmpty() &&
        videoId.length <= MAX_VIDEO_ID_LENGTH &&
        videoId.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }

/**
 * Builds the durable stand-in for a video's audio.
 *
 * A real `googlevideo.com` URL is never stored: it expires within hours and is bound to the IP that
 * requested it. The sentinel is stable forever, which is what lets it serve as the cache key while
 * the URL it eventually resolves to changes on every playback.
 *
 * @param videoId YouTube's 11-character video id, case preserved.
 * @return the sentinel URI to store in `episodes.audio_url`.
 */
fun youTubeAudioSentinel(videoId: String): String = SENTINEL_PREFIX + videoId

/**
 * Extracts the video id from a sentinel.
 *
 * Implemented with plain string operations rather than a URI parser: this runs on every data source
 * open, including for ordinary podcast MP3s, and must neither allocate a parser nor case-fold.
 *
 * @param uri any audio URI, sentinel or not.
 * @return the video id, or `null` when [uri] is an ordinary URL.
 */
fun youTubeVideoIdOrNull(uri: String): String? =
    if (uri.startsWith(SENTINEL_PREFIX)) {
        uri.substring(SENTINEL_PREFIX.length).takeIf { it.isNotEmpty() }
    } else {
        null
    }

/**
 * The canonical Atom feed URL for a playlist.
 *
 * This is what gets stored as [Podcast.feedUrl], so it is also what [podcastIdOf] hashes and what
 * duplicate detection compares. Every accepted spelling of a playlist link must reduce to the same
 * `playlistId` before reaching here.
 *
 * @param playlistId the canonical playlist id, e.g. `PLBQmLCA6V3Nc_Z_LpUguOnbjrgt9LqlG0`.
 */
fun youTubePlaylistFeedUrl(playlistId: String): String = PLAYLIST_FEED_PREFIX + playlistId

/**
 * Recovers the playlist id from a stored [youTubePlaylistFeedUrl].
 *
 * The show's identity is the feed URL, but the extractor wants a playlist id, so refreshing has to
 * undo the minting above. Kept next to it deliberately: these two must stay exact inverses, and the
 * only way to guarantee that is to let one file own both.
 *
 * @param feedUrl the value stored in `podcasts.feed_url`.
 * @return the playlist id, or `null` when [feedUrl] is not a YouTube playlist feed URL.
 */
fun youTubePlaylistIdOrNull(feedUrl: String): String? =
    if (feedUrl.startsWith(PLAYLIST_FEED_PREFIX)) {
        feedUrl.substring(PLAYLIST_FEED_PREFIX.length).takeIf { it.isNotEmpty() }
    } else {
        null
    }

/**
 * The playlist page URL for [playlistId], which is what the extractor is given.
 *
 * Deliberately not the same string as [youTubePlaylistFeedUrl]: that one is the show's stored
 * identity and must never move, this one is an address that only ever exists in flight.
 *
 * @param playlistId the canonical playlist id.
 */
fun youTubePlaylistUrl(playlistId: String): String = PLAYLIST_PAGE_PREFIX + playlistId

/**
 * Pulls the video id out of a watch URL.
 *
 * The extractor identifies a playlist entry by its watch URL (`…/watch?v=niTJ2221aS8`, sometimes
 * carrying `&list=` and `&index=` as well), while everything downstream is keyed by the bare id. The
 * result is put through [isYouTubeVideoId] before it is returned, for the same reason the Atom
 * parser did it: the id goes on to become a sentinel URI, a Media3 cache key and an extractor
 * argument, and exactly one shape of id may exist below this line.
 *
 * @param watchUrl a YouTube watch URL, as the extractor reported it.
 * @return the video id, or `null` when the URL carries no well-formed one.
 */
fun youTubeVideoIdFromWatchUrlOrNull(watchUrl: String): String? {
    val query = watchUrl.substringAfter('?', missingDelimiterValue = "")
    if (query.isEmpty()) return null
    return query.splitToSequence('&')
        .firstOrNull { it.startsWith(VIDEO_ID_PARAM) }
        ?.substring(VIDEO_ID_PARAM.length)
        ?.takeIf(::isYouTubeVideoId)
}

/** The query parameter a watch URL carries its video id in. */
private const val VIDEO_ID_PARAM = "v="

/**
 * Thumbnail URL for a video.
 *
 * `mqdefault.jpg` (320x180) rather than the `hqdefault.jpg` the feed itself publishes: `hqdefault`
 * is 480x360, which is 4:3 with the 16:9 frame letterboxed inside it, so a square centre-crop keeps
 * the black bars. `maxresdefault.jpg` and `hq720.jpg` 404 on older uploads, and the artwork
 * composable falls back to a glyph rather than to another URL. `mqdefault.jpg` always exists and is
 * exactly 16:9, which is what lets the existing square artwork component render it unchanged.
 *
 * @param videoId YouTube's video id.
 */
fun youTubeThumbnailUrl(videoId: String): String =
    "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
