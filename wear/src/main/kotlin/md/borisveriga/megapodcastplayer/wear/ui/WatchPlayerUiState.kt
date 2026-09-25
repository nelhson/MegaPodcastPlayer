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
 * How long a volume the wearer set keeps the bar where they put it.
 *
 * The same bargain [SEEK_HOLD_MS] strikes, and for the same reason: between sending `SetVolume`
 * and the phone publishing the result the last snapshot still describes the old level, and showing
 * it would walk the bar back off the step the wearer just turned onto. Shorter than the seek hold
 * because volume is sent as it is turned rather than once at the end, so a reply is never far
 * behind — and because a bar stuck a step out of date is a worse lie when the ears can hear the
 * difference.
 */
internal const val VOLUME_HOLD_MS = 1_500L

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
 * A volume the wearer has set, and whether the phone has been told yet.
 *
 * Unlike a scrub, this is never "in progress": every step is sent, throttled rather than held
 * back, because a volume nobody applied until the finger stopped would be a volume nobody could
 * hear themselves choosing. What is held is the *reading* — see [VOLUME_HOLD_MS].
 *
 * @property level the level the wearer has turned to, on the phone's scale.
 * @property sentAtElapsedMs the watch's elapsed-realtime clock when this level went out, or null
 *   while it is still waiting its turn behind the throttle.
 */
