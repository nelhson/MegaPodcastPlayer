package md.borisveriga.megapodcastplayer.wear.ui

import md.borisveriga.megapodcastplayer.core.wearprotocol.NowPlayingSnapshot
import md.borisveriga.megapodcastplayer.core.wearprotocol.OfflineEpisode
import md.borisveriga.megapodcastplayer.wear.data.PhoneLink
import md.borisveriga.megapodcastplayer.wear.data.ReceivedSnapshot
import md.borisveriga.megapodcastplayer.wear.data.StoredEpisode
import md.borisveriga.megapodcastplayer.wear.data.TransferProgress
import md.borisveriga.megapodcastplayer.wear.playback.WatchPlaybackState

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
 * Which device is producing the audio.
 *
 * The watch is a remote control until somebody plays an episode it holds, and then it is a player.
 * Almost every control on the screen means something different in the two cases — pause reaches a
 * different device, the queue is only the phone's — so the distinction is carried explicitly rather
 * than inferred from whether some field happens to be null.
 */
enum class PlaybackSource {

    /** The phone is playing and the watch is showing what it said. */
    PHONE,

    /** The watch is playing its own copy, with or without a phone in range. */
    WATCH,
}

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
 * [positionMs] and [progress] are not simply copied out of [snapshot]: while the phone is playing,
 * it publishes only when something changes, so the watch advances the clock itself between
 * publishes; while the user is scrubbing it shows where they have dragged to instead. See
 * [watchPlayerUiState].
 *
 * @property link whether the phone can be reached at all.
 * @property snapshot what is playing — the phone's own report, or the watch's local playback wearing
 *   the same shape; see [WatchPlaybackState.asSnapshot].
 * @property positionMs playback position extrapolated to now, or the scrub preview while scrubbing.
 * @property progress fraction played, in `0f..1f`, matching [positionMs].
 * @property lastCommandFailed set when a command could not be delivered, so the screen can say the
 *   tap did nothing instead of silently ignoring it.
 * @property isScrubbing true while the user is dragging the progress bar, which is what makes the
 *   bar grow a thumb and take rotary focus.
 * @property source which device the controls act on.
 * @property stored episodes whose audio is on the watch, newest first.
 * @property offered episodes the phone has downloaded and has not yet sent here.
 * @property transfers copies currently arriving, by episode id.
 */
data class WatchPlayerUiState(
    val link: PhoneLink = PhoneLink.CHECKING,
    val snapshot: NowPlayingSnapshot = NowPlayingSnapshot(),
    val positionMs: Long = 0L,
    val progress: Float = 0f,
    val lastCommandFailed: Boolean = false,
    val isScrubbing: Boolean = false,
    val source: PlaybackSource = PlaybackSource.PHONE,
    val stored: List<StoredEpisode> = emptyList(),
    val offered: List<OfflineEpisode> = emptyList(),
    val transfers: Map<String, TransferProgress> = emptyMap(),
) {

    /**
     * True when the transport controls are worth drawing.
     *
     * For the phone that needs a reachable phone with something loaded: controls for an unreachable
     * phone are a lie, and controls for an idle one do nothing. For the watch's own playback neither
     * question arises — the audio is coming out of the device the buttons are on.
     */
    val showsControls: Boolean
        get() = !snapshot.isIdle && (source == PlaybackSource.WATCH || link == PhoneLink.CONNECTED)

    /**
     * True when the unreachable-phone screen should replace everything else.
     *
     * Only when there is nothing else to offer. A watch with episodes on it is useful with the phone
     * switched off — that is what they are for — so an out-of-range phone becomes a line of text
     * above the local list rather than a wall in front of it. See [showsPhoneOutOfRange].
     */
    val showsLinkProblem: Boolean
        get() = link != PhoneLink.CONNECTED && source == PlaybackSource.PHONE && stored.isEmpty()

    /** True when the phone is out of reach but the watch has something of its own to show. */
    val showsPhoneOutOfRange: Boolean
        get() = link != PhoneLink.CONNECTED && !showsLinkProblem

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
     * True when the watch holds nothing and the phone is offering nothing.
     *
     * Only while the phone is reachable: with no phone in range an empty list is not a state anybody
     * can do anything about, and saying so would be nagging.
     */
    val showsNothingToCopy: Boolean
        get() = link == PhoneLink.CONNECTED && stored.isEmpty() && transfers.isEmpty() &&
            offered.isEmpty()

    /**
     * Transfers arriving now, paired with what they are.
     *
     * The store knows how many bytes have landed; only the phone's published offer knows whose bytes
     * they are. A transfer whose offer has since disappeared from the list is left out rather than
     * shown as a nameless bar.
     */
    val arriving: List<ArrivingEpisode>
        get() = transfers.mapNotNull { (id, progress) ->
            offered.firstOrNull { it.id == id }?.let { ArrivingEpisode(it, progress) }
        }

    /**
     * Episodes the phone has that the watch does not, and is not already receiving.
     *
     * Computed here rather than published that way: the phone offers everything it holds, because it
     * cannot know what arrived — a transfer that failed halfway leaves the phone thinking it sent an
     * episode the watch threw away.
     */
    val copyable: List<OfflineEpisode>
        get() {
            val here = stored.map { it.id }.toSet()
            return offered.filterNot { it.id in here || it.id in transfers }
        }
}

