package md.borisveriga.megapodcastplayer.core.wearprotocol

/**
 * The Wearable Data Layer addresses MegaPodcastPlayer uses to talk between the phone and the watch.
 *
 * Both apps are built from this one file, so a path can never drift on one side only. Everything is
 * namespaced under [PREFIX] because the Data Layer is shared with any other app signed with the
 * same key, and because the phone's listener service filters incoming messages by path prefix.
 */
object WearPaths {

    /** Namespace for every MegaPodcastPlayer path; also the manifest's `pathPrefix` filter. */
    const val PREFIX = "/megapodcastplayer"

    /**
     * Phone -> watch: the current playback state, published as a **data item**.
     *
     * A data item rather than a message because the Data Layer keeps the last value and replays it
     * to the watch on connect, so opening the watch app renders the right thing before the phone
     * has said anything.
     */
    const val NOW_PLAYING = "$PREFIX/now_playing"

    /**
     * Watch -> phone: a single [WearCommand], sent as a **message**.
     *
     * A message rather than a data item because commands are events, not state: pressing pause
     * twice must arrive twice, and a data item holding the same bytes would be de-duplicated.
     */
    const val COMMAND = "$PREFIX/command"

    /**
     * Phone -> watch: the episodes the phone holds offline, published as a **data item**.
     *
     * Separate from [NOW_PLAYING] because the two change on completely different clocks — playback
     * state several times a minute, the download list a few times a week — and a watch that had to
     * re-read one to learn the other would spend its Bluetooth budget on the wrong one.
     */
    const val OFFLINE_LIBRARY = "$PREFIX/offline_library"

    /**
     * Phone -> watch: one episode's audio, over a **channel**.
     *
     * Neither a message nor a data item: an episode is tens of megabytes, a message is capped at
     * 100 KB, and a data item is replicated to *every* connected node whether it wants it or not. A
     * channel is a plain socket to one node, opened for the transfer and closed after it, which is
     * exactly what a file wants.
     *
     * The episode's id is the last path segment; see [episodeAudioPath].
     */
    const val EPISODE_AUDIO = "$PREFIX/episode_audio"

    /** The `DataMap` key both sides use for the serialised payload. */
    const val PAYLOAD_KEY = "payload"

    /**
     * Capability the phone app advertises, declared in its `res/values/wear.xml`.
     *
     * The watch looks this up to tell "the phone is not reachable" apart from "the phone is
     * reachable but MegaPodcastPlayer is not installed on it" — two very different things to show the user.
     */
    const val PHONE_CAPABILITY = "megapodcastplayer_phone_player"

    /**
     * The channel path carrying one episode's audio.
     *
     * The id travels in the path rather than in a preamble on the stream, so the receiving side
     * knows what it is being sent before the first byte arrives — and so a transfer that dies
     * halfway can be attributed to an episode rather than discarded as unidentifiable.
     *
     * @param episodeId the episode; ids are hex digests, so nothing needs escaping.
     */
    fun episodeAudioPath(episodeId: String): String = "$EPISODE_AUDIO/$episodeId"

    /**
     * Reads the episode id back out of a channel path.
     *
     * @param path the path the channel was opened on.
     * @return the id, or null when the path is not one of ours — which is normal, since a
     *   `WearableListenerService` is offered every channel the phone opens.
     */
    fun episodeIdFromAudioPath(path: String): String? =
        path.removePrefix("$EPISODE_AUDIO/").takeIf { it.isNotEmpty() && it != path }
}
