package md.borisveriga.megapodcastplayer.core.media

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Whether the user has asked to be told when the episode playing finishes.
 *
 * The bell exists for one situation: listening while falling asleep. Armed from the player, it turns
 * the end of an episode — which is otherwise silence, or the next episode starting — into something
 * that will wake the person who armed it.
 *
 * ## Why this is in memory and not a preference
 *
 * It is a one-shot for tonight, not a setting. [consume] clears it, so it fires once and the player
 * shows it disarmed afterwards; a value in DataStore would instead survive a reboot and ring at the
 * end of an episode played a week later. Losing the arming when the process dies is the right
 * failure: a phone that was killed mid-episode is not one that is still playing into a sleeping
 * user's ear.
 *
 * ## Why it is not keyed to an episode
 *
 * The bell means "wake me when this finishes". If the queue has moved on while the user was still
 * awake, the episode they meant is whatever is playing when they finally doze off, not the one they
 * had loaded when they tapped. Keying it to an id would arm a bell for an episode that has already
 * gone by, which is a bell that never rings.
 *
 * ## Why it lives in `:core:media`
 *
 * Both ends need it. The player's view model arms and disarms it, and [EndOfEpisodeBellListener],
 * installed on the service's own player, consumes it. `:core:media` is the lowest module both of
 * those can see.
 *
 * Safe to touch from any thread: the arming is one [MutableStateFlow] and [consume] is atomic.
 */
@Singleton
class EpisodeEndBell @Inject constructor() {

    private val armedState = MutableStateFlow(false)

    /** Whether the bell is currently armed; the player renders its button from this. */
    val armed: StateFlow<Boolean> = armedState.asStateFlow()

    /** Arms the bell, so the end of the episode playing rings it. */
    fun arm() {
        armedState.value = true
    }

    /** Disarms the bell, because the user changed their mind. */
    fun disarm() {
        armedState.value = false
    }

    /**
     * Reads and clears the arming in one step.
     *
     * Atomic rather than a read followed by a write because the two end-of-episode signals
     * [EndOfEpisodeBellListener] watches can both describe the same moment; the loser of that race
     * must get `false` and ring nothing.
     *
     * @return true when the bell was armed, and therefore should ring now.
     */
    fun consume(): Boolean = armedState.getAndUpdate { false }
}
