package md.borisveriga.megapodcastplayer.navigation

import android.app.Application
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Tests for [ReturnToListOnReTapEffect]: when a re-tap of the Library tab closes the show.
 *
 * The effect is driven with plain state rather than through `LibraryListDetail`, whose panes are
 * Hilt screens; what is under test is the rule, and the rule is three facts — the tap was a
 * re-tap, it happened after arrival, and the list was hidden.
 */
@RunWith(AndroidJUnit4::class)
// The real application binds a Media3 controller on start-up; nothing here needs it.
@Config(sdk = [34], application = Application::class)
class ReturnToListOnReTapEffectTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val signal = mutableIntStateOf(0)
    private val isListPaneVisible = mutableStateOf(false)
    private var returns = 0

    /**
     * Composes the effect over the fixture's state.
     *
     * @param listVisible whether the list pane is on screen to begin with.
     */
    private fun setUp(listVisible: Boolean) {
        isListPaneVisible.value = listVisible
        composeTestRule.setContent {
            ReturnToListOnReTapEffect(
                signal = signal.intValue,
                isListPaneVisible = isListPaneVisible.value,
                onReturnToList = { returns++ },
            )
        }
    }

    /** Re-taps the tab and lets the effect run. */
    private fun reTap() {
        composeTestRule.runOnIdle { signal.intValue++ }
        composeTestRule.waitForIdle()
    }

    /** A folded phone with a show in front: the re-tap is the way back to the library. */
    @Test
    fun `a re-tap over a full-screen show returns to the list`() {
        setUp(listVisible = false)

        reTap()

        assertEquals(1, returns)
    }

    /** Arriving on the tab with a show open is not a re-tap, and must not close the show. */
    @Test
    fun `arriving does not close the show`() {
        signal.intValue = 3
        setUp(listVisible = false)
        composeTestRule.waitForIdle()

        assertEquals(0, returns)
    }

    /** Opened out, the list is already beside the show; the re-tap scrolls it and closes nothing. */
    @Test
    fun `a re-tap with the list on screen leaves the show alone`() {
        setUp(listVisible = true)

        reTap()

        assertEquals(0, returns)
    }

    /** The pane closing or opening by itself is not a re-tap either. */
    @Test
    fun `the list appearing or going does not count as a re-tap`() {
        setUp(listVisible = true)

        composeTestRule.runOnIdle { isListPaneVisible.value = false }
        composeTestRule.waitForIdle()

        assertEquals(0, returns)
    }

    /** Every re-tap over a show is honoured, not just the first. */
    @Test
    fun `each re-tap over a show returns again`() {
        setUp(listVisible = false)

        reTap()
        reTap()

        assertEquals(2, returns)
    }
}
