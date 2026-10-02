package md.borisveriga.megapodcastplayer.feature.player.video

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Tests for [KeepPictureEffect]: when the picture is asked for, and when it is handed back.
 *
 * The effect is the whole of the rule that lets a minimised video carry on in the collapsed bar, so
 * what is pinned is the rule: the picture follows *wanted* and the app being in front, and nothing
 * else — in particular not which of the screen and the bar happens to be drawing it, which the
 * effect is never told.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class KeepPictureEffectTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** A lifecycle the test moves by hand, standing in for the activity's. */
    private class HandLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private val owner = HandLifecycleOwner()
    private var wanted by mutableStateOf(false)

    /** Read by the content, so that changing it recomposes the effect's caller. */
    private var recomposed by mutableIntStateOf(0)
    private var entered = 0
    private var exited = 0

    /** The recomposition whose lambda was last called; what shows the effect holds the latest. */
    private var passOfLastCall = -1

    /**
     * Composes the effect under [owner], with the lifecycle already at [state].
     *
     * @param state where the lifecycle stands when the effect arrives.
     */
    private fun setContent(state: Lifecycle.State = Lifecycle.State.RESUMED) {
        owner.registry.currentState = state
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                // Lambdas that capture the pass they were made in, so each recomposition hands the
                // effect a new pair — as any caller whose callbacks close over state does.
                val pass = recomposed
                KeepPictureEffect(
                    wanted = wanted,
                    onEnter = {
                        entered++
                        passOfLastCall = pass
                    },
                    onExit = {
                        exited++
                        passOfLastCall = pass
                    },
                )
            }
        }
        composeRule.waitForIdle()
    }

    /**
     * Moves the lifecycle, as the activity going to the back or coming to the front would.
     *
     * @param state the state to move to.
     */
    private fun moveTo(state: Lifecycle.State) {
        composeRule.runOnIdle { owner.registry.currentState = state }
        composeRule.waitForIdle()
    }

    @Test
    fun `a picture nobody wants is neither asked for nor handed back`() {
        setContent()
        moveTo(Lifecycle.State.CREATED)
        moveTo(Lifecycle.State.RESUMED)

        // An audio player going to the back and coming forward again sends the service nothing.
        assertEquals(0, entered)
        assertEquals(0, exited)
    }

    @Test
    fun `the picture is asked for when it becomes wanted, and handed back when it stops being`() {
        setContent()

        wanted = true
        composeRule.waitForIdle()
        assertEquals(1, entered)
        assertEquals(0, exited)

        // Switched to audio, or moved on to an episode with nothing to show.
        wanted = false
        composeRule.waitForIdle()
        assertEquals(1, entered)
        assertEquals(1, exited)
    }

    @Test
    fun `the picture stays on while it is wanted, whatever is recomposed around it`() {
        wanted = true
        setContent()

        // What minimising the video screen is to this effect: a recomposition of its caller, with
        // new lambdas, and nothing else. The picture moves from the screen to the bar and the
        // effect, keyed on neither, neither hands back to sound nor asks again.
        recomposed++
        composeRule.waitForIdle()
        recomposed++
        composeRule.waitForIdle()

        assertEquals(1, entered)
        assertEquals(0, exited)

        // And when it does end, it is the caller's latest lambda that is told, not the one the
        // effect started with.
        wanted = false
        composeRule.waitForIdle()
        assertEquals(1, exited)
        assertEquals(2, passOfLastCall)
    }

    @Test
    fun `a rotation does not hand back to sound`() {
        wanted = true
        // Under the activity's own lifecycle this time, because the activity is what is recreated.
        composeRule.setContent {
            KeepPictureEffect(wanted = wanted, onEnter = { entered++ }, onExit = { exited++ })
        }
        composeRule.waitForIdle()
        assertEquals(1, entered)

        composeRule.activityRule.scenario.recreate()

        // The old activity stopped and was destroyed, and said it was changing configurations on
        // the way. The picture has to survive that: a hand-back here is a re-buffer per rotation.
        assertEquals(0, exited)
    }

    @Test
    fun `leaving the front hands back to sound, and coming back asks again`() {
        wanted = true
        setContent()

        moveTo(Lifecycle.State.CREATED)
        assertEquals(1, exited)

        moveTo(Lifecycle.State.RESUMED)
        assertEquals(2, entered)
        assertEquals(1, exited)
    }

    @Test
    fun `a picture wanted while the app is at the back waits for the front`() {
        wanted = true
        setContent(Lifecycle.State.CREATED)
        assertEquals(0, entered)

        moveTo(Lifecycle.State.STARTED)
        assertEquals(1, entered)
    }
}
