package md.borisveriga.megapodcastplayer.core.model

/**
 * What pressing a play control on an episode does: the table behind every such control.
 *
 * An episode has up to three of them — the play button on its row, and *Play audio* and *Play
 * video* on its sheet and row — and what each should do depends on one more thing: whether the
 * episode is the one the player already holds. Each control used to answer that for itself, and
 * the answers disagreed. *Play audio* on the episode playing as video paused it, because it shared
 * the row button's toggle; *Play video* on the episode playing as sound started it again from its
 * stored position, a re-buffer and a jump back of up to five seconds.
 *
 * The rule the table keeps: an episode the player already holds is never started again. Changing
 * how it is taken — sound or picture — changes the player's face and nothing about its playback.
 */

/** A control that plays an episode. */
enum class PlayControl {
    /** The play button on an episode's row: a toggle, and the mark of what is playing. */
    ROW_BUTTON,

    /** *Play audio*, and the single play button of an episode with no picture. */
    PLAY_AUDIO,

    /** *Play video*. */
    PLAY_VIDEO,
}

/** What a press asks of the player itself, apart from which face it is opened in. */
enum class PlayerCommand {
    /** Load the episode and play it from where it was left. */
    START,

    /** The episode is already loaded: leave it where it is, and make sure it is running. */
    CARRY_ON,

    /** The episode is already loaded: pause it if it is running, run it if it is paused. */
    TOGGLE,
}

/**
 * What one press does.
 *
 * @property command what is asked of the player.
 * @property openAs how the player is then opened, or null to leave it as it is shown.
 */
data class PlayTransition(val command: PlayerCommand, val openAs: OpenPlayerAs?)

/**
 * What pressing [control] on an episode does.
 *
 * @param control the control pressed.
 * @param isLoaded whether the episode is the one the player holds, playing or paused.
 * @return the command for the player and the face to open it in.
 */
fun playTransition(control: PlayControl, isLoaded: Boolean): PlayTransition = when (control) {
    PlayControl.ROW_BUTTON -> if (isLoaded) {
        // The player is already showing it, as a bar or a picture; a toggle opens nothing.
        PlayTransition(PlayerCommand.TOGGLE, openAs = null)
    } else {
        PlayTransition(PlayerCommand.START, OpenPlayerAs.AUDIO)
    }

    PlayControl.PLAY_AUDIO ->
        PlayTransition(if (isLoaded) PlayerCommand.CARRY_ON else PlayerCommand.START, OpenPlayerAs.AUDIO)

    PlayControl.PLAY_VIDEO ->
        PlayTransition(if (isLoaded) PlayerCommand.CARRY_ON else PlayerCommand.START, OpenPlayerAs.VIDEO)
}
