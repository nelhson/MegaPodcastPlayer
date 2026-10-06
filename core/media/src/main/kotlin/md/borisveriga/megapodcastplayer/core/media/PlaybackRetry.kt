package md.borisveriga.megapodcastplayer.core.media

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter

/**
 * Automatic retries of a failed episode, before anyone is told it failed.
 *
 * Most of what stops an episode is a moment of bad network: a stream URL refused once, a socket
 * reset in a tunnel, a YouTube rendition that answered on the second ask. Putting *Try again* in
 * front of the user for each of those made them do by hand what the player can do itself, and for
 * a picture it was worse — the first refusal handed the episode straight back to sound. So the
 * player asks again on its own a few times, and only an episode that keeps failing reaches the
 * error the screens already know how to say.
 */

/**
 * Boolean session extra: true while the player is waiting to ask again for an episode that failed.
 *
 * Published by the service and read by `PlaybackConnection`, which shows such a player as
 * buffering rather than failed — the error the player is holding is not the last word yet.
 */
internal const val EXTRA_RETRYING = "md.borisveriga.megapodcastplayer.extra.RETRYING"

/**
 * How many times a failed episode is asked for again, and how long to wait before each.
 *
 * Pure, so the schedule can be asserted without a player.
 *
 * @property maxRetries how many retries follow the first failure before it is given up on.
 * @property baseDelayMs the wait before the first retry; each one after waits twice as long as the
 *   one before, so a network that is down for a few seconds is given those seconds.
 */
internal class PlaybackRetryPolicy(
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    private val baseDelayMs: Long = DEFAULT_BASE_DELAY_MS,
) {

    /**
     * The wait before the next retry, or null when the retries are used up.
     *
     * @param retriesMade how many retries have already been made for this failure.
     * @return milliseconds to wait — 1 s, 2 s, 4 s with the defaults — or null to give up.
     */
    fun delayBeforeRetry(retriesMade: Int): Long? =
        if (retriesMade in 0 until maxRetries) baseDelayMs shl retriesMade else null

    companion object {
        /** Three, as asked for: enough to ride out a blip, few enough to say so within seconds. */
        private const val DEFAULT_MAX_RETRIES = 3

        /** A second: long enough for a radio to come back, short enough to read as buffering. */
        private const val DEFAULT_BASE_DELAY_MS = 1_000L

        /**
         * How far past its last failure an episode must get before its count starts again.
         *
         * Progress rather than *ready*: a fault a few seconds ahead of the playhead — a truncated
         * download, a server that fails one byte range — reaches ready on what is buffered before
         * it every time, and a count reset there would retry it forever.
         */
        const val PROGRESS_RESET_MS: Long = 10_000L

        /**
         * The most retries one item gets, however far it gets between them.
         *
         * Bounds an item that fails every half minute all the way through, which progress alone
         * would keep forgiving, and the non-fatal report each retry files.
         */
        const val MAX_RETRIES_PER_ITEM: Int = 10
    }
}

/**
 * Whether asking again could change the answer.
 *
 * A dropped connection, a timeout or a server error can; a file that is not there, a format the
 * device cannot play or a link that is gone cannot, and retrying those only kept the user looking at
 * a spinner for seven seconds before being told the same thing. A 403 is retried: a YouTube stream
 * URL expires with exactly that, and the retry resolves a fresh one.
 *
 * @param error what the player failed with.
 * @return true when the failure is worth retrying.
 */
internal fun isRetryable(error: PlaybackException): Boolean = when (error.errorCode) {
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
    PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
    PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
    PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
    -> false

    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> isRetryableHttpStatus(error)

    else -> true
}

/**
 * Whether a bad HTTP status is one a second ask can get past.
 *
 * @param error a failure with [PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS].
 * @return true for a server error, a 403 (an expired stream URL), a timeout or a rate limit; true
 *   too when the status cannot be read, since asking once more is then the cheaper mistake.
 */
private fun isRetryableHttpStatus(error: PlaybackException): Boolean {
    val status = generateSequence(error.cause) { it.cause }
        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
        .firstOrNull()
        ?.responseCode
        ?: return true
    return status >= HTTP_SERVER_ERROR || status in RETRYABLE_CLIENT_STATUSES
}

/** The first server-error status. */
private const val HTTP_SERVER_ERROR = 500

/** Client statuses worth asking again for: expired URL, timeout, rate limit. */
private val RETRYABLE_CLIENT_STATUSES = setOf(403, 408, 429)

/**
 * Prepares a failed episode again, at the position it failed at, until [policy] says to stop.
 *
 * Installed on the real player, ahead of the session, so that [onRetryingChanged] reaches the
 * controllers before the error does and they can show it as buffering rather than flash it.
 *
 * The count starts again once the episode has played [PlaybackRetryPolicy.PROGRESS_RESET_MS] past
 * where it last failed, and whenever the item changes, which includes a picture being handed back
 * to sound: that sound gets retries of its own. No item gets more than
 * [PlaybackRetryPolicy.MAX_RETRIES_PER_ITEM] in all. A failure [isRetryable] rules out is given up
 * on at once, and a stop cancels a retry still waiting; see [cancelPending].
 *
 * @property player the player whose errors are retried.
 * @property scope where the waits run; the service's, on the main thread the player lives on.
 * @property crashReporter where each failure that was retried rather than shown is recorded.
 * @property onRetryingChanged told when a retry starts waiting and when there is none left pending.
 * @property onGaveUp told of the failure once the retries are used up; the error stands from then on.
 * @property policy how many retries, and how far apart.
 */
