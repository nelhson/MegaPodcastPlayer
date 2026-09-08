package md.borisveriga.megapodcastplayer.feature.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import md.borisveriga.megapodcastplayer.core.data.backup.RestoreRun
import md.borisveriga.megapodcastplayer.core.data.repository.RestoreProgress
import md.borisveriga.megapodcastplayer.core.data.repository.RestoreSummary
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.model.AppearanceSettings
import md.borisveriga.megapodcastplayer.core.model.ThemeChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Tests for [SettingsScreen], focused on the backup section.
 *
 * The section carries the one warning in the app that has to arrive *before* the thing it warns
 * about: the database is recreated rather than migrated, so "no backup yet" has to be on screen
 * while the user can still act on it. That, the busy state, and the confirmation that a restore
 * replaces the queue are what is worth pinning here.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(
        uiState: SettingsUiState,
        onExportBackup: () -> Unit = {},
        onRestoreBackup: () -> Unit = {},
        onConfirmRestore: (Boolean) -> Unit = {},
        onCancelRestore: () -> Unit = {},
        onThemeChange: (ThemeChoice) -> Unit = {},
        onPureBlackChange: (Boolean) -> Unit = {},
        onOpenNotificationSettings: () -> Unit = {},
    ) {
        composeRule.setContent {
            MegaPodcastPlayerTheme {
                SettingsScreen(
                    uiState = uiState,
                    onBack = {},
                    onSpeedChange = {},
                    onSkipForwardChange = {},
                    onSkipBackChange = {},
                    onAutoPlayNextChange = {},
                    onAutoDownloadChange = {},
                    onUnmeteredOnlyChange = {},
                    onKeepLimitChange = {},
                    onDeleteAfterPlayingChange = {},
                    onThemeChange = onThemeChange,
                    onDynamicColorChange = {},
                    onPureBlackChange = onPureBlackChange,
                    onOpenNotificationSettings = onOpenNotificationSettings,
                    onRemoveAllDownloads = {},
                    onExportBackup = onExportBackup,
                    onRestoreBackup = onRestoreBackup,
                    onConfirmRestore = onConfirmRestore,
                    onCancelRestore = onCancelRestore,
                    onMessageShown = {},
                )
            }
        }
    }

    /**
     * Brings a row of the backup section into view; it sits below two full cards of settings.
     *
     * `performScrollTo` walks up to the scrolling ancestor on its own. Swiping instead would have
     * to pick one of five scrollable nodes, four of which are the horizontal choice rows.
     */
    private fun scrollToText(text: String) {
        composeRule.onNodeWithText(text).performScrollTo()
    }

    /**
     * The appearance section is the only one whose effect is visible while it is being chosen, and
     * the theme row is the reason the section exists at all.
     */
    @Test
    fun `the theme can be chosen, and the chosen one is the one reported`() {
        var chosen: ThemeChoice? = null
        setContent(SettingsUiState(), onThemeChange = { chosen = it })

        composeRule.onNodeWithText("Dark").performClick()

        assertEquals(ThemeChoice.DARK, chosen)
    }

    /**
     * A switch that visibly does nothing is worse than one that says why: in a light theme, pure
     * black has nothing to apply to, and the description is where that is admitted.
     */
    @Test
    fun `pure black says when it is not doing anything yet`() {
        setContent(SettingsUiState(appearance = AppearanceSettings(theme = ThemeChoice.LIGHT)))

        composeRule.onNodeWithText("True black backgrounds; takes effect when the app is dark")
            .assertIsDisplayed()
    }

    @Test
    fun `pure black describes what it does once the app is dark`() {
        setContent(SettingsUiState(appearance = AppearanceSettings(theme = ThemeChoice.DARK)))

        composeRule
            .onNodeWithText("True black backgrounds, which an OLED screen does not light at all")
            .assertIsDisplayed()
    }

    /**
     * Android owns every notification switch there is; what this row must do is get the user
     * there. The per-show half of the same question lives on the show's own page.
     */
    @Test
    fun `the notifications row hands over to the system`() {
        var opened = 0
        setContent(SettingsUiState(), onOpenNotificationSettings = { opened++ })

        composeRule.onNodeWithText("Notification settings").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `warns that no backup has ever been taken`() {
        setContent(SettingsUiState())

        scrollToText("No backup yet")

        composeRule.onNodeWithText("No backup yet").assertIsDisplayed()
    }

    @Test
    fun `shows when the last backup was taken`() {
        setContent(
            SettingsUiState(
                backup = BackupUiState(lastBackupAtMs = 1_789_084_800_000L),
            ),
        )

        scrollToText("Export library")

        composeRule.onNodeWithText("Last backup: ", substring = true).assertIsDisplayed()
    }

    @Test
    fun `tapping export asks the caller to launch the picker`() {
        var launched = false
        setContent(SettingsUiState(), onExportBackup = { launched = true })

        scrollToText("Export library")
        composeRule.onNodeWithText("Export library").performClick()

        assertTrue(launched)
    }

    @Test
    fun `a running restore reports the show it is on and takes no taps`() {
        var launched = false
        setContent(
            uiState = SettingsUiState(
                backup = BackupUiState(
                    restore = RestoreRun.Running(RestoreProgress(1, 3, "Podlodka Podcast")),
                ),
            ),
            onExportBackup = { launched = true },
        )

        scrollToText("Restoring 2 of 3 — Podlodka Podcast")
        composeRule.onNodeWithText("Restoring 2 of 3 — Podlodka Podcast").assertIsDisplayed()
        composeRule.onNodeWithText("Export library").performClick()

        // Both rows are disabled while a restore runs; a second write would interleave two runs.
        assertFalse("the export row should not act during a restore", launched)
    }

    @Test
    fun `the confirmation says the queue will be replaced and defaults re-download off`() {
        var confirmedWith: Boolean? = null
        setContent(
            uiState = SettingsUiState(
                backup = BackupUiState(pendingRestore = PendingRestore(json = "{}", showCount = 2)),
            ),
            onConfirmRestore = { confirmedWith = it },
        )

        composeRule.onNodeWithText("play queue will be replaced", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Restore", substring = false).performClick()

        assertEquals(false, confirmedWith)
    }

    @Test
    fun `backing out of the confirmation starts nothing`() {
        var cancelled = false
        var confirmedWith: Boolean? = null
        setContent(
            uiState = SettingsUiState(
                backup = BackupUiState(pendingRestore = PendingRestore(json = "{}", showCount = 1)),
            ),
            onConfirmRestore = { confirmedWith = it },
            onCancelRestore = { cancelled = true },
        )

        composeRule.onNodeWithText("Cancel").performClick()

        assertTrue(cancelled)
        assertNull(confirmedWith)
    }

    @Test
    fun `a finished restore names the feeds it could not fetch`() {
        setContent(
            SettingsUiState(
                backup = BackupUiState(
                    restore = RestoreRun.Finished(
                        RestoreSummary(
                            showsRestored = 2,
                            episodesRestored = 9,
                            failedTitles = listOf("Dead Feed"),
                        ),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("Dead Feed", substring = true).assertIsDisplayed()
    }
}
