package md.borisveriga.megapodcastplayer.feature.listen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import md.borisveriga.megapodcastplayer.core.common.format.formatDuration
import md.borisveriga.megapodcastplayer.core.common.format.formatPublishedDate
import md.borisveriga.megapodcastplayer.core.common.format.formatRemaining
import md.borisveriga.megapodcastplayer.core.designsystem.component.EmptyState
import md.borisveriga.megapodcastplayer.core.designsystem.component.EpisodeCard
import md.borisveriga.megapodcastplayer.core.designsystem.component.EpisodeShelf
import md.borisveriga.megapodcastplayer.core.designsystem.component.LoadingState
import md.borisveriga.megapodcastplayer.core.designsystem.component.MegaPodcastPlayerLargeTopAppBar
import md.borisveriga.megapodcastplayer.core.designsystem.theme.FontScalePreviews
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.designsystem.theme.ThemePreviews
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.EpisodeWithShow
import md.borisveriga.megapodcastplayer.feature.podcast.EpisodeDetailSheet

/**
 * The Listen tab: what to play, without going looking for it.
 *
 * @param onEpisodePlaying invoked once an episode has been handed to the player, so the shell can
 *   open the player sheet.
 * @param onBrowseLibrary opens the library, from the empty state.
 * @param modifier layout modifier.
 * @param viewModel injected by Hilt.
 */
@Composable
fun ListenRoute(
    onEpisodePlaying: () -> Unit,
    onBrowseLibrary: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ListenViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val openEpisodeId by viewModel.openEpisodeId.collectAsStateWithLifecycle()

    ListenScreen(
        uiState = uiState,
        openEpisodeId = openEpisodeId,
        onEpisodeClick = viewModel::openEpisode,
        onEpisodePlay = { episodeId -> viewModel.togglePlay(episodeId, onEpisodePlaying) },
        onEpisodeSheetDismiss = viewModel::closeEpisode,
        onBrowseLibrary = onBrowseLibrary,
        modifier = modifier,
    )
}

/**
 * Stateless Listen screen: three shelves, in the order the question is asked.
 *
 * This is the surface the app did not have. Its library is an inventory of shows, which is the
 * right answer to "what am I subscribed to" and no answer at all to "what shall I listen to now" —
 * so every session started show → scroll → tap, and the two things most sessions actually want,
 * *carry on with what I was in the middle of* and *what arrived since yesterday*, had nowhere to
 * live. Every fact needed for both was already in the database.
 *
 * A scrolling column of horizontal shelves rather than one long list: a list would answer *which
 * episode of this show*, and the question here is *which show*, which the eye answers off a row of
 * covers faster than any list of titles.
 *
 * A shelf with nothing on it is not drawn at all, header included. Three empty headings is a screen
 * telling the user three times that it has nothing for them.
 *
 * @param uiState what to render.
 * @param openEpisodeId the episode whose sheet is open, or null.
 * @param onEpisodeClick opens an episode's sheet.
 * @param onEpisodePlay plays an episode, or pauses the one playing.
 * @param onEpisodeSheetDismiss closes the sheet.
 * @param onBrowseLibrary opens the library from the empty state.
 * @param modifier layout modifier.
 * @param now the reference point for relative dates, injected so previews and tests are stable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenScreen(
    uiState: ListenUiState,
    openEpisodeId: String?,
    onEpisodeClick: (String) -> Unit,
    onEpisodePlay: (String) -> Unit,
    onEpisodeSheetDismiss: () -> Unit,
    onBrowseLibrary: () -> Unit,
    modifier: Modifier = Modifier,
    now: Instant = remember { Instant.now() },
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    openEpisodeId?.let { episodeId ->
        uiState.episodeById(episodeId)?.let { entry ->
            // The show page's own sheet. An episode opened from a shelf and one opened from its
            // show have to be the same thing, or the app has two answers to "what is this episode".
            EpisodeDetailSheet(
                episodeId = entry.episode.id,
                showTitle = entry.showTitle,
                artworkUrl = entry.artworkUrl,
                onPlaying = { onEpisodePlay(entry.episode.id) },
                onDismiss = onEpisodeSheetDismiss,
            )
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MegaPodcastPlayerLargeTopAppBar(
                title = stringResource(R.string.listen_title),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        when {
            uiState.isLoading -> LoadingState(modifier = Modifier.padding(padding))

            uiState.isEmpty -> EmptyState(
                icon = Icons.Rounded.Headphones,
                title = stringResource(R.string.listen_empty_title),
                description = stringResource(R.string.listen_empty_description),
                actionLabel = stringResource(R.string.listen_empty_action),
                onAction = onBrowseLibrary,
                modifier = Modifier.padding(padding),
            )

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.lg),
            ) {
                Shelf(
                    title = stringResource(R.string.listen_continue),
                    episodes = uiState.continueListening,
                    uiState = uiState,
                    now = now,
                    onEpisodeClick = onEpisodeClick,
                    onEpisodePlay = onEpisodePlay,
                )
                Shelf(
                    title = stringResource(R.string.listen_new),
                    episodes = uiState.newEpisodes,
                    uiState = uiState,
                    now = now,
                    onEpisodeClick = onEpisodeClick,
                    onEpisodePlay = onEpisodePlay,
                )
                Shelf(
                    title = stringResource(R.string.listen_up_next),
                    episodes = uiState.upNext,
                    uiState = uiState,
                    now = now,
                    onEpisodeClick = onEpisodeClick,
                    onEpisodePlay = onEpisodePlay,
                )
            }
        }
    }
}

/**
 * One shelf of cards.
 *
 * @param title the shelf's name.
 * @param episodes what it holds.
 * @param uiState read for which card is playing.
 * @param now reference point for the relative dates.
 * @param onEpisodeClick opens an episode.
 * @param onEpisodePlay plays one.
 */