internal class PlaybackRetryListener(
    private val player: Player,
    private val scope: CoroutineScope,
    private val crashReporter: CrashReporter,
    private val onRetryingChanged: (Boolean) -> Unit,
    private val onGaveUp: (PlaybackException) -> Unit,
    private val policy: PlaybackRetryPolicy = PlaybackRetryPolicy(),
) : Player.Listener {

    /** Retries made since the episode last got past a failure. */
    private var retriesMade = 0

    /** Retries made for the current item in all. */
    private var itemRetries = 0

    /** Where the current item last failed, or null when it has not. */
    private var lastFailureAtMs: Long? = null

    /** The wait for the next retry, while there is one. */
    private var pending: Job? = null

    /** Whether a retry is pending, as last told to [onRetryingChanged]. */
    private var retrying = false

    /**
     * Schedules a retry of the failed episode, or gives up on it once the retries are used up.
     *
     * @param error what the player failed with.
     */
    override fun onPlayerError(error: PlaybackException) {
        val position = player.currentPosition
        lastFailureAtMs?.let { last ->
            if (position >= last + PlaybackRetryPolicy.PROGRESS_RESET_MS) retriesMade = 0
        }
        lastFailureAtMs = position
        val wait = policy.delayBeforeRetry(retriesMade)
            ?.takeIf { isRetryable(error) && itemRetries < PlaybackRetryPolicy.MAX_RETRIES_PER_ITEM }
        if (wait == null) {
            settle()
            onGaveUp(error)
            return
        }
        retriesMade += 1
        itemRetries += 1
        // Not shown to anyone, so recorded: a source that only ever plays on the third ask is a
        // fault worth seeing even though the user never did.
        crashReporter.recordNonFatal(NON_FATAL_RETRIED, error)
        setRetrying(true)
        val failedId = player.currentMediaItem?.mediaId
        pending?.cancel()
        pending = scope.launch {
            delay(wait)
            // Something else got there first: the user moved on, stopped, or prepared it by hand.
            if (player.playerError == null || player.currentMediaItem?.mediaId != failedId) {
                setRetrying(false)
                return@launch
            }
            // An errored player keeps its item and position; preparing again resumes from there.
            player.prepare()
        }
    }

    /**
     * Lowers the retrying flag once the episode is playing again.
     *
     * The count is left alone: being ready says only that something is buffered, and a fault
     * further on would otherwise get a fresh three retries every time; see
     * [PlaybackRetryPolicy.PROGRESS_RESET_MS].
     */
    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY) setRetrying(false)
    }

    /** Drops a retry meant for an item that is no longer the current one, and starts afresh. */
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        settle()
        itemRetries = 0
        lastFailureAtMs = null
    }

    /**
     * Drops a retry still waiting, because playback was stopped.
     *
     * Called from the session's player on `stop()`. A stop keeps the player's error and item, so
     * nothing the player reports would otherwise tell the waiting retry that it is no longer
     * wanted, and a headset's stop would be followed a second later by the episode starting again.
     */
    fun cancelPending() {
        settle()
    }

    /** Cancels any pending retry and starts the count again. */
    private fun settle() {
        pending?.cancel()
        pending = null
        retriesMade = 0
        setRetrying(false)
    }

    /**
     * Tells [onRetryingChanged], once per change.
     *
     * @param value whether a retry is now pending.
     */
    private fun setRetrying(value: Boolean) {
        if (retrying == value) return
        retrying = value
        onRetryingChanged(value)
    }

    private companion object {
        /** One message for the failure kind, fixed, so they group into one report. */
        const val NON_FATAL_RETRIED = "Playback failed; retrying"
    }
}

/**
 * The session extras that say whether a retry is pending.
 *
 * @param retrying whether the player is waiting to ask again.
 * @return the bundle to publish with `MediaSession.setSessionExtras`.
 */
internal fun retryingExtras(retrying: Boolean): Bundle = Bundle().apply { putBoolean(EXTRA_RETRYING, retrying) }

/**
 * How a player waiting to retry is shown: buffering, with the error it is holding kept back.
 *
 * Only while there is an error to keep back. A retry flag left behind by a player that was
 * stopped by hand says nothing about a player with no error, and must not draw a spinner. A failed
 * *command* is not the player's error and is still said.
 *
 * @param retrying whether the service says a retry is pending.
 * @param hasPlayerError whether the player is holding an error.
 * @param commandError the last failed command's message, which stays.
 * @return this state, or this state shown as buffering with only the command's error.
 */
internal fun PlaybackState.whileRetrying(
    retrying: Boolean,
    hasPlayerError: Boolean,
    commandError: String?,
): PlaybackState = if (retrying && hasPlayerError) {
    copy(
        isBuffering = true,
        errorMessage = commandError,
        error = commandError?.let { PlaybackError.UNKNOWN },
    )
} else {
    this
}
