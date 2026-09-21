package md.borisveriga.megapodcastplayer.feature.player

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
 * are pinned in particular. The lengths of time run to four hours and arm exactly what their caption
 * says; and *End of chapter* means the chapter playing until another is chosen, after which it
 * means that one — a second tap on the chip must not quietly move the stop back.
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
    fun `the lengths of time run from a quarter of an hour to four hours`() {
        showOptions()

        composeRule.onNodeWithContentDescription(DURATION_MENU).performClick()

        composeRule.onNodeWithText("15 min").assertIsDisplayed()
        composeRule.onNodeWithText("1 h").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("4 h").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("4 h 15 min").assertDoesNotExist()
    }

    @Test
    fun `a length of time arms exactly that and closes the sheet`() {
        showOptions()

        composeRule.onNodeWithContentDescription(DURATION_MENU).performClick()
        composeRule.onNodeWithText("1 h 30 min").performScrollTo().performClick()

        assertEquals(listOf(90 * MINUTE_MS), armedAfter)
        assertEquals(1, dismissals)
    }

    @Test
    fun `a running countdown is what the chip says`() {
        showOptions(state = SleepTimerState(remainingMs = 18 * MINUTE_MS))

        composeRule.onNodeWithText("18 min left").assertIsDisplayed()
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
        const val DURATION_MENU = "Choose how long to keep playing"
        const val CHAPTER_MENU = "Choose the chapter to stop after"

        /** The second chapter is playing; the first is behind the playhead and not on offer. */
        val CHAPTERS = listOf(
            SleepChapterOption(index = 1, title = "The interview"),
            SleepChapterOption(index = 2, title = "Listener mail"),
        )
    }
}