internal data class VolumeAdjustment(
    val level: Int,
    val sentAtElapsedMs: Long? = null,
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
 * @property isAdjustingVolume true while the volume row holds the bezel. Never true at the same
 *   time as [isScrubbing]: rotary input has exactly one focus owner, so the two modes are
 *   mutually exclusive by construction, and the view model is where that is enforced.
 * @property volumeLevel the level the volume row draws, on the phone's `0..maxVolume` scale. Not
 *   simply the snapshot's: a level the wearer has just turned to is shown at once and held over
 *   the round trip, the way a committed scrub is.
 * @property momentSaved true for a few seconds after a moment is marked. A watch has no snackbar
 *   and the mark leaves nothing on screen, so without this the button is one the wearer presses and
 *   then presses again because they cannot tell whether the first press did anything.
 * @property showsScrubHint true while the first scrub on this watch is being explained. Taking hold
 *   of the bar is the one gesture here that leaves no trace on the screen, so the first time it is
 *   done the bar says what the bezel now does.
 * @property showsVolumeHint the same sentence for the volume bar, for the same reason. Its minus
 *   and plus buttons are visible and explain themselves; that the bezel moves it once the volume
 *   button has opened it is the part nothing on the screen says.
 */
data class WatchPlayerUiState(
    val link: PhoneLink = PhoneLink.CHECKING,
    val snapshot: NowPlayingSnapshot = NowPlayingSnapshot(),
    val lastCommandFailed: Boolean = false,
    val isScrubbing: Boolean = false,
    val isAdjustingVolume: Boolean = false,
    val volumeLevel: Int = 0,
    val momentSaved: Boolean = false,
    val showsScrubHint: Boolean = false,
    val showsVolumeHint: Boolean = false,
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

    /**
     * True when the volume row is worth drawing.
     *
     * Needs the same reachable, loaded phone the transport needs — volume with nothing playing
     * adjusts something nobody can hear — and a phone that reported a scale to move along. A
     * phone that did not gets no row at all rather than one whose bar cannot move; see
     * [NowPlayingSnapshot.canSetVolume].
     */
    val canSetVolume: Boolean
        get() = showsControls && snapshot.canSetVolume

    /**
     * Whether moving the volume by [steps] would change it.
     *
     * At an end stop it would not, and a haptic tick for a step that was not taken is the hand
     * being told something that did not happen. Shared by the bezel and the finger, which tick on
     * the same rule.
     *
     * @param steps signed whole steps; positive is louder.
     * @return false when the level is already at the end it would move towards.
     */
    fun volumeWouldMove(steps: Int): Boolean =
        (volumeLevel + steps).coerceIn(0, snapshot.maxVolume) != volumeLevel
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
 * @param volume a level the wearer has just set, which overrides the phone's until it confirms.
 * @param isAdjustingVolume whether the volume row currently holds the bezel.
 * @param momentSaved whether the mark-a-moment confirmation is up.
 * @param showsScrubHint whether the first-scrub explanation is up.
 * @param showsVolumeHint whether the first-volume explanation is up.
 * @return the state for the pages and the position for the bar, built from one reading of the
 *   inputs so the two never disagree about which episode the position belongs to.
 */
internal fun watchPlayerFrame(
    link: PhoneLink,
    received: ReceivedSnapshot?,
    nowElapsedMs: Long,
    lastCommandFailed: Boolean = false,
    scrub: ScrubState? = null,
    volume: VolumeAdjustment? = null,
    isAdjustingVolume: Boolean = false,
    momentSaved: Boolean = false,
    showsScrubHint: Boolean = false,
    showsVolumeHint: Boolean = false,
): WatchPlayerFrame {
    val snapshot = received?.snapshot ?: NowPlayingSnapshot()
    val sinceArrivalMs = if (received == null) 0L else nowElapsedMs - received.receivedAtElapsedMs

    val shown = scrub?.positionMs?.takeIf {
        stillShowing(scrub.committedAtElapsedMs, received, nowElapsedMs, SEEK_HOLD_MS)
    }
    // The phone's position is a reading taken some time ago, so it is advanced to now.
    val positionMs = shown ?: snapshot.positionAfter(sinceArrivalMs)

    // The level the wearer turned to wins until the phone confirms it, for the reason a held
    // scrub does: the last snapshot still describes the level they turned away from. Confirmed
    // means the phone *says that level*, not merely that it said something since: a snapshot sent
    // for any other reason — the position drifting, the reply to the step before this one — lands
    // after the send too, still carrying the old level, and believing it walks the bar back.
    val volumeLevel = volume?.level?.takeIf { level ->
        stillShowing(volume.sentAtElapsedMs, received, nowElapsedMs, VOLUME_HOLD_MS) {
            it.snapshot.volume == level
        }
    } ?: snapshot.volume

    val uiState = WatchPlayerUiState(
        link = link,
        // The reading the position was extrapolated from has done its job by now; see the
        // property's documentation for why it is not carried along.
        snapshot = snapshot.copy(positionMs = 0L),
        lastCommandFailed = lastCommandFailed,
        // Only an uncommitted scrub is "scrubbing": once the seek is away the user has let go, and
        // the held position is just covering the round trip.
        isScrubbing = scrub != null && scrub.committedAtElapsedMs == null,
        isAdjustingVolume = isAdjustingVolume,
        volumeLevel = volumeLevel.coerceIn(0, snapshot.maxVolume),
        momentSaved = momentSaved,
        showsScrubHint = showsScrubHint,
        showsVolumeHint = showsVolumeHint,
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
 * Whether a value the wearer set still governs what the screen shows.
 *
 * One rule serving the scrubber and the volume row, because it answers one question: the wearer
 * moved something, the phone has not answered yet, and until it does the screen must show what
 * they did rather than what the phone last said.
 *
 * A value that has not been sent always governs — the wearer is still moving it. A sent one
 * governs until the phone confirms, which is a snapshot that arrived *after* it went out and that
 * [confirms] accepts, or until [holdMs] passes without one. The bound is what keeps a phone that
 * never answers — or answers with something else, because it refused or clamped the value — from
 * freezing the control for good.
 *
 * @param sentAtElapsedMs the watch's elapsed-realtime clock when the command went out, or null
 *   while nothing has been sent.
 * @param received the last snapshot the watch got, or null if none.
 * @param nowElapsedMs the watch's current elapsed-realtime clock.
 * @param holdMs how long a sent value is held before the phone's own reading is believed again.
 * @param confirms whether a snapshot that arrived after the send is the answer to it. Arrival
 *   alone is enough for a seek, whose result is a position that can never be matched exactly; a
 *   volume is an exact level, so its caller asks for that level.
 */
private fun stillShowing(
    sentAtElapsedMs: Long?,
    received: ReceivedSnapshot?,
    nowElapsedMs: Long,
    holdMs: Long,
    confirms: (ReceivedSnapshot) -> Boolean = { true },
): Boolean {
    val sentAt = sentAtElapsedMs ?: return true
    val confirmed = received != null && received.receivedAtElapsedMs > sentAt && confirms(received)
    return !confirmed && nowElapsedMs - sentAt < holdMs
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
