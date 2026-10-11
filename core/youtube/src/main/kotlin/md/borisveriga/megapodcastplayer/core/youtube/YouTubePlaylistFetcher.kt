package md.borisveriga.megapodcastplayer.core.youtube

import java.io.IOException
import md.borisveriga.megapodcastplayer.core.network.rss.FeedChannel

/**
 * Reads a YouTube playlist as a show.
 *
 * This exists because YouTube's per-playlist Atom feed — which is what the app used to read — is
 * capped at fifteen entries and has no pagination whatsoever. `max-results` and `start-index` are
 * accepted and ignored; there is no continuation token. Worse than the obvious symptom of a long
 * playlist importing short: those fifteen are the first fifteen *in playlist order*, not the fifteen
 * newest, so for any playlist that grows at the end the feed can never report a new video at all and
 * refreshing it is permanently a no-op.
 *
 * The extractor has no such limit, and this module already depends on it to resolve audio, so the
 * playlist is walked page by page instead. The same caveat as
 * [YouTubeAudioResolver] applies: this reads YouTube's own responses, which their terms of service
 * do not permit, and it will break whenever they change the shape of those responses.
 *
 * Which is why there are two implementations, and the user picks one in settings
 * ([md.borisveriga.megapodcastplayer.core.model.YouTubeSource]). [NewPipePlaylistFetcher] is the
 * extractor, with everything above. [OfficialFeedPlaylistFetcher] is the Atom feed again, read as
 * YouTube means it to be and accepted for what it is: the newest few videos, and nothing against the
 * terms. Both are bound under a qualifier — `di/PlaylistFetcherQualifiers.kt` — so that the choice
 * is made where the preference is, in the repository, on every fetch.
 */
interface YouTubePlaylistFetcher {

    /**
     * Reads a playlist.
     *
     * The result is shaped as a [FeedChannel] so that everything below it — the entity mappers,
     * `upsertFromFeed`, duplicate detection, the player, the download stack — cannot tell a playlist
     * from a podcast, exactly as the Atom parser did. Items are in playlist order, which is the
     * order the Atom feed uses too, so the two implementations agree on arrangement and differ only
     * in how far down the playlist they reach.
     *
     * A page that fails mid-walk fails the whole call rather than returning what it has. A truncated
     * result is indistinguishable, to every caller, from a playlist that really is that short — the
     * official feed's fixed cut is the one exception, and it is a cut the caller chose.
     *
     * @param playlistId the canonical playlist id, e.g. `PLAA9qRhhXQ2c`.
     * @return the playlist, shaped as a podcast channel, in playlist order.
     * @throws YouTubePlaylistUnavailableException when the playlist cannot be read at all.
     * @throws IOException on network failure.
     */
    suspend fun fetch(playlistId: String): FeedChannel
}

/**
 * A playlist exists as a link but cannot be read: private, deleted, or served by a YouTube this
 * extractor no longer understands.
 *
 * Extends [IOException] to match [YouTubeAudioUnavailableException], and because the repository
 * already routes an [IOException] from a feed fetch into "couldn't add this show".
 *
 * @param playlistId the playlist that could not be read.
 * @param reason a short phrase for the user, completing "…because …".
 * @param cause the extractor failure this was translated from, if any.
 */
class YouTubePlaylistUnavailableException(
    val playlistId: String,
    val reason: String,
    cause: Throwable? = null,
) : IOException("YouTube playlist unavailable for $playlistId: $reason", cause)
