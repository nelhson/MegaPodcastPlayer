package md.borisveriga.megapodcastplayer.wear.ui

import md.borisveriga.megapodcastplayer.core.wearprotocol.NowPlayingSnapshot
import md.borisveriga.megapodcastplayer.wear.data.PhoneLink
import md.borisveriga.megapodcastplayer.wear.data.ReceivedSnapshot

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
 * What the watch screen draws, apart from the clock.
 *
 * Deliberately does not carry the playback position. That lives in [PlaybackPosition], and the
 * split is the whole reason the screen scrolls smoothly: this object is collected above the list,
 * so anything that changes it recomposes every row, and the position changes once a second.
 * Everything here changes when the wearer does something or the phone says something; the position
 * is the one thing that changes by itself. See [watchPlayerFrame].
 *
 * @property link whether the phone can be reached at all.
 * @property snapshot what the phone last said it was playing. Its `positionMs` is always zero here.
 *   The live position is [PlaybackPosition]; the snapshot's was the reading it was extrapolated
 *   from, and keeping it would put the clock back into the list by the side door.
 * @property lastCommandFailed set when a command could not be delivered, so the screen can say the
 *   tap did nothing instead of silently ignoring it.
 * @property isScrubbing true while the user is dragging the progress bar, which is what makes the
 *   bar grow a thumb and take rotary focus.
 * @property momentSaved true for a few seconds after a moment is marked. A watch has no snackbar
 *   and the mark leaves nothing on screen, so without this the button is one the wearer presses and
 *   then presses again because they cannot tell whether the first press did anything.
 * @property showsScrubHint true while the first scrub on this watch is being explained. Taking hold
 *   of the bar is the one gesture here that leaves no trace on the screen, so the first time it is
 *   done the bar says what the bezel now does.
 */
data class WatchPlayerUiState(
    val link: PhoneLink = PhoneLink.CHECKING,
    val snapshot: NowPlayingSnapshot = NowPlayingSnapshot(),
    val lastCommandFailed: Boolean = false,
    val isScrubbing: Boolean = false,
    val momentSaved: Boolean = false,
    val showsScrubHint: Boolean = false,
) {

    /**
     * True when the transport controls are worth drawing.
     *
     * That needs a reachable phone with something loaded: controls for an unreachable phone are a
     * lie, and controls for an idle one do nothing.
     */
    val showsControls: Boolean
        get() = !snapshot.isIdle && link == PhoneLink.CONNECTED

    /**
     * True when the unreachable-phone screen should replace everything else.
     *
     * The watch is a remote control and nothing more, so a phone it cannot reach leaves it with
     * nothing to offer: the whole screen becomes the sentence saying so, and what to do about it.
     */
    val showsLinkProblem: Boolean
        get() = link != PhoneLink.CONNECTED

    /** True when the phone is reachable but has nothing loaded. */
    val showsEmptyQueue: Boolean
        get() = link == PhoneLink.CONNECTED && snapshot.isIdle && snapshot.upNext.isEmpty()

    /**
     * True when the bar can be scrubbed at all.
     *
     * Seeking needs a duration to seek within. While it is unknown there is no scale to drag along,
     * so the bar stays a plain indicator rather than offering a gesture that could not mean anything.
     */
    val canScrub: Boolean
        get() = showsControls && snapshot.knownDurationMs != null
}

/**
 * Where playback has reached, as the bar shows it.
 *
 * The one thing on the screen that changes by itself, once a second while the phone plays. It is
 * kept apart from [WatchPlayerUiState] so that the bar — the only thing that draws it — is the only
 * thing that recomposes when it moves.
 *
 * Not simply copied out of the snapshot: the phone publishes only when something changes, so the
 * watch advances the clock itself between publishes; while the user is scrubbing it shows where
 * they have dragged to instead. See [watchPlayerFrame].
 *
 * @property positionMs playback position extrapolated to now, or the scrub preview while scrubbing.
 * @property progress fraction played, in `0f..1f`, matching [positionMs].
 */
data class PlaybackPosition(
    val positionMs: Long = 0L,
    val progress: Float = 0f,
)

/**
 * Everything the screen draws, computed together and then handed out in two parts.
 *
 * One function builds both because they share their inputs and their intermediate values; the
 * view model then exposes them as two flows, so that a change to one does not wake the other.
 *
 * @property uiState what the pages draw.
 * @property position what the bar draws.
 */
internal data class WatchPlayerFrame(
    val uiState: WatchPlayerUiState = WatchPlayerUiState(),
    val position: PlaybackPosition = PlaybackPosition(),
)

/**
 * Builds the screen state from what the phone last said and how long ago it said it.
 *
 * Kept separate from the view model, and free of Android types, so both the extrapolation and the
 * seek hold can be tested by passing clock readings rather than by waiting for them.
 *
 * @param link whether the phone is reachable.
 * @param received the last snapshot and when it arrived, or null if none has.
 * @param nowElapsedMs the watch's current [android.os.SystemClock.elapsedRealtime].
 * @param lastCommandFailed whether the most recent command failed to send.
 * @param scrub a scrub in progress or recently committed, which overrides the extrapolated position.
 * @param momentSaved whether the mark-a-moment confirmation is up.
 * @param showsScrubHint whether the first-scrub explanation is up.
 * @return the state for the pages and the position for the bar, built from one reading of the
 *   inputs so the two never disagree about which episode the position belongs to.
 */
internal fun watchPlayerFrame(
    link: PhoneLink,
    received: ReceivedSnapshot?,
    nowElapsedMs: Long,
    lastCommandFailed: Boolean = false,
    scrub: ScrubState? = null,
    momentSaved: Boolean = false,
    showsScrubHint: Boolean = false,
): WatchPlayerFrame {
    val snapshot = received?.snapshot ?: NowPlayingSnapshot()
    val sinceArrivalMs = if (received == null) 0L else nowElapsedMs - received.receivedAtElapsedMs

    val shown = scrub?.positionMs?.takeIf { scrub.stillShowing(received, nowElapsedMs) }
    // The phone's position is a reading taken some time ago, so it is advanced to now.
    val positionMs = shown ?: snapshot.positionAfter(sinceArrivalMs)

    val uiState = WatchPlayerUiState(
        link = link,
        // The reading the position was extrapolated from has done its job by now; see the
        // property's documentation for why it is not carried along.
        snapshot = snapshot.copy(positionMs = 0L),
        lastCommandFailed = lastCommandFailed,
        // Only an uncommitted scrub is "scrubbing": once the seek is away the user has let go, and
        // the held position is just covering the round trip.
        isScrubbing = scrub != null && scrub.committedAtElapsedMs == null,
        momentSaved = momentSaved,
        showsScrubHint = showsScrubHint,
    )

    return WatchPlayerFrame(
        uiState = uiState,
        position = PlaybackPosition(
            positionMs = positionMs,
            progress = snapshot.progressAt(positionMs),
        ),
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
 * Returns `0f` while the duration is unknown, which is the safe default for a progress bar: no bar
 * rather than a bar that means nothing.
 *
 * @param positionMs the position to express as a fraction.
 */
private fun NowPlayingSnapshot.progressAt(positionMs: Long): Float {
    val duration = knownDurationMs ?: return 0f
    return (positionMs.toFloat() / duration).coerceIn(0f, 1f)
}
