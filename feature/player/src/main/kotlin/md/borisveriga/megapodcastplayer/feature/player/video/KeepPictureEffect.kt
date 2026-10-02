package md.borisveriga.megapodcastplayer.feature.player.video

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.LifecycleStartEffect

/**
 * Keeps the picture on for as long as it is [wanted] and the app is in front.
 *
 * The bracket the video screen used to keep for itself — show the picture on the way in, go back to
 * sound on the way out — moved to where both of the picture's homes are in view. The screen and the
 * collapsed bar take turns showing one picture, and a bracket kept by either would end it in the
 * hand-over: minimising the screen would stop the picture a moment before the bar asked for it
 * again, and that is a re-buffer in the middle of a sentence. Kept here, minimising changes nothing
 * at all, and the picture simply has somewhere else to be drawn.
 *
 * So the picture ends when it stops being wanted — the player was switched to audio, moved on to an
 * episode with nothing to show, or was put away — and when the app leaves the front: home, another
 * app, the screen going off. With nothing to draw on there is no picture worth streaming, and the
 * sound carries on. Coming back asks for it again.
 *
 * A rotation or a fold is neither. The activity is recreated, the effect leaves and returns, and the
 * picture is not touched in between.
 *
 * @param wanted whether the player is in video on an episode that has a picture.
 * @param onEnter asks for the picture; called when it becomes wanted, and on every return to the
 *   front while it is.
 * @param onExit hands back to sound.
 */
@Composable
internal fun KeepPictureEffect(wanted: Boolean, onEnter: () -> Unit, onExit: () -> Unit) {
    val activity = LocalActivity.current
    // The effect is keyed on what is wanted, not on who is asked; a caller passing fresh lambdas
    // each composition must not restart it, and the lambdas it calls must still be the latest.
    val enter by rememberUpdatedState(onEnter)
    val exit by rememberUpdatedState(onExit)

    LifecycleStartEffect(wanted) {
        if (wanted) enter()
        onStopOrDispose {
            if (wanted && activity?.isChangingConfigurations != true) exit()
        }
    }
}
