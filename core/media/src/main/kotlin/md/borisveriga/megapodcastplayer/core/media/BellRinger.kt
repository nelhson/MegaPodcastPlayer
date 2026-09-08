package md.borisveriga.megapodcastplayer.core.media

/**
 * Rings the end-of-episode bell wherever the user will hear it.
 *
 * An interface here and an implementation in `:app` for the same reason
 * [PlaybackProgressRecorder] is: the ring needs a notification channel, the app's launcher activity
 * and the watch's Data Layer, and `:core:media` may not depend on the modules that own those. The
 * service injects this; Hilt supplies the concrete one.
 *
 * Implementations must not throw. This is called from a player callback on a service that is very
 * probably the only thing running at the time, and a bell that failed to ring is a disappointment,
 * not a reason to take playback down with it.
 */
interface BellRinger {

    /**
     * Rings the bell: sound it where the user is, and buzz anything else of theirs that can be felt.
     *
     * @param episodeTitle what just finished, for the notification's text, or null when the player
     *   has no title for it — which is rare enough to be worth a fallback rather than a guard.
     */
    suspend fun ring(episodeTitle: String?)
}
