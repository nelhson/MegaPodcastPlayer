package md.borisveriga.megapodcastplayer.wear.complication

import md.borisveriga.megapodcastplayer.core.wearprotocol.NowPlayingSnapshot
import md.borisveriga.megapodcastplayer.wear.ui.formatCompactRemaining

/**
 * The words a complication needs, whatever shape the watch face asked for.
 *
 * A complication is a few characters in a corner of somebody else's design: the face decides how
 * much of this it draws, and every slot has to make sense on its own. So all of them are worked out
 * in one place from one snapshot, and the service just hands the right ones to the right builder.
 *
 * @property shortText a handful of characters — how much of the episode is left, or a dash.
 * @property longText a phrase: the episode title, or what is going on instead.
 * @property title the show, when there is one; the second line of a long-text complication.
 * @property description what TalkBack reads out, which is the only slot that gets the whole story.
 * @property progress fraction played, in `0f..1f`, for the ring a ranged-value complication draws.
 * @property isPlaying which glyph to show, and whether the ring means anything.
 */
internal data class ComplicationCopy(
    val shortText: String,
    val longText: String,
    val title: String?,
    val description: String,
    val progress: Float,
    val isPlaying: Boolean,
)

/**
 * The strings a complication draws, resolved from this module's resources.
 *
 * @property nothingPlaying shown when the phone has nothing loaded.
 * @property empty the short-text stand-in for the same state, sized for a corner of a watch face.
 * @property describeFormat template joining an episode to its show for the spoken description.
 */
internal data class ComplicationStrings(
    val nothingPlaying: String,
    val empty: String,
    val describeFormat: String,
)

/**
 * Works out what the complication says.
 *
 * Kept a pure function so the wording can be tested without a watch face, a data source service or
 * Play Services in the loop.
 *
 * @param snapshot the phone's last published state, or null when it has never been heard from.
 * @param positionMs playback position now, extrapolated by
 *   [md.borisveriga.megapodcastplayer.wear.data.extrapolatedPositionMs].
 * @param strings the resolved copy.
 */
internal fun complicationCopy(
    snapshot: NowPlayingSnapshot?,
    positionMs: Long,
    strings: ComplicationStrings,
): ComplicationCopy {
    if (snapshot == null || snapshot.isIdle) {
        return ComplicationCopy(
            shortText = strings.empty,
            longText = strings.nothingPlaying,
            title = null,
            description = strings.nothingPlaying,
            progress = 0f,
            isPlaying = false,
        )
    }

    val duration = snapshot.knownDurationMs
    val remaining = duration?.let { (it - positionMs).coerceAtLeast(0L) }

    return ComplicationCopy(
        // The time left rather than the time played: on a watch face this number answers "can I
        // finish this before I get there", which is the only question worth four characters.
        shortText = remaining?.let(::formatCompactRemaining) ?: strings.empty,
        longText = snapshot.title,
        title = snapshot.showTitle.takeIf { it.isNotBlank() },
        description = describe(snapshot, strings),
        progress = duration?.let { (positionMs.toFloat() / it).coerceIn(0f, 1f) } ?: 0f,
        isPlaying = snapshot.isPlaying,
    )
}

/**
 * The spoken description: the episode, and the show it belongs to when it has one.
 *
 * @param snapshot the state being described.
 * @param strings the resolved copy.
 */
private fun describe(snapshot: NowPlayingSnapshot, strings: ComplicationStrings): String =
    if (snapshot.showTitle.isBlank()) {
        snapshot.title
    } else {
        strings.describeFormat.format(snapshot.title, snapshot.showTitle)
    }
