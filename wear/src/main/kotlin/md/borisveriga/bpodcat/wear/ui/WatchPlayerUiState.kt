package md.borisveriga.bpodcat.wear.ui

import androidx.compose.ui.graphics.ImageBitmap
import md.borisveriga.bpodcat.core.wearprotocol.NowPlayingSnapshot
import md.borisveriga.bpodcat.wear.data.PhoneLink
import md.borisveriga.bpodcat.wear.data.ReceivedSnapshot

/**
 * How long a committed seek keeps the progress bar where the user put it.
 *
 * Between sending `SeekTo` and the phone publishing the result there is a Bluetooth round trip, and
 * during it the phone's last snapshot still describes the old position. Extrapolating that would
 * walk the bar back to where the user just dragged it away from, then jump forward when the reply
 * lands. Holding avoids the bounce; a bound is still needed, because a phone that never replies must
 * not freeze the bar forever.
 */
internal const val SEEK_HOLD_MS = 3_000L

/**
 * A scrub in progress, or one just committed.
 *
 * @property positionMs where the user has dragged to.
 * @property committedAtElapsedMs the watch's elapsed-realtime clock when `SeekTo` was sent, or null
 *   while the user is still moving and nothing has been sent yet.
 */
internal data class ScrubState(
    val positionMs: Long,
    val committedAtElapsedMs: Long? = null,
)

/**
 * What the watch screen draws.
 *
 * [positionMs] and [progress] are not simply copied out of [snapshot]: the phone only publishes when
 * something changes, so the watch advances the clock itself between publishes, and while the user is
 * scrubbing it shows where they have dragged to instead. See [watchPlayerUiState].
 *
 * @property link whether the phone can be reached at all.
 * @property snapshot the last thing the phone said, or an empty snapshot if it has never said
 *   anything.
 * @property positionMs playback position extrapolated to now, or the scrub preview while scrubbing.
 * @property progress fraction played, in `0f..1f`, matching [positionMs].
 * @property lastCommandFailed set when a command could not be delivered, so the screen can say the
 *   tap did nothing instead of silently ignoring it.
 * @property isScrubbing true while the user is dragging the progress bar, which is what makes the
 *   bar grow a thumb and take rotary focus.
 * @property artwork cover art the phone sent, or null when it sent none.
 */
data class WatchPlayerUiState(
    val link: PhoneLink = PhoneLink.CHECKING,
    val snapshot: NowPlayingSnapshot = NowPlayingSnapshot(),
    val positionMs: Long = 0L,
    val progress: Float = 0f,
    val lastCommandFailed: Boolean = false,
    val isScrubbing: Boolean = false,
    val artwork: ImageBitmap? = null,
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

    /**
     * True when the bar can be scrubbed at all.
     *
     * Seeking needs a duration to seek within. While the phone has not read one there is no scale to
     * drag along, so the bar stays a plain indicator rather than offering a gesture that could not
     * mean anything.
     */
    val canScrub: Boolean
        get() = showsControls && snapshot.knownDurationMs != null
}

/**
 * Builds the screen state from what the phone last said and how long ago that was.
 *
 * Kept separate from the view model, and free of Android types beyond the decoded bitmap, so both
 * the extrapolation and the seek hold can be tested by passing clock readings rather than by waiting
 * for them.
 *
 * @param link whether the phone is reachable.
 * @param received the last snapshot and when it arrived, or null if none has.
 * @param nowElapsedMs the watch's current [android.os.SystemClock.elapsedRealtime].
 * @param lastCommandFailed whether the most recent command failed to send.
 * @param scrub a scrub in progress or recently committed, which overrides the extrapolated position.
 * @param artwork cover art resolved from the phone's data item, if any.
 */
internal fun watchPlayerUiState(
    link: PhoneLink,
    received: ReceivedSnapshot?,
    nowElapsedMs: Long,
    lastCommandFailed: Boolean = false,
    scrub: ScrubState? = null,
    artwork: ImageBitmap? = null,
): WatchPlayerUiState {
    val snapshot = received?.snapshot ?: NowPlayingSnapshot()
    val sinceArrivalMs = if (received == null) 0L else nowElapsedMs - received.receivedAtElapsedMs

    val shown = scrub?.positionMs?.takeIf { scrub.stillShowing(received, nowElapsedMs) }

    return WatchPlayerUiState(
        link = link,
        snapshot = snapshot,
        positionMs = shown ?: snapshot.positionAfter(sinceArrivalMs),
        progress = shown?.let { snapshot.progressAt(it) } ?: snapshot.progressAfter(sinceArrivalMs),
        lastCommandFailed = lastCommandFailed,
        // Only an uncommitted scrub is "scrubbing": once SeekTo is away the user has let go, and the
        // held position is just covering the round trip.
        isScrubbing = scrub != null && scrub.committedAtElapsedMs == null,
        artwork = artwork,
    )
}

/**
 * Whether this scrub still governs what the bar shows.
 *
 * An uncommitted scrub always does — the user's finger is on it. A committed one does until the
 * phone confirms, which is a snapshot that arrived *after* the command went out, or until
 * [SEEK_HOLD_MS] passes without one.
 *
 * @param received the last snapshot the watch got, or null if none.
 * @param nowElapsedMs the watch's current elapsed-realtime clock.
 */
private fun ScrubState.stillShowing(received: ReceivedSnapshot?, nowElapsedMs: Long): Boolean {
    val committedAt = committedAtElapsedMs ?: return true
    val confirmed = received != null && received.receivedAtElapsedMs > committedAt
    return !confirmed && nowElapsedMs - committedAt < SEEK_HOLD_MS
}

/**
 * Fraction played at an absolute position, in `0f..1f`.
 *
 * The counterpart of [NowPlayingSnapshot.progressAfter] for a position that was chosen rather than
 * extrapolated. Returns `0f` while the duration is unknown, matching that function.
 *
 * @param positionMs the position to express as a fraction.
 */
private fun NowPlayingSnapshot.progressAt(positionMs: Long): Float {
    val duration = knownDurationMs ?: return 0f
    return (positionMs.toFloat() / duration).coerceIn(0f, 1f)
}
