package md.borisveriga.megapodcastplayer.feature.player.embedded

import android.webkit.JavascriptInterface

/** Something the embedded player's page reported. */
sealed interface EmbeddedPlayerEvent {

    /** The API has loaded and the player is ready to be told things. */
    data object Ready : EmbeddedPlayerEvent

    /**
     * The player changed state.
     *
     * @property state what it is doing now.
     */
    data class StateChanged(val state: EmbeddedPlayerState) : EmbeddedPlayerEvent

    /**
     * Where the player is, reported once a second while the page is alive.
     *
     * @property positionMs the position, in milliseconds.
     * @property durationMs the video's length, in milliseconds; zero until the player knows it.
     */
    data class Time(val positionMs: Long, val durationMs: Long) : EmbeddedPlayerEvent

    /**
     * The player would not play the video.
     *
     * @property error why.
     */
    data class Failed(val error: EmbeddedPlayerError) : EmbeddedPlayerEvent
}

/**
 * The object the embedded player's page calls back into, published to it as [EMBEDDED_BRIDGE_NAME].
 *
 * Each method is one callback the page's script makes; see [embeddedPlayerHtml]. They arrive on
 * the `WebView`'s JavaScript thread, and are handed on as they are: the receiver is a view model
 * whose state is safe to update from any thread, and a hop to the main thread here would be a
 * second place to get the threading wrong.
 *
 * Every method is public and annotated, as `addJavascriptInterface` requires, and the class is kept
 * by `proguard-rules.pro` for the same reason: a shrunk build that renamed one would be a page
 * calling a method that no longer exists.
 *
 * @property onEvent receives what the page reported.
 */
class EmbeddedPlayerBridge(private val onEvent: (EmbeddedPlayerEvent) -> Unit) {

    /** The API has loaded. */
    @JavascriptInterface
    fun onReady() {
        onEvent(EmbeddedPlayerEvent.Ready)
    }

    /**
     * The player changed state.
     *
     * @param code a `YT.PlayerState` value; an unknown one is dropped rather than guessed at.
     */
    @JavascriptInterface
    fun onStateChange(code: Int) {
        val state = embeddedPlayerStateOf(code) ?: return
        onEvent(EmbeddedPlayerEvent.StateChanged(state))
    }

    /**
     * Where the player is.
     *
     * @param positionSeconds the position, as the API reports it: seconds, fractional.
     * @param durationSeconds the video's length the same way; zero or `NaN` until known.
     */
    @JavascriptInterface
    fun onTime(positionSeconds: Double, durationSeconds: Double) {
        onEvent(
            EmbeddedPlayerEvent.Time(
                positionMs = positionSeconds.toWholeMillis(),
                durationMs = durationSeconds.toWholeMillis(),
            ),
        )
    }

    /**
     * The player would not play the video.
     *
     * @param code an `onError` value.
     */
    @JavascriptInterface
    fun onError(code: Int) {
        onEvent(EmbeddedPlayerEvent.Failed(embeddedPlayerErrorOf(code)))
    }
}

/**
 * Seconds as the API reports them, as whole milliseconds; a `NaN` or a negative reads as zero.
 *
 * The API hands back `NaN` for a duration it does not know yet, and `Long` has no `NaN`.
 */
internal fun Double.toWholeMillis(): Long =
    if (isNaN() || this < 0.0) 0L else (this * MILLIS_PER_SECOND).toLong()

private const val MILLIS_PER_SECOND = 1_000.0
