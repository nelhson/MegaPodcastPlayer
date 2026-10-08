package md.borisveriga.megapodcastplayer.feature.downloads

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.model.DownloadFolders
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.EpisodeWithShow
import md.borisveriga.megapodcastplayer.core.model.FolderView
import md.borisveriga.megapodcastplayer.core.model.countByFolder
import md.borisveriga.megapodcastplayer.core.model.groupIntoSections
import md.borisveriga.megapodcastplayer.core.model.inFolder
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Tests for the folder half of [DownloadsScreen]: the folder chip, the *Move* action and its sheet,
 * the empty-folder state, and the delete-folder question.
 *
 * Apart from `DownloadsScreenTest` because they need a screen with folders in it, and because a
 * folder is a separate idea from the download states that file is about.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class DownloadsFolderScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** One folder, "Commute", holding episode "a". */
    private val commute = DownloadFolders.NONE.created(id = "f1", name = "Commute").moved(listOf("a"), "f1")

    private fun download(id: String) = EpisodeWithShow(
        episode = Episode(
            id = id,
            podcastId = "podcast-1",
            guid = "guid-$id",
            title = "Episode $id",
            description = "",
            audioUrl = "https://cdn.example.com/$id.mp3",
            artworkUrl = null,
            durationMs = 60_000L,
            publishedAt = Instant.parse("2026-08-24T06:00:00Z"),
            sizeBytes = 90_000_000L,
            downloadState = DownloadState.COMPLETED,
            downloadedBytes = 90_000_000L,
            downloadPercent = 100f,
        ),
        showTitle = "Podlodka Podcast",
        showArtworkUrl = null,
    )

    /** Renders the screen over [downloads] filed as [folders] say, with [folderView] on screen. */
    private fun setScreen(
        downloads: List<EpisodeWithShow>,
        folders: DownloadFolders = DownloadFolders.NONE,
        folderView: FolderView = FolderView.AllFolders,
        folderActions: DownloadFolderActions = DownloadFolderActions(),
    ) {
        // Narrowed by the same functions the view model uses, so the test cannot agree with a
        // screen that disagrees with the app.
        val inView = downloads.inFolder(folderView, folders)
        composeRule.setContent {
            MegaPodcastPlayerTheme {
                DownloadsScreen(
                    uiState = DownloadsUiState(
                        downloads = downloads,
                        sections = inView.groupIntoSections(),
                        completedCount = downloads.size,
                        totalBytes = 90_000_000L,
                        freeBytes = 4_000_000_000L,
                        folders = folders,
                        folderView = folderView,
                        folderCounts = downloads.countByFolder(folders),
                        isFolderEmpty = inView.isEmpty(),
                        isLoading = false,
                    ),
                    onEpisodeClick = {},
                    onEpisodeRetry = {},
                    onEpisodeDownloadNow = {},
                    onEpisodeRemove = {},
                    onEpisodeQueue = {},
                    onMove = { _, _, _ -> },
                    onRefresh = {},
                    onBrowseLibrary = {},
                    onMessageShown = {},
                    onOpenSettings = {},
                    onExportList = {},
                    scrollToTopSignal = 0,
                    folderActions = folderActions,
                )
            }
        }
    }

    @Test
    fun `the folder chip lists the folders and shows the one chosen`() {
        var shown: FolderView? = null
        setScreen(
            downloads = listOf(download("a"), download("b")),
            folders = commute,
            folderActions = DownloadFolderActions(onShowFolder = { shown = it }),
        )

        composeRule.onNodeWithContentDescription("Choose a folder").performClick()
        composeRule.onNodeWithText("Commute").performClick()

        assertEquals(FolderView.Folder("f1"), shown)
    }

    @Test
    fun `a row names its folder while every folder is shown`() {
        setScreen(downloads = listOf(download("a")), folders = commute)

        composeRule.onNodeWithText("Commute", substring = true).assertExists()
    }

    @Test
    fun `inside a folder only its downloads are listed`() {
        setScreen(
            downloads = listOf(download("a"), download("b")),
            folders = commute,
            folderView = FolderView.Folder("f1"),
        )

        composeRule.onNodeWithText("Episode a").assertExists()
        composeRule.onNodeWithText("Episode b").assertDoesNotExist()
    }

    @Test
    fun `an empty folder says so and offers every folder back`() {
        var shown: FolderView? = null
        setScreen(
            downloads = listOf(download("b")),
            folders = DownloadFolders.NONE.created(id = "f1", name = "Commute"),
            folderView = FolderView.Folder("f1"),
            folderActions = DownloadFolderActions(onShowFolder = { shown = it }),
        )

        composeRule.onNodeWithText("Nothing in Commute").assertIsDisplayed()
        composeRule.onNodeWithText("No downloads match this filter").assertDoesNotExist()
        composeRule.onNodeWithText("Show all folders").performClick()

        assertEquals(FolderView.AllFolders, shown)
    }

    @Test
    fun `the move action files a download under the folder picked`() {
        var moved: Pair<String, String?>? = null
        setScreen(
            downloads = listOf(download("b")),
            folders = commute,
            folderActions = DownloadFolderActions(onMove = { id, folder -> moved = id to folder }),
        )

        composeRule.onNodeWithText("Episode b").performCustomAccessibilityActionWithLabel("Move")
        composeRule.onNodeWithText("Commute").performClick()

        assertEquals("b" to "f1", moved)
    }

    @Test
    fun `deleting a folder keeps its downloads unless the box is ticked`() {
        var confirmed: Boolean? = null
        composeRule.setContent {
            MegaPodcastPlayerTheme {
                DeleteFolderDialog(
                    name = "Commute",
                    count = 2,
                    freed = "180 MB",
                    onConfirm = { confirmed = it },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("Its 2 downloads move to Downloads.").assertIsDisplayed()
        composeRule.onNodeWithText("Delete folder").assertExists()
        composeRule.onNodeWithText("Also delete its 2 downloads, freeing 180 MB").performClick()
        composeRule.onNodeWithText("Delete folder and downloads").performClick()

        assertEquals(true, confirmed)
    }
}

/*
 * The folder-name dialog is deliberately not driven from here, for the reason `NoteDialog` is not
 * driven from the moments tests: a text field in a dialog never lets Robolectric go idle, and the
 * test times out after a minute without asserting anything. What the dialog decides — which names
 * it refuses — is `folderNameProblem`, tested in `FolderNameProblemTest` without a composition;
 * what a confirmed name does is covered by `DownloadsViewModelTest`.
 */
