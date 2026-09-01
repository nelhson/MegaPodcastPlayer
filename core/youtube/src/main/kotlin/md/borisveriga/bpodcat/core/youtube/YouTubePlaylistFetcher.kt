package md.borisveriga.bpodcat.core.youtube

import java.io.IOException
import md.borisveriga.bpodcat.core.network.rss.FeedChannel

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
 */
interface YouTubePlaylistFetcher {

    /**
     * Reads every video in a playlist.
     *
     * The result is shaped as a [FeedChannel] so that everything below it — the entity mappers,
     * `upsertFromFeed`, duplicate detection, the player, the download stack — cannot tell a playlist
     * from a podcast, exactly as the Atom parser it replaces did. Items are in playlist order, which
     * is the order the Atom feed used too, so a show imported before this change keeps its
     * arrangement and merely gains the entries that were missing.
     *
     * A page that fails mid-walk fails the whole call rather than returning what it has. Returning
     * a partial playlist is precisely the bug this replaced, and a truncated result is indis-
     * tinguishable, to every caller, from a playlist that really is that short.
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
