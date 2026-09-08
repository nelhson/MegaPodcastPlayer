package md.borisveriga.megapodcastplayer.core.media

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.VisibleForTesting
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import com.google.common.collect.ImmutableList
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings

/**
 * What the media notification, the lock screen and the Now Bar put in their button slots.
 *
 * Media3's default is built for music: previous track, play/pause, next track. For a podcast that
 * is the wrong three. The controls a listener reaches for on a lock screen — walking, driving, half
 * asleep — are *skip back* to replay a sentence and *skip forward* to get past an ad, and neither
 * of them was there. Worse, with a queue of one the "previous"/"next" pair collapsed to a lone
 * rewind glyph beside an empty slot.
 *
 * So the app states its preferences rather than accepting the default:
 *
 *  - **compact** (shade collapsed, lock screen, Now Bar): skip back · play/pause · skip forward,
 *    the play/pause button being Media3's own and always occupying the central slot;
 *  - **expanded**: the same three, with previous and next episode either side of them.
 *
 * Every button is bound to a `Player` command rather than a custom session command, so the session
 * enables and disables them from what the player can actually do. That is what makes *next* vanish
 * at the end of the queue instead of sitting there greyed out, and it is why nothing here has to
 * know what is queued.
 *
 * The glyphs carry the number: Media3 ships 5, 10, 15 and 30-second variants, so a configured
 * interval that matches one of them is drawn with its number and anything else — 45 or 60 seconds —
 * falls back to the plain glyph. That is the same rule [md.borisveriga.megapodcastplayer.core.media]'s
 * callers apply in the app's own player, and for the same reason: a button that says 30 and jumps
 * 45 is a small lie the user notices the first time they use it.
 */

/**
 * Builds the button preferences for a pair of skip intervals.
 *
 * @param context supplies the spoken and displayed labels.
 * @param settings the user's configured skip distances; only those two fields are read.
 * @return the buttons, in the order Media3 resolves slots from.
 */
@UnstableApi
fun mediaButtonPreferences(
    context: Context,
    settings: PlaybackSettings,
): ImmutableList<CommandButton> = ImmutableList.of(
    CommandButton.Builder(skipBackIconConstant(settings.skipBackMs))
        .setPlayerCommand(Player.COMMAND_SEEK_BACK)
        .setDisplayName(context.skipLabel(R.plurals.playback_action_skip_back, settings.skipBackMs))
        // The overflow fallback matters on surfaces with fewer slots than this list has buttons —
        // Android Auto, and a compact Now Bar. Without it a button with nowhere to go is dropped.
        .setSlots(CommandButton.SLOT_BACK, CommandButton.SLOT_OVERFLOW)
        .build(),
    CommandButton.Builder(skipForwardIconConstant(settings.skipForwardMs))
        .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
        .setDisplayName(
            context.skipLabel(R.plurals.playback_action_skip_forward, settings.skipForwardMs),
        )
        .setSlots(CommandButton.SLOT_FORWARD, CommandButton.SLOT_OVERFLOW)
        .build(),
    CommandButton.Builder(CommandButton.ICON_PREVIOUS)
        .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
        .setDisplayName(context.getString(R.string.playback_action_previous))
        .setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW)
        .build(),
    CommandButton.Builder(CommandButton.ICON_NEXT)
        .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
        .setDisplayName(context.getString(R.string.playback_action_next))
        .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
        .build(),
)

/**
 * The Media3 icon constant for a skip-back distance.
 *
 * @param skipMs the configured distance in milliseconds.
 * @return a numbered glyph where one exists, otherwise the plain one.
 */
@VisibleForTesting
@UnstableApi
internal fun skipBackIconConstant(skipMs: Long): Int = when (skipMs) {
    SKIP_5_MS -> CommandButton.ICON_SKIP_BACK_5
    SKIP_10_MS -> CommandButton.ICON_SKIP_BACK_10
    SKIP_15_MS -> CommandButton.ICON_SKIP_BACK_15
    SKIP_30_MS -> CommandButton.ICON_SKIP_BACK_30
    else -> CommandButton.ICON_SKIP_BACK
}

/**
 * The Media3 icon constant for a skip-forward distance; see [skipBackIconConstant].
 *
 * @param skipMs the configured distance in milliseconds.
 * @return a numbered glyph where one exists, otherwise the plain one.
 */
@VisibleForTesting
@UnstableApi
internal fun skipForwardIconConstant(skipMs: Long): Int = when (skipMs) {
    SKIP_5_MS -> CommandButton.ICON_SKIP_FORWARD_5
    SKIP_10_MS -> CommandButton.ICON_SKIP_FORWARD_10
    SKIP_15_MS -> CommandButton.ICON_SKIP_FORWARD_15
    SKIP_30_MS -> CommandButton.ICON_SKIP_FORWARD_30
    else -> CommandButton.ICON_SKIP_FORWARD
}

/**
 * A skip button's label, pluralised on whole seconds.
 *
 * The glyph carries the number visually; a notification button's display name is what TalkBack and
 * Android Auto read out, so it has to say the number too.
 *
 * @param plural the quantity string to resolve.
 * @param skipMs the configured distance in milliseconds.
 */
private fun Context.skipLabel(@PluralsRes plural: Int, skipMs: Long): String {
    // At least one, so a nonsensical setting still reads as a number rather than "0 seconds".
    val seconds = (skipMs / MILLIS_PER_SECOND).coerceAtLeast(1L).toInt()
    return resources.getQuantityString(plural, seconds, seconds)
}

private const val MILLIS_PER_SECOND = 1_000L

private const val SKIP_5_MS = 5_000L
private const val SKIP_10_MS = 10_000L
private const val SKIP_15_MS = 15_000L
private const val SKIP_30_MS = 30_000L
