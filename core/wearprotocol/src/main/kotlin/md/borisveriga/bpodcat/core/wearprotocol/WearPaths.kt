package md.borisveriga.bpodcat.core.wearprotocol

/**
 * The Wearable Data Layer addresses BPodcat uses to talk between the phone and the watch.
 *
 * Both apps are built from this one file, so a path can never drift on one side only. Everything is
 * namespaced under [PREFIX] because the Data Layer is shared with any other app signed with the
 * same key, and because the phone's listener service filters incoming messages by path prefix.
 */
object WearPaths {

    /** Namespace for every BPodcat path; also the manifest's `pathPrefix` filter. */
    const val PREFIX = "/bpodcat"

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

    /** The `DataMap` key both sides use for the serialised payload. */
    const val PAYLOAD_KEY = "payload"

    /**
     * The `DataMap` key carrying downscaled cover art, as a Data Layer **asset**.
     *
     * The snapshot also carries an [NowPlayingSnapshot.artworkUrl], but the watch must not fetch it:
     * its only route to the network is a proxy through the phone's Bluetooth link, and a full-size
     * cover is hundreds of kilobytes pulled over a link measured in kilobytes per second. The phone
     * has the image in its Coil disk cache already, so it downscales once and sends the bytes.
     *
     * An asset rather than another byte array in the `DataMap`: a data item is capped at 100 KB and
     * already carries the queue, whereas assets are transferred out of band and cached by content
     * digest, so republishing an unchanged image costs nothing.
     *
     * Optional in both directions. A phone too old to send it leaves the key absent and the watch
     * draws no artwork; a watch too old to read it ignores the key. Neither is an error.
     */
    const val ARTWORK_KEY = "artwork"

    /**
     * Capability the phone app advertises, declared in its `res/values/wear.xml`.
     *
     * The watch looks this up to tell "the phone is not reachable" apart from "the phone is
     * reachable but BPodcat is not installed on it" — two very different things to show the user.
     */
    const val PHONE_CAPABILITY = "bpodcat_phone_player"
}
