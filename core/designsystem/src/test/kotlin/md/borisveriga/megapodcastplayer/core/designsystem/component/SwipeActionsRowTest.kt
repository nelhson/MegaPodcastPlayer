package md.borisveriga.megapodcastplayer.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Layout and gesture tests for [SwipeActionsRow], driven inside a `LazyColumn`.
 *
 * Three things about the setup are load-bearing, and each of them corresponds to a bug that shipped
 * because an earlier version of this file did not do it.
 *
 * The row is rendered **inside a lazy list**, because a lazy list measures its items with an
 * unbounded height — the one context where `fillMaxHeight` on the revealed buttons quietly does
 * nothing. Every caller is a lazy list.
 *
 * The row's content **paints no background of its own**, because `ShowRow` paints none either. A
 * test whose content filled itself with a colour was testing a row the library does not have, and
 * could not see the buttons showing through every row on screen.
 *
 * The committed action **writes to state the list reads**, so that a commit recomposes the item the
 * way a real one does, rather than quietly incrementing a captured `var`.
 *
 * What is deliberately *not* covered: the row once stranded itself at the far edge after a
 * committed swipe, because each drag delta launched a coroutine to reach `Animatable.snapTo` and one
 * of them could land after the release animation had started, cancelling it. That is a dispatcher
 * race, and Robolectric drains those coroutines in order before the release is ever processed — the
 * test below passes against the broken version too, with the clock held manually or not. The fix is
 * structural instead: drag deltas are written synchronously, so there is no second writer left to
 * race. Believe the absence of `launch` in the drag path, not this file.
 *
 * The release rule itself is a pure function with its own tests; see [SwipeActionsTest].
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SwipeActionsRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** The theme colours, captured from inside the composition so the assertions can name them. */
    private var surfaceColor: Color = Color.Unspecified
    private var markPlayedColor: Color = Color.Unspecified

    @Composable
    private fun markPlayedAction(onClick: () -> Unit) = SwipeAction(
        icon = Icons.Rounded.DoneAll,
        label = "Mark played",
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        onClick = onClick,
    )

    @Composable
    private fun removeAction(onClick: () -> Unit) = SwipeAction(
        icon = Icons.Rounded.Delete,
        label = "Remove",
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        onClick = onClick,
    )

    /**
     * A row with no background of its own — a stand-in for `ShowRow`.
     *
     * @param label the text the finders reach for.
     */
    @Composable
    private fun BareRow(label: String = "The row") {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ROW_HEIGHT),
        ) {
            Text(text = label)
        }
    }

    private fun setContent(
        onMarkPlayed: () -> Unit = {},
        onFullSwipe: () -> Unit = {},
        withFullSwipe: Boolean = true,
    ) {
        composeRule.setContent {
            MegaPodcastPlayerTheme {
                surfaceColor = MaterialTheme.colorScheme.surface
                markPlayedColor = MaterialTheme.colorScheme.secondaryContainer

                LazyColumn {
                    item {
                        SwipeActionsRow(
                            actions = listOf(markPlayedAction(onMarkPlayed)),
                            fullSwipeAction = removeAction(onFullSwipe).takeIf { withFullSwipe },
                            modifier = Modifier.testTag(ROW_TAG),
                        ) {
                            BareRow()
                        }
                    }
                }
            }
        }
    }

    /** Pulls the row leftwards by [distance] pixels, slowly enough not to read as a fling. */
    private fun swipeLeftBy(distance: Float) {
        composeRule.onNodeWithText("The row").performTouchInput {
            down(centerRight)
            repeat(SWIPE_STEPS) {
                moveBy(Offset(-distance / SWIPE_STEPS, 0f))
                advanceEventTime(SWIPE_STEP_MS)
            }
            up()
        }
    }

    @Test
    fun `a row at rest is opaque, so the actions behind it cannot be seen`() {
        setContent()

        // Sampled where the trailing button sits. `ShowRow` paints nothing, so without a container
        // of its own this component showed "Mark played" and "Remove" through every library row,
        // permanently, and the swipe stopped meaning anything.
        val pixels = composeRule.onNodeWithTag(ROW_TAG).captureToImage().toPixelMap()
        val overTheButton = pixels[pixels.width - EDGE_INSET_PX, pixels.height / 2]

        assertEquals(surfaceColor, overTheButton)
        assertNotEquals(markPlayedColor, overTheButton)
    }

    @Test
    fun `a revealed button is as tall as the row it sits behind`() {
        setContent()
        swipeLeftBy(SHORT_SWIPE_PX)

        // The reason the buttons are wrapped in a `matchParentSize` box. Without it the list's
        // unbounded height makes `fillMaxHeight` a no-op and this comes back as the height of an
        // icon over a label — a short coloured patch beside a taller row.
        val button = composeRule.onNodeWithText("Mark played").fetchSemanticsNode()
        assertEquals(ROW_HEIGHT.value, button.size.height / composeRule.density.density, 1f)
    }

    @Test
    fun `a short swipe rests open and the button it reveals is tappable`() {
        var marked = 0
        setContent(onMarkPlayed = { marked++ })

        swipeLeftBy(SHORT_SWIPE_PX)

        composeRule.onNodeWithText("Mark played").assertIsDisplayed()
        composeRule.onNodeWithText("Mark played").performClick()

        assertEquals(1, marked)
    }

    @Test
    fun `tapping a revealed button closes the row again`() {
        setContent()
        swipeLeftBy(SHORT_SWIPE_PX)

        composeRule.onNodeWithText("Mark played").performClick()

        composeRule.onNodeWithText("Mark played").assertDoesNotExist()
    }

    @Test
    fun `a full swipe commits and does not also fire the revealed button`() {
        var marked = 0
        var committed = 0
        setContent(onMarkPlayed = { marked++ }, onFullSwipe = { committed++ })

        swipeLeftBy(FULL_SWIPE_PX)

        assertEquals(1, committed)
        // The buttons are passed through on the way to the commit threshold. Firing one of them as
        // well would make the gesture do two things at once.
        assertEquals(0, marked)
    }

    @Test
    fun `a committed full swipe closes the row even though its action recomposes the list`() {
        // Queueing from the library is the case worth pinning: unlike a removal, the row stays on
        // screen afterwards, so a row that failed to spring back would sit there displaying the
        // action it had just performed. See the class doc on what this does and does not catch.
        composeRule.setContent {
            MegaPodcastPlayerTheme {
                var queued by remember { mutableStateOf(0) }

                LazyColumn {
                    item {
                        // Reading the count here is what makes the commit recompose the item.
                        Text(text = "Queued $queued")
                        SwipeActionsRow(
                            actions = listOf(markPlayedAction {}),
                            fullSwipeAction = SwipeAction(
                                icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                                label = "Queue next",
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                onClick = { queued++ },
                            ),
                            modifier = Modifier.testTag(ROW_TAG),
                        ) {
                            BareRow()
                        }
                    }
                }
            }
        }

        swipeLeftBy(FULL_SWIPE_PX)

        composeRule.onNodeWithText("Queued 1").assertIsDisplayed()
        // Shut again: a closed row keeps its buttons out of the merged semantics tree.
        composeRule.onNodeWithText("Mark played").assertDoesNotExist()
        composeRule.onNodeWithText("Queue next").assertDoesNotExist()
    }

    @Test
    fun `without a full-swipe action the row cannot be pulled far enough to commit`() {
        var marked = 0
        setContent(onMarkPlayed = { marked++ }, withFullSwipe = false)

        // The same gesture that commits above. With nothing to commit, the row's travel is capped
        // at the buttons and the pull simply leaves it open.
        swipeLeftBy(FULL_SWIPE_PX)

        composeRule.onNodeWithText("Mark played").assertIsDisplayed()
        assertEquals(0, marked)
    }

    private companion object {
        const val ROW_TAG = "swipe-row"

        /** Tall enough to be unmistakably different from an icon stacked over a label. */
        val ROW_HEIGHT = 96.dp

        /** How far in from the trailing edge the opacity check samples, in pixels. */
        const val EDGE_INSET_PX = 20

        /** How many steps a driven swipe is broken into, so it reads as a drag rather than a fling. */
        const val SWIPE_STEPS = 10

        /** Milliseconds between those steps; slow enough to stay under the fling velocity. */
        const val SWIPE_STEP_MS = 32L

        /**
         * Past half the revealed button, nowhere near half the row.
         *
         * Compose swallows a touch slop's worth of the first movement, so this has to clear that
         * floor too. Half of the 411dp xxhdpi row this class configures is around 616px.
         */
        const val SHORT_SWIPE_PX = 300f

        /** Comfortably past half the row's width, which is where a release commits. */
        const val FULL_SWIPE_PX = 1_000f
    }
}
