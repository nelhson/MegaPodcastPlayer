package md.borisveriga.bpodcat.wear.ui

import md.borisveriga.bpodcat.core.wearprotocol.NowPlayingSnapshot
import md.borisveriga.bpodcat.wear.data.PhoneLink
import md.borisveriga.bpodcat.wear.data.ReceivedSnapshot

/**
 * What the watch screen draws.
 *
 * [positionMs] and [progress] are not simply copied out of [snapshot]: the phone only publishes when
 * something changes, so the watch advances the clock itself between publishes. See
 * [watchPlayerUiState].
 *
 * @property link whether the phone can be reached at all.
 * @property snapshot the last thing the phone said, or an empty snapshot if it has never said
 *   anything.
 * @property positionMs playback position extrapolated to now.
 * @property progress fraction played, in `0f..1f`, extrapolated to now.
 * @property lastCommandFailed set when a command could not be delivered, so the screen can say the
 *   tap did nothing instead of silently ignoring it.
 */
data class WatchPlayerUiState(
    val link: PhoneLink = PhoneLink.CHECKING,
    val snapshot: NowPlayingSnapshot = NowPlayingSnapshot(),
    val positionMs: Long = 0L,
    val progress: Float = 0f,
    val lastCommandFailed: Boolean = false,
) {

    /**
     * True when the transport controls are worth drawing.
     *
     * Requires both a reachable phone and something loaded on it: controls for an unreachable phone
     * are a lie, and controls for an idle one do nothing.
     */
    val showsControls: Boolean
        get() = link == PhoneLink.CONNECTED && !snapshot.isIdle

    /** True when the phone is reachable but has nothing loaded. */
    val showsEmptyQueue: Boolean
        get() = link == PhoneLink.CONNECTED && snapshot.isIdle && snapshot.upNext.isEmpty()
}

/**
 * Builds the screen state from what the phone last said and how long ago that was.
 *
 * Kept separate from the view model, and free of Android types, so the extrapolation can be tested
 * by passing a clock reading rather than by waiting for one.
 *
 * @param link whether the phone is reachable.
 * @param received the last snapshot and when it arrived, or null if none has.
 * @param nowElapsedMs the watch's current [android.os.SystemClock.elapsedRealtime].
 * @param lastCommandFailed whether the most recent command failed to send.
 */
internal fun watchPlayerUiState(
    link: PhoneLink,
    received: ReceivedSnapshot?,
    nowElapsedMs: Long,
    lastCommandFailed: Boolean = false,
): WatchPlayerUiState {
    val snapshot = received?.snapshot ?: NowPlayingSnapshot()
    val sinceArrivalMs = if (received == null) 0L else nowElapsedMs - received.receivedAtElapsedMs

    return WatchPlayerUiState(
        link = link,
        snapshot = snapshot,
        positionMs = snapshot.positionAfter(sinceArrivalMs),
        progress = snapshot.progressAfter(sinceArrivalMs),
        lastCommandFailed = lastCommandFailed,
    )
}