/**
 * An episode on its way to the watch.
 *
 * @property episode what is coming, from the phone's published offer.
 * @property progress how much of it has landed.
 */
data class ArrivingEpisode(
    val episode: OfflineEpisode,
    val progress: TransferProgress,
)

/**
 * Builds the screen state from what the phone last said, what the watch itself is playing, and how
 * long ago the phone said it.
 *
 * Kept separate from the view model, and free of Android types, so both the extrapolation and the
 * seek hold can be tested by passing clock readings rather than by waiting for them.
 *
 * Local playback wins when there is any. A watch playing its own audio is not also a remote control:
 * the buttons have to act on the thing making the noise, and showing the phone's episode above them
 * would put the wrong title over the right controls.
 *
 * @param link whether the phone is reachable.
 * @param received the last snapshot and when it arrived, or null if none has.
 * @param nowElapsedMs the watch's current [android.os.SystemClock.elapsedRealtime].
 * @param lastCommandFailed whether the most recent command failed to send.
 * @param scrub a scrub in progress or recently committed, which overrides the extrapolated position.
 * @param local what the watch's own player is doing, or null when it is playing nothing.
 * @param stored the episodes on the watch.
 * @param offered what the phone has published as available to copy.
 * @param transfers copies currently arriving.
 */
@Suppress("LongParameterList")
internal fun watchPlayerUiState(
    link: PhoneLink,
    received: ReceivedSnapshot?,
    nowElapsedMs: Long,
    lastCommandFailed: Boolean = false,
    scrub: ScrubState? = null,
    local: WatchPlaybackState? = null,
    stored: List<StoredEpisode> = emptyList(),
    offered: List<OfflineEpisode> = emptyList(),
    transfers: Map<String, TransferProgress> = emptyMap(),
): WatchPlayerUiState {
    val phoneSnapshot = received?.snapshot ?: NowPlayingSnapshot()
    val sinceArrivalMs = if (received == null) 0L else nowElapsedMs - received.receivedAtElapsedMs

    // The skip intervals stay the phone's even for local playback: they are the user's preference,
    // set once on the phone, and a watch that jumped a different distance would be a second opinion
    // nobody asked for.
    val snapshot = local?.asSnapshot(
        skipForwardMs = phoneSnapshot.skipForwardMs,
        skipBackMs = phoneSnapshot.skipBackMs,
    ) ?: phoneSnapshot

    val shown = scrub?.positionMs?.takeIf { scrub.stillShowing(received, nowElapsedMs) }
    // Local playback reports a live position from the player itself, so there is nothing to
    // extrapolate; the phone's is a reading taken some time ago.
    val playing = local?.positionMs ?: phoneSnapshot.positionAfter(sinceArrivalMs)

    return WatchPlayerUiState(
        link = link,
        snapshot = snapshot,
        positionMs = shown ?: playing,
        progress = snapshot.progressAt(shown ?: playing),
        lastCommandFailed = lastCommandFailed,
        // Only an uncommitted scrub is "scrubbing": once the seek is away the user has let go, and
        // the held position is just covering the round trip.
        isScrubbing = scrub != null && scrub.committedAtElapsedMs == null,
        source = if (local != null) PlaybackSource.WATCH else PlaybackSource.PHONE,
        stored = stored,
        offered = offered,
        transfers = transfers,
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
