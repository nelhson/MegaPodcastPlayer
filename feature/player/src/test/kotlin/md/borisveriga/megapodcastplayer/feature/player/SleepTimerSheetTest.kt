package md.borisveriga.megapodcastplayer.feature.player

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.media.SleepTimerState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Tests for the sleep timer sheet's options.
 *
 * Rendered without the modal sheet around them: the container is the design system's and is tested
 * there, and what can go wrong here is which callback an option reaches and with what. Two things
 * are pinned in particular. The stepper arms exactly the length it shows, five minutes at a time
 * and never less than five; and *End of chapter* means the chapter playing until another is
 * chosen, after which it means that one — a second tap on the chip must not quietly move the stop
 * back.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SleepTimerSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val armedAfter = mutableListOf<Long>()
    private val armedChapters = mutableListOf<Int>()
    private var dismissals = 0

    /**
     * Shows the options.
     *
     * @param state what the timer is doing.
     * @param chapterOptions the chapters on offer.
     */
    private fun showOptions(
        state: SleepTimerState = SleepTimerState(),
        chapterOptions: List<SleepChapterOption> = emptyList(),
    ) {
        composeRule.setContent {
            MegaPodcastPlayerTheme {
                SleepTimerOptions(
                    state = state,
                    chapterOptions = chapterOptions,
                    onArmAfter = { armedAfter += it },
                    onArmEndOfEpisode = {},
                    onArmEndOfChapter = { armedChapters += it },
                    onCancel = {},
                    onDismiss = { dismissals++ },
                )
            }
        }
    }

    @Test
    fun `the stepper opens on half an hour and moves five minutes at a time`() {
        showOptions()

        composeRule.onNodeWithText("30 min").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(MORE).performClick()
        composeRule.onNodeWithText("35 min").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(LESS).performClick()
        composeRule.onNodeWithContentDescription(LESS).performClick()
        composeRule.onNodeWithText("25 min").assertIsDisplayed()
    }

    @Test
    fun `the stepper goes no lower than five minutes`() {
        showOptions()

        repeat(5) { composeRule.onNodeWithContentDescription(LESS).performClick() }

        composeRule.onNodeWithText("5 min").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(LESS).assertIsNotEnabled()
    }

    @Test
    fun `start arms exactly the length shown and closes the sheet`() {
        showOptions()

        repeat(3) { composeRule.onNodeWithContentDescription(MORE).performClick() }
        composeRule.onNodeWithText("45 min").assertIsDisplayed()
        composeRule.onNodeWithText("Start").performClick()

        assertEquals(listOf(45 * MINUTE_MS), armedAfter)
        assertEquals(1, dismissals)
    }

    @Test
    fun `a running countdown is what the caption says, and where the stepper opens`() {
        showOptions(state = SleepTimerState(remainingMs = 18 * MINUTE_MS))

        composeRule.onNodeWithText("18 min left").assertIsDisplayed()
        // Rounded up to the step, so Start restarts at about the same place and plus is five more.
        composeRule.onNodeWithText("20 min").assertIsDisplayed()
    }

    @Test
    fun `the stepper opens within its range whatever is left`() {
        assertEquals(30, startingMinutes(null))
        assertEquals(5, startingMinutes(1L))
        assertEquals(20, startingMinutes(18 * MINUTE_MS))
        assertEquals(20, startingMinutes(20 * MINUTE_MS))
        assertEquals(12 * 60, startingMinutes(24 * 60 * MINUTE_MS))
    }

    @Test
    fun `an episode without chapters has no end-of-chapter option`() {
        showOptions()

        composeRule.onNodeWithText("End of chapter").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(CHAPTER_MENU).assertDoesNotExist()
    }

    @Test
    fun `end of chapter means the chapter playing until another is chosen`() {
        showOptions(chapterOptions = CHAPTERS)

        composeRule.onNodeWithText("2. The interview").assertIsDisplayed()
        composeRule.onNodeWithText("End of chapter").assertIsNotSelected().performClick()

        assertEquals(listOf(1), armedChapters)
        assertEquals(1, dismissals)
    }

    @Test
    fun `a later chapter can be chosen from the menu`() {
        showOptions(chapterOptions = CHAPTERS)

        composeRule.onNodeWithContentDescription(CHAPTER_MENU).performClick()
        composeRule.onNodeWithText("3. Listener mail").performClick()

        assertEquals(listOf(2), armedChapters)
        assertEquals(1, dismissals)
    }

    @Test
    fun `an armed chapter is ticked, named, and what the chip arms again`() {
        showOptions(state = SleepTimerState(endOfChapterIndex = 2), chapterOptions = CHAPTERS)

        composeRule.onNodeWithText("3. Listener mail").assertIsDisplayed()
        composeRule.onNodeWithText("End of chapter").assertIsSelected().performClick()

        // Not back to the chapter playing: the stop stays where the user put it.
        assertEquals(listOf(2), armedChapters)
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val LESS = "Take off five minutes"
        const val MORE = "Add five minutes"
        const val CHAPTER_MENU = "Choose the chapter to stop after"

        /** The second chapter is playing; the first is behind the playhead and not on offer. */
        val CHAPTERS = listOf(
            SleepChapterOption(index = 1, title = "The interview"),
            SleepChapterOption(index = 2, title = "Listener mail"),
        )
    }
}
