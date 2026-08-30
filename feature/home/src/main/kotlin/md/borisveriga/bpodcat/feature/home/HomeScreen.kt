package md.borisveriga.bpodcat.feature.home

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import md.borisveriga.bpodcat.core.common.format.formatDuration
import md.borisveriga.bpodcat.core.common.format.formatPublishedDate
import md.borisveriga.bpodcat.core.common.format.formatRemaining
import md.borisveriga.bpodcat.core.designsystem.component.ArtworkSize
import md.borisveriga.bpodcat.core.designsystem.component.DownloadButton
import md.borisveriga.bpodcat.core.designsystem.component.EmptyState
import md.borisveriga.bpodcat.core.designsystem.component.EpisodeRow
import md.borisveriga.bpodcat.core.designsystem.component.LoadingState
import md.borisveriga.bpodcat.core.designsystem.component.PodcastArtwork
import md.borisveriga.bpodcat.core.designsystem.component.SectionHeader
import md.borisveriga.bpodcat.core.designsystem.theme.BPodcatTheme
import md.borisveriga.bpodcat.core.model.Episode
import md.borisveriga.bpodcat.core.model.EpisodeWithShow

/**
 * The Latest feed, wired to its view model.
 *
 * @param onEpisodePlaying invoked once playback has started, so the shell can open the player.
 * @param onAddPodcast opens search; the only route out of a genuinely empty feed.
 * @param onOpenSettings opens settings, which is a top-bar action now rather than a tab.
 * @param modifier layout modifier.
 * @param viewModel the screen's state holder.
 */
@Composable
fun HomeRoute(
    onEpisodePlaying: () -> Unit,
    onAddPodcast: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // The feed is the launch destination, so it inherits the library's job of checking for new
    // episodes on entry. Feeds fetched recently are skipped inside the repository.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshStale()
        onPauseOrDispose { }
    }

    HomeScreen(
        uiState = uiState,
        onEpisodeClick = { id -> viewModel.play(id, onEpisodePlaying) },
        onQueueClick = viewModel::addToQueue,
        onDownloadClick = viewModel::toggleDownload,
        onRefresh = viewModel::refresh,
        onAddPodcast = onAddPodcast,
        onOpenSettings = onOpenSettings,
        onMessageShown = viewModel::onMessageShown,
        modifier = modifier,
    )
}

/**
 * One chronological feed across every followed show.
 *
 * This screen exists because the app previously had no answer to "what is new" — a subscriber had
 * to open each show in turn to find out. Everything on it is a shared component, so the rows here
 * and the rows on downloads or a show's page are the same rows.
 *
 * @param uiState the feed.
 * @param onEpisodeClick plays an episode.
 * @param onQueueClick appends an episode to the queue.
 * @param onDownloadClick starts or removes an episode's offline copy.
 * @param onRefresh re-fetches every feed.
 * @param onAddPodcast opens search.
 * @param onOpenSettings opens settings.
 * @param onMessageShown clears the snackbar once shown.
 * @param modifier layout modifier.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onEpisodeClick: (String) -> Unit,
    onQueueClick: (String) -> Unit,
    onDownloadClick: (String) -> Unit,
    onRefresh: () -> Unit,
    onAddPodcast: () -> Unit,
    onOpenSettings: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message.toText(resources))
        onMessageShown()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.home_title),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                },
                actions = {
                    IconButton(onClick = onAddPodcast) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = stringResource(R.string.home_search),
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = stringResource(R.string.home_settings),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // A hairline rather than a spinner: this refresh is one the user did not ask for.
            if (uiState.isAutoRefreshing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            when {
                uiState.isLoading -> LoadingState(
                    contentDescription = stringResource(R.string.home_loading),
                )

                uiState.isEmpty -> EmptyState(
                    icon = Icons.Rounded.NewReleases,
                    title = stringResource(R.string.home_empty_title),
                    description = stringResource(R.string.home_empty_description),
                    actionLabel = stringResource(R.string.home_empty_action),
                    onAction = onAddPodcast,
                )

                else -> PullToRefreshBox(
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    FeedList(
                        uiState = uiState,
                        onEpisodeClick = onEpisodeClick,
                        onQueueClick = onQueueClick,
                        onDownloadClick = onDownloadClick,
                    )
                }
            }
        }
    }
}

/**
 * The scrolling body of the feed.
 *
 * @param uiState the feed.
 * @param onEpisodeClick plays an episode.
 * @param onQueueClick appends an episode to the queue.
 * @param onDownloadClick starts or removes an episode's offline copy.
 */
@Composable
private fun FeedList(
    uiState: HomeUiState,
    onEpisodeClick: (String) -> Unit,
    onQueueClick: (String) -> Unit,
    onDownloadClick: (String) -> Unit,
) {
    val now = remember { Instant.now() }
    val resources = LocalResources.current

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (uiState.continueListening.isNotEmpty()) {
            item(key = CONTINUE_HEADER_KEY) {
                SectionHeader(text = stringResource(R.string.home_continue_listening))
            }
            item(key = CONTINUE_SHELF_KEY) {
                ContinueListeningShelf(
                    entries = uiState.continueListening,
                    onEpisodeClick = onEpisodeClick,
                )
            }
        }

        uiState.groups.forEach { group ->
            item(key = group.section.name) {
                SectionHeader(text = stringResource(group.section.labelResId))
            }
            items(items = group.episodes, key = { it.episode.id }) { entry ->
                EpisodeRow(
                    title = entry.episode.title,
                    showTitle = entry.showTitle,
                    metadata = entry.episode.metadataLine(now, resources),
                    artworkUrl = entry.artworkUrl,
                    isUnplayed = entry.episode.isNew,
                    isPlayed = entry.episode.isPlayed,
                    playedFraction = entry.episode.playedFraction,
                    onClick = { onEpisodeClick(entry.episode.id) },
                    trailing = {
                        IconButton(onClick = { onQueueClick(entry.episode.id) }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.PlaylistAdd,
                                contentDescription = stringResource(R.string.home_add_to_queue),
                            )
                        }
                        DownloadButton(
                            state = entry.episode.downloadState,
                            progressPercent = entry.episode.downloadPercent,
                            onClick = { onDownloadClick(entry.episode.id) },
                        )
                    },
                )
            }
        }
    }
}

