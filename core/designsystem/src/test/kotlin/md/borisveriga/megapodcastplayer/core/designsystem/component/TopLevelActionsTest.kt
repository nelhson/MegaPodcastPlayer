package md.borisveriga.megapodcastplayer.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Tests for the two things every top-level destination shares.
 *
 * [ScrollToTopEffect] is the one worth testing rather than reading, because it has a guard that is
 * invisible in the happy case and load-bearing in the other: it must scroll when the signal changes
 * *while the screen is up*, and must not scroll when it merely arrives holding a signal — which is
 * what a tab returned to does, carrying a scroll position the navigation library saved on the way
 * out.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TopLevelActionsTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** A list long enough that scrolling it is visible in what is composed. */
    private val rows = (0 until 60).map { "Row $it" }

    @Test
    fun `the gear reports a tap and says what it is`() {
        var opened = false
        composeRule.setContent {
            MegaPodcastPlayerTheme { SettingsAction(onClick = { opened = true }) }
        }

        composeRule.onNodeWithContentDescription("Settings").performClick()

        assertTrue(opened)
    }

    @Test
    fun `a signal that changes while the screen is up scrolls the list back to the top`() {
        var signal by mutableIntStateOf(0)
        lateinit var index: () -> Int
        composeRule.setContent {
            MegaPodcastPlayerTheme {
                // Started part-way down rather than scrolled there by the test: what is under test
                // is the effect, and driving a real fling would only be testing Compose's own list.
                val state = rememberLazyListState(initialFirstVisibleItemIndex = 30)
                index = { state.firstVisibleItemIndex }
                ScrollToTopEffect(signal = signal, state = state)
                LazyColumn(state = state) {
                    items(rows) { row ->
                        Box(modifier = Modifier.fillMaxWidth().height(48.dp)) {
                            Text(text = row)
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals(30, index())

        signal += 1
        composeRule.waitForIdle()

        assertEquals(0, index())
    }

    /**
     * The guard. Every `LaunchedEffect` runs once when it enters the composition, so without a
     * remembered first value a tab returned to would be scrolled to the top for no reason — losing
     * exactly the position the navigation library had gone to the trouble of saving.
     */
    @Test
    fun `arriving already holding a signal scrolls nothing`() {
        lateinit var index: () -> Int
        composeRule.setContent {
            MegaPodcastPlayerTheme {
                // 7 stands for "this tab has been re-tapped seven times since the app started",
                // which is what a screen composed later in a session actually arrives holding.
                val state = rememberLazyListState(initialFirstVisibleItemIndex = 30)
                index = { state.firstVisibleItemIndex }
                ScrollToTopEffect(signal = 7, state = state)
                LazyColumn(state = state) {
                    items(rows) { row ->
                        Box(modifier = Modifier.fillMaxWidth().height(48.dp)) {
                            Text(text = row)
                        }
                    }
                }
            }
        }

        composeRule.waitForIdle()

        assertEquals(30, index())
    }
}
