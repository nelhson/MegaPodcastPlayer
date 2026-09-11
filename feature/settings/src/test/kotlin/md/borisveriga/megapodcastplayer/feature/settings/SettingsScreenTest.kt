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
        onExportSubscriptions: () -> Unit = {},
        onImportSubscriptions: () -> Unit = {},
        onConfirmRestore: () -> Unit = {},
        onCancelRestore: () -> Unit = {},
        onRestoreResultShown: () -> Unit = {},
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
                    onExportSubscriptions = onExportSubscriptions,
                    onImportSubscriptions = onImportSubscriptions,
                    onConfirmRestore = onConfirmRestore,
                    onCancelRestore = onCancelRestore,
                    onRestoreResultShown = onRestoreResultShown,
                    onMessageShown = {},
                )
            }
        }
    }

    /**
     * Brings a row of the subscriptions section into view; it sits below two full cards of
     * settings.
     *
     * `performScrollTo` walks up to the scrolling ancestor on its own. Swiping instead would have
     * to pick one of five scrollable nodes, four of which are the horizontal choice rows.
     */
    private fun scrollToText(text: String) {
        composeRule.onNodeWithText(text).performScrollTo()
    }

    /**
     * SET-6. The row used to be *the* speed; it is now the speed of every show that has not said
     * otherwise, and the shows that have are named so that an unexpected rate has an explanation
     * on the screen the rate is set on.
     */
    @Test
    fun `the playback rate is named as a default, and its exceptions are listed`() {
        setContent(
            SettingsUiState(
                speedOverrides = listOf(
                    ShowSpeedOverride("Acquired", 2f),
                    ShowSpeedOverride("Zeitgeist", 1.5f),
                ),
            ),
        )

        composeRule.onNodeWithText("Default speed").assertExists()
        composeRule
            .onNodeWithText("Some shows play at their own speed: Acquired at 2x, Zeitgeist at 1.5x")
            .assertExists()
    }

    /** A row saying "no shows override this" would explain a feature rather than report a fact. */
    @Test
    fun `nothing is said about overrides when there are none`() {
        setContent(SettingsUiState())

        composeRule.onNodeWithText("Some shows play at their own speed:", substring = true)
            .assertDoesNotExist()
    }

    /** SET-4. An app with a crash reporter on its classpath should say so where it can be read. */
    @Test
    fun `about says whether anything is reported`() {
        setContent(SettingsUiState(isCrashReporting = true))

        scrollToText("Crash reporting")
        composeRule
            .onNodeWithText(
                "On. Crashes and handled failures are sent to Firebase Crashlytics.",
            )
            .assertExists()
    }

    @Test
    fun `about says when nothing is reported`() {
        setContent(SettingsUiState(isCrashReporting = false))

        scrollToText("Crash reporting")
        composeRule
            .onNodeWithText(
                "Off. This build has no crash reporting configured, so nothing leaves the device.",
            )
            .assertExists()
    }

    /**
     * The OFL requires the licence to travel with the fonts, and for a user that means the APK.
     * Until this it lived only in `docs/`, which satisfied nobody who had not cloned the project.
     */
    @Test
    fun `the bundled fonts' licences can be read in the app`() {
        setContent(SettingsUiState())

        scrollToText("Font licences")
        composeRule.onNodeWithText("Font licences").performClick()

        composeRule.onNodeWithText("SIL OPEN FONT LICENSE Version 1.1", substring = true)
            .assertExists()
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
    fun `warns that the subscriptions have never been exported`() {
        setContent(SettingsUiState())

        scrollToText("Not exported yet")

        composeRule.onNodeWithText("Not exported yet").assertIsDisplayed()
    }

    @Test
    fun `shows when the list was last exported`() {
        setContent(
            SettingsUiState(
                backup = BackupUiState(lastBackupAtMs = 1_789_084_800_000L),
            ),
        )

        scrollToText("Export subscriptions")

        composeRule.onNodeWithText("Last exported: ", substring = true).assertIsDisplayed()
    }

    @Test
    fun `tapping either row asks the caller to launch its picker`() {
        var exported = false
        var imported = false
        setContent(
            SettingsUiState(),
            onExportSubscriptions = { exported = true },
            onImportSubscriptions = { imported = true },
        )

        scrollToText("Export subscriptions")
        composeRule.onNodeWithText("Export subscriptions").performClick()
        scrollToText("Import subscriptions")
        composeRule.onNodeWithText("Import subscriptions").performClick()

        assertTrue(exported)
        assertTrue(imported)
    }

    @Test
    fun `a running import reports the show it is on and takes no taps`() {
        var launched = false
        setContent(
            uiState = SettingsUiState(
                backup = BackupUiState(
                    restore = RestoreRun.Running(RestoreProgress(1, 3, "Podlodka Podcast")),
                ),
            ),
            onExportSubscriptions = { launched = true },
        )

        scrollToText("Adding 2 of 3 — Podlodka Podcast")
        composeRule.onNodeWithText("Adding 2 of 3 — Podlodka Podcast").assertIsDisplayed()
        composeRule.onNodeWithText("Export subscriptions").performClick()

        // Both rows are disabled while an import runs; a second write would interleave two runs.
        assertFalse("the export row should not act during an import", launched)
    }

    /**
     * The confirmation says what an import will *not* do while there is still nothing to undo: it
     * carries links, so a show already in the library keeps its position and its downloads.
     */
    @Test
    fun `the confirmation says a show already in the library is left alone`() {
        var confirmed = false
        setContent(
            uiState = SettingsUiState(
                backup = BackupUiState(pendingRestore = PendingRestore(json = "{}", showCount = 3)),
            ),
            onConfirmRestore = { confirmed = true },
        )

        composeRule.onNodeWithText("left as it is", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Add", substring = false).performClick()

        assertTrue(confirmed)
    }

    @Test
    fun `a file whose rows were not all usable says how many were skipped`() {
        // Reported rather than folded into the total: a file whose extra rows were folders is
        // ordinary, and one whose rows were mostly refused is worth going back to.
        setContent(
            uiState = SettingsUiState(
                backup = BackupUiState(
                    pendingRestore = PendingRestore(
                        json = "{}",
                        showCount = 3,
                        skipped = 2,
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("2 entries were skipped", substring = true).assertIsDisplayed()
    }

    @Test
    fun `backing out of the confirmation starts nothing`() {
        var cancelled = false
        var confirmed = false
        setContent(
            uiState = SettingsUiState(
                backup = BackupUiState(pendingRestore = PendingRestore(json = "{}", showCount = 1)),
            ),
            onConfirmRestore = { confirmed = true },
            onCancelRestore = { cancelled = true },
        )

        composeRule.onNodeWithText("Cancel").performClick()

        assertTrue(cancelled)
        assertFalse("nothing may be fetched when the user backs out", confirmed)
    }

    @Test
    fun `a finished import names the feeds it could not fetch`() {
        setContent(
            SettingsUiState(
                backup = BackupUiState(
                    restore = RestoreRun.Finished(
                        id = "run-1",
                        summary = RestoreSummary(
                            showsRestored = 2,
                            failedTitles = listOf("Dead Feed"),
                        ),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("2 shows added.").assertIsDisplayed()
        composeRule.onNodeWithText("Dead Feed", substring = true).assertIsDisplayed()
    }

    /**
     * Dismissing the report has to be told to somebody who outlives this screen: the run behind it
     * is retained and replayed for days, and a summary the screen forgot about is a dialog that
     * greets the user on every visit to settings.
     */
    @Test
    fun `dismissing the import report reports it as shown`() {
        var shown = false
        setContent(
            SettingsUiState(
                backup = BackupUiState(
                    restore = RestoreRun.Finished("run-1", RestoreSummary(showsRestored = 2)),
                ),
            ),
            onRestoreResultShown = { shown = true },
        )

        composeRule.onNodeWithText("Done").performClick()

        assertTrue(shown)
    }
}