@Composable
private fun Shelf(
    title: String,
    episodes: List<EpisodeWithShow>,
    uiState: ListenUiState,
    now: Instant,
    onEpisodeClick: (String) -> Unit,
    onEpisodePlay: (String) -> Unit,
) {
    EpisodeShelf(
        title = title,
        episodes = episodes,
        key = { it.episode.id },
    ) { entry ->
        val isNowPlaying = uiState.nowPlaying.episodeId == entry.episode.id

        EpisodeCard(
            title = entry.episode.title,
            showTitle = entry.showTitle,
            metadata = entry.episode.metadataLine(now),
            artworkUrl = entry.artworkUrl,
            playedFraction = entry.episode.playedFraction,
            isNowPlaying = isNowPlaying,
            isPlaying = uiState.nowPlaying.isPlaying,
            isBuffering = uiState.nowPlaying.isBuffering,
            stateDescription = entry.episode.stateDescription(isNowPlaying),
            onClick = { onEpisodeClick(entry.episode.id) },
            onPlay = { onEpisodePlay(entry.episode.id) },
        )
    }
}

/**
 * The line under a card's title.
 *
 * How much is *left* rather than how long it is, whenever the episode has been started: on a
 * "continue listening" shelf the total duration is the one number that does not help.
 *
 * @param now reference point for the relative date.
 * @return e.g. `2 days ago · 12 min left`.
 */
@Composable
private fun Episode.metadataLine(now: Instant): String {
    // `LocalResources` rather than `LocalContext.current.resources`, so a configuration change —
    // a locale switch included — invalidates the read and the line is formatted again.
    val resources = LocalResources.current
    return listOfNotNull(
        formatPublishedDate(resources, publishedAt, now),
        formatRemaining(resources, durationMs, positionMs).takeIf { isInProgress }
            ?: formatDuration(resources, durationMs),
    ).joinToString(SEPARATOR)
}

/**
 * What a screen reader says about a card, beyond its words.
 *
 * The same four states a list row announces, in the same order of precedence, so an episode reads
 * identically wherever it is met.
 *
 * @param isNowPlaying whether the player has this episode loaded.
 * @return the spoken state.
 */
@Composable
private fun Episode.stateDescription(isNowPlaying: Boolean): String = when {
    isNowPlaying -> stringResource(R.string.listen_state_now_playing)

    isPlayed -> stringResource(R.string.listen_state_played)

    isInProgress -> stringResource(
        R.string.listen_state_in_progress,
        (playedFraction * PERCENT).toInt(),
    )

    else -> stringResource(R.string.listen_state_unplayed)
}

/** Between the date and the duration. */
private const val SEPARATOR = " · "

/** Progress is stored as a fraction and spoken as a percentage. */
private const val PERCENT = 100

@ThemePreviews
@FontScalePreviews
@Composable
private fun ListenScreenPreview() {
    MegaPodcastPlayerTheme {
        ListenScreen(
            uiState = ListenUiState(
                continueListening = listOf(previewEpisode("a", positionMs = 900_000L)),
                newEpisodes = listOf(previewEpisode("b"), previewEpisode("c")),
                isLoading = false,
            ),
            openEpisodeId = null,
            onEpisodeClick = {},
            onEpisodePlay = {},
            onEpisodeSheetDismiss = {},
            onBrowseLibrary = {},
            now = PREVIEW_NOW,
        )
    }
}

@Preview
@Composable
private fun ListenScreenEmptyPreview() {
    MegaPodcastPlayerTheme {
        ListenScreen(
            uiState = ListenUiState(isLoading = false),
            openEpisodeId = null,
            onEpisodeClick = {},
            onEpisodePlay = {},
            onEpisodeSheetDismiss = {},
            onBrowseLibrary = {},
            now = PREVIEW_NOW,
        )
    }
}

/**
 * One sample episode for the previews.
 *
 * @param id the episode id, which is also the card key.
 * @param positionMs how far into it the preview pretends the user is.
 */
private fun previewEpisode(id: String, positionMs: Long = 0L) = EpisodeWithShow(
    episode = Episode(
        id = id,
        podcastId = "podcast-1",
        guid = "guid-$id",
        title = "Podlodka #49$id — Как устроены дизайн-системы",
        description = "",
        audioUrl = "https://cdn.example.com/$id.mp3",
        artworkUrl = null,
        durationMs = 2_530_000L,
        publishedAt = Instant.parse("2026-09-05T06:00:00Z"),
        sizeBytes = null,
        positionMs = positionMs,
    ),
    showTitle = "Podlodka Podcast",
    showArtworkUrl = null,
)

/** Fixed, so the previews' relative dates do not drift with the wall clock. */
private val PREVIEW_NOW: Instant = Instant.parse("2026-09-07T09:00:00Z")