/**
 * A horizontal shelf of started-but-unfinished episodes.
 *
 * Separate from the chronological list on purpose: resuming something is a different intent from
 * browsing what is new, and a half-heard episode published three weeks ago would otherwise be
 * buried under Earlier where nobody scrolls.
 *
 * @param entries the in-progress episodes.
 * @param onEpisodeClick resumes an episode.
 */
@Composable
private fun ContinueListeningShelf(
    entries: List<EpisodeWithShow>,
    onEpisodeClick: (String) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = BPodcatTheme.spacing.screenHorizontal),
        horizontalArrangement = Arrangement.spacedBy(BPodcatTheme.spacing.md),
    ) {
        items(items = entries, key = { it.episode.id }) { entry ->
            Column(
                modifier = Modifier
                    .width(SHELF_CARD_WIDTH)
                    .clip(BPodcatTheme.shapes.artworkLarge)
                    .clickable(role = Role.Button) { onEpisodeClick(entry.episode.id) }
                    // One node per card, so TalkBack reads "title, 18 minutes left" as a single
                    // resumable item rather than walking an image and two labels.
                    .semantics(mergeDescendants = true) { }
                    .padding(bottom = BPodcatTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(BPodcatTheme.spacing.sm),
            ) {
                PodcastArtwork(
                    url = entry.artworkUrl,
                    size = ArtworkSize.Header,
                    shape = BPodcatTheme.shapes.artworkLarge,
                )
                Text(
                    text = entry.episode.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatRemaining(
                        durationMs = entry.episode.durationMs,
                        positionMs = entry.episode.positionMs,
                    ).orEmpty(),
                    style = BPodcatTheme.type.numeric,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The string resource naming a recency bucket. */
private val LatestSection.labelResId: Int
    get() = when (this) {
        LatestSection.TODAY -> R.string.home_section_today
        LatestSection.YESTERDAY -> R.string.home_section_yesterday
        LatestSection.THIS_WEEK -> R.string.home_section_this_week
        LatestSection.EARLIER -> R.string.home_section_earlier
    }

/**
 * The metadata line under an episode title.
 *
 * Shows how much is left rather than the total duration once an episode has been started: for
 * something half-listened-to, "18 min left" is the number that decides whether to resume it now.
 */
private fun Episode.metadataLine(now: Instant, resources: Resources): String {
    val length = if (isInProgress) {
        formatRemaining(durationMs = durationMs, positionMs = positionMs)
    } else {
        formatDuration(durationMs)
    }
    val published = formatPublishedDate(instant = publishedAt, now = now)
    return listOfNotNull(length, published).joinToString(separator = META_SEPARATOR)
        .ifEmpty { resources.getString(R.string.home_section_earlier) }
}

/** Maps a one-off outcome onto the text its snackbar shows. */
private fun HomeMessage.toText(resources: Resources): String = when (this) {
    is HomeMessage.Queued -> resources.getString(R.string.home_queued, title)

    HomeMessage.EpisodeUnavailable -> resources.getString(R.string.home_episode_unavailable)

    is HomeMessage.RefreshFinished -> when {
        summary.failedTitles.isNotEmpty() -> resources.getString(R.string.home_refresh_failed)

        summary.newEpisodeCount > 0 -> resources.getQuantityString(
            R.plurals.home_refresh_new_episodes,
            summary.newEpisodeCount,
            summary.newEpisodeCount,
        )

        else -> resources.getString(R.string.home_refresh_up_to_date)
    }
}

private const val CONTINUE_HEADER_KEY = "continue-header"
private const val CONTINUE_SHELF_KEY = "continue-shelf"
private const val META_SEPARATOR = " · "
private val SHELF_CARD_WIDTH = 152.dp

@Preview
@Composable
private fun HomeScreenPreview() {
    BPodcatTheme {
        HomeScreen(
            uiState = HomeUiState(
                groups = listOf(
                    LatestGroup(
                        section = LatestSection.TODAY,
                        episodes = listOf(previewEntry("e1", "The AI bubble, revisited", "Hard Fork")),
                    ),
                    LatestGroup(
                        section = LatestSection.THIS_WEEK,
                        episodes = listOf(previewEntry("e2", "Nvidia, Part III", "Acquired")),
                    ),
                ),
                isLoading = false,
            ),
            onEpisodeClick = {},
            onQueueClick = {},
            onDownloadClick = {},
            onRefresh = {},
            onAddPodcast = {},
            onOpenSettings = {},
            onMessageShown = {},
        )
    }
}

private fun previewEntry(id: String, title: String, show: String) = EpisodeWithShow(
    episode = Episode(
        id = id,
        podcastId = "p1",
        guid = id,
        title = title,
        description = "",
        audioUrl = "https://example.com/$id.mp3",
        artworkUrl = null,
        durationMs = 2_530_000L,
        publishedAt = Instant.parse("2026-08-30T09:00:00Z"),
        sizeBytes = null,
    ),
    showTitle = show,
    showArtworkUrl = null,
)
