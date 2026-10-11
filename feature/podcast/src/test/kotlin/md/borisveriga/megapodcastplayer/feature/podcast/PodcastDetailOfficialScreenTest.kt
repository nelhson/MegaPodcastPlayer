package md.borisveriga.megapodcastplayer.feature.podcast

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.Podcast
import md.borisveriga.megapodcastplayer.core.model.PodcastSource
import md.borisveriga.megapodcastplayer.core.model.YouTubeSource
import md.borisveriga.megapodcastplayer.core.model.youTubeAudioSentinel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The show page's rows under the official YouTube source.
 *
 * Beside [PodcastDetailScreenTest], which was already as long as a test class is allowed to be.
 * What is pinned is what the row no longer offers: the second play button, and the two swipes that
 * only the app's player can honour.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class PodcastDetailOfficialScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val podcast = Podcast(
        id = "podcast-1",
        itunesId = null,
        title = "Generic",
        author = "Boris Veriga",
        feedUrl = "https://www.youtube.com/feeds/videos.xml?playlist_id=PL1",
        artworkUrl = null,
        description = "",
        addedAt = Instant.EPOCH,
        lastRefreshAt = null,
        etag = null,
        lastModified = null,
        autoRefresh = true,
    )

    private fun episode(id: String, audioUrl: String) = Episode(
        id = id,
        podcastId = podcast.id,
        guid = "guid-$id",
        title = "Episode $id",
        description = "",
        audioUrl = audioUrl,
        artworkUrl = null,
        durationMs = 5_025_000L,
        publishedAt = Instant.parse("2026-08-24T06:00:00Z"),
        sizeBytes = null,
    )

    private fun setScreen(episodes: List<Episode>, source: PodcastSource) {
        composeRule.setContent {
            MegaPodcastPlayerTheme {
                PodcastDetailScreen(
                    uiState = PodcastDetailUiState(
                        podcast = podcast.copy(source = source),
                        episodes = episodes,
                        isLoading = false,
                        youTubeSource = YouTubeSource.OFFICIAL,
                    ),
                    onBack = {},
                    onEpisodeClick = {},
                    onEpisodePlay = {},
                    onEpisodePlayFrom = { _, _ -> },
                    onEpisodeWatch = {},
                    onEpisodeSheetDismiss = {},
                    onEpisodeDownloadToggle = {},
                    onEpisodeDownloadTo = { _, _ -> },
                    onEpisodeSwipeDownload = {},
                    onEpisodePlayNext = {},
                    onVideoQualitiesRequest = {},
                    onVideoDownload = { _, _, _ -> },
                    onVideoDownloadRemove = {},
                    onEpisodeMove = { _, _, _ -> },
                    onFilterChange = {},
                    onSortChange = {},
                    onShowSettingsChange = {},
                    onRefresh = {},
                    onRebuild = {},
                    onRemove = {},
                    onDownloadAndExport = { _, _ -> },
                    onMessageShown = {},
                )
            }
        }
    }

    /** A row that publishes at least one spoken action: the swipes, offered again to a screen reader. */
    private fun hasSpokenActions() = SemanticsMatcher("has spoken actions") { node ->
        node.config.getOrNull(SemanticsActions.CustomActions)?.isNotEmpty() == true
    }

    @Test
    fun `a youtube row has one play button and no swipe actions`() {
        setScreen(
            listOf(episode("a", youTubeAudioSentinel("niTJ2221aS8"))),
            source = PodcastSource.YOUTUBE,
        )

        // No second way to watch: the one play button is YouTube's own player.
        composeRule.onNodeWithContentDescription("Play video").assertDoesNotExist()
        // Neither the download swipe nor "play next" is published as an action on the row.
        composeRule.onNode(hasText("Episode a") and hasSpokenActions()).assertDoesNotExist()
    }

    @Test
    fun `an rss row keeps its swipes`() {
        setScreen(
            listOf(episode("a", "https://cdn.example.com/a.mp3")),
            source = PodcastSource.RSS,
        )

        composeRule.onNode(hasText("Episode a") and hasSpokenActions()).assertExists()
    }
}
