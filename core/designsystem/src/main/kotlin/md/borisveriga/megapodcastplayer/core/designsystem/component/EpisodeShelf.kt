package md.borisveriga.megapodcastplayer.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import md.borisveriga.megapodcastplayer.core.designsystem.theme.FontScalePreviews
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.designsystem.theme.ThemePreviews

/**
 * A row of episode cards, read across rather than scrolled through.
 *
 * The shape a "what shall I listen to now" surface is made of. A list answers *which* episode of a
 * show; a shelf answers *which show*, by putting five or six covers side by side and letting the
 * eye do the choosing — which is the actual question at the start of a session.
 *
 * Deliberately not a `LazyColumn` of rows. Three shelves of full-width rows is a screen the user has
 * to scroll to see the second one at all, and the whole point is that everything on offer is on
 * screen at once.
 *
 * @param title the shelf's name, drawn as a heading so a screen reader can jump between shelves.
 * @param episodes what the shelf holds; an empty list draws nothing at all, header included.
 * @param key a stable identity for each card.
 * @param modifier layout modifier.
 * @param card draws one card; the caller supplies it because what a card *says* is a feature's
 *   business and how a shelf is arranged is the design system's.
 */
@Composable
fun <T> EpisodeShelf(
    title: String,
    episodes: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    card: @Composable (T) -> Unit,
) {
    if (episodes.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(text = title)

        LazyRow(
            contentPadding = PaddingValues(
                horizontal = MegaPodcastPlayerTheme.spacing.screenHorizontal,
            ),
            horizontalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.md),
        ) {
            items(items = episodes, key = key) { episode -> card(episode) }
        }
    }
}

/**
 * One episode, as a card on a shelf.
 *
 * A card is a *cover with a caption*: the artwork does the work, and the two lines under it are
 * there to disambiguate two episodes of the same show. That is why the title gets two lines and the
 * show gets one, the opposite way round from a list row, where the artwork is a thumbnail and the
 * words are the row.
 *
 * The play control is the same [PlayPauseButton] a list row carries, at the same rung, for the same
 * reason: it keeps playing at one tap while the card's own tap opens the episode, and it doubles as
 * the mark for whichever card is loaded — the only one showing a pause.
 *
 * @param title the episode title.
 * @param showTitle the show it came from; a shelf mixes shows, so this is never optional.
 * @param metadata the trailing detail line, e.g. `2 days ago · 42 min`.
 * @param artworkUrl artwork for the cover; null renders the themed placeholder.
 * @param playedFraction progress through the episode, drawn as a hairline under the cover.
 * @param isNowPlaying whether this is the episode the player has loaded.
 * @param isPlaying whether that episode is running.
 * @param isBuffering whether it is preparing.
 * @param stateDescription what a screen reader says about the card's state, in the feature's words.
 * @param onClick opens the episode.
 * @param onPlay plays it, or pauses it when it is the one playing.
 * @param modifier layout modifier.
 */
@Composable
fun EpisodeCard(
    title: String,
    showTitle: String,
    metadata: String,
    artworkUrl: String?,
    playedFraction: Float,
    isNowPlaying: Boolean,
    isPlaying: Boolean,
    isBuffering: Boolean,
    stateDescription: String,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(CARD_WIDTH)
            .clickable(role = Role.Button, onClick = onClick)
            // One node per card, like every row in the app: a screen reader says "Podlodka, the AI
            // bubble, 2 days ago, 30 percent played" rather than walking four fragments.
            .semantics(mergeDescendants = true) {
                this.stateDescription = stateDescription
            },
        verticalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.sm),
    ) {
        PodcastArtwork(
            url = artworkUrl,
            size = ArtworkSize.Card,
            shape = MegaPodcastPlayerTheme.shapes.artworkLarge,
        )

        if (playedFraction > 0f) {
            LinearProgressIndicator(
                progress = { playedFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    // The state description already says how far through it is; a second reading
                    // as a bare percentage would be the same fact twice.
                    .clearAndSetSemantics { },
            )
        }

        Text(
            text = showTitle,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = CARD_TITLE_LINES,
            overflow = TextOverflow.Ellipsis,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = metadata,
                style = MegaPodcastPlayerTheme.type.numeric,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Weighted, and not filling: a weighted child is measured after the unweighted
                // ones, so the button takes its full size first and the text gets what is left,
                // ellipsised. Without this a line like "6 days ago · 1 h 30 min left" took the
                // whole card and squeezed the button to a sliver.
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(end = MegaPodcastPlayerTheme.spacing.sm),
            )
            PlayPauseButton(
                playing = isNowPlaying && isPlaying,
                onToggle = { onPlay() },
                size = PlayPauseSize.Small,
                buffering = isNowPlaying && isBuffering,
                containerColor = if (isNowPlaying) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                contentColor = if (isNowPlaying) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
            )
        }
    }
}

/**
 * How wide a card is.
 *
 * Fixed rather than a fraction of the screen, so that a shelf shows a little of the next card on
 * every device — which is the only thing that says a shelf scrolls. On the Fold's inner display it
 * simply shows more of them.
 */
private val CARD_WIDTH: Dp = 168.dp

/** Two lines of title: enough to tell two episodes of one show apart, and no more. */
private const val CARD_TITLE_LINES = 2

/**
 * A shelf of three, the middle one playing.
 *
 * The two things worth seeing side by side are the progress hairline under a part-heard card and
 * the way a playing card's button changes colour rather than shape.
 */
@ThemePreviews
@Composable
internal fun EpisodeShelfPreview() {
    MegaPodcastPlayerTheme {
        EpisodeShelf(
            title = "Continue listening",
            episodes = listOf(
                Triple("The AI bubble, revisited", "Hard Fork", 0.62f),
                Triple("Nvidia, Part III", "Acquired", 0.18f),
                Triple("Why is it so hard to buy a mattress?", "Search Engine", 0f),
            ),
            key = { it.first },
        ) { (title, show, played) ->
            EpisodeCard(
                title = title,
                showTitle = show,
                metadata = "42 min left",
                artworkUrl = null,
                playedFraction = played,
                isNowPlaying = played == 0.18f,
                isPlaying = played == 0.18f,
                isBuffering = false,
                stateDescription = "",
                onClick = {},
                onPlay = {},
            )
        }
    }
}

/** One card on its own, at every text size, because two lines of title is where it gives. */
@ThemePreviews
@FontScalePreviews
@Composable
internal fun EpisodeCardPreview() {
    MegaPodcastPlayerTheme {
        EpisodeCard(
            title = "Nvidia, Part III: The Dan Ives Chronicles",
            showTitle = "Acquired",
            metadata = "2 h 41 min left",
            artworkUrl = null,
            playedFraction = 0.3f,
            isNowPlaying = true,
            isPlaying = true,
            isBuffering = false,
            stateDescription = "Playing",
            onClick = {},
            onPlay = {},
        )
    }
}
