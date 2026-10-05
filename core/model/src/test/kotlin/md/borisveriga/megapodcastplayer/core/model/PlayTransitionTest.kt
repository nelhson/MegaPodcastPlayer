package md.borisveriga.megapodcastplayer.core.model

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The whole of [playTransition], one row per case.
 *
 * A table rather than a test per rule, because the bugs it replaces were disagreements *between*
 * rows: each control was right on its own terms and wrong beside the others. Read down the
 * `isLoaded = true` rows and none of them says [PlayerCommand.START] — that is the rule.
 *
 * @property control the control pressed.
 * @property isLoaded whether the episode is the one the player holds.
 * @property expected what the press should do.
 */
@RunWith(Parameterized::class)
class PlayTransitionTest(
    private val control: PlayControl,
    private val isLoaded: Boolean,
    private val expected: PlayTransition,
) {

    @Test
    fun `a press does what the table says`() {
        assertEquals(expected, playTransition(control, isLoaded))
    }

    companion object {

        /** Every control, on an episode the player holds and on one it does not. */
        @JvmStatic
        @Parameterized.Parameters(name = "{0}, loaded={1}")
        fun cases(): List<Array<Any>> = listOf(
            // Another episode: started, and opened the way the control says.
            row(PlayControl.ROW_BUTTON, false, PlayerCommand.START, OpenPlayerAs.AUDIO),
            row(PlayControl.PLAY_AUDIO, false, PlayerCommand.START, OpenPlayerAs.AUDIO),
            row(PlayControl.PLAY_VIDEO, false, PlayerCommand.START, OpenPlayerAs.VIDEO),
            // The episode the player holds. The row button is its pause; it opens nothing.
            row(PlayControl.ROW_BUTTON, true, PlayerCommand.TOGGLE, null),
            // Playing as video, *Play audio* switches to audio; it used to pause.
            row(PlayControl.PLAY_AUDIO, true, PlayerCommand.CARRY_ON, OpenPlayerAs.AUDIO),
            // Playing as audio, *Play video* switches in place; it used to start the episode again.
            row(PlayControl.PLAY_VIDEO, true, PlayerCommand.CARRY_ON, OpenPlayerAs.VIDEO),
        )

        /** One row of the table, in the shape the runner wants. */
        private fun row(
            control: PlayControl,
            isLoaded: Boolean,
            command: PlayerCommand,
            openAs: OpenPlayerAs?,
        ): Array<Any> = arrayOf(control, isLoaded, PlayTransition(command, openAs))
    }
}
