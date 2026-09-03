package md.borisveriga.megapodcastplayer.core.model

/**
 * Where a show's episode list comes from, and therefore how its audio is obtained.
 *
 * This is the one discriminator the rest of the app branches on: the parser to use for the feed
 * body, whether the audio URL is a real URL or a [youTubeAudioSentinel], and whether the UI shows a
 * source badge. Everything downstream of the parser — episode storage, the queue, the player, the
 * download stack — is deliberately blind to it.
 */
enum class PodcastSource {

    /** A conventional RSS 2.0 podcast feed whose items carry an `<enclosure>` audio URL. */
    RSS,

    /**
     * A public YouTube playlist, read through YouTube's per-playlist Atom feed and played as audio
     * only. Its episodes store a [youTubeAudioSentinel] rather than a playable URL.
     */
    YOUTUBE,
}
