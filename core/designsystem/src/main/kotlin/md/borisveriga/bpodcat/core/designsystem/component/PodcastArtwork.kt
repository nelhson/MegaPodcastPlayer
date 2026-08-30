package md.borisveriga.bpodcat.core.designsystem.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import md.borisveriga.bpodcat.core.designsystem.theme.BPodcatTheme
import md.borisveriga.bpodcat.core.designsystem.theme.ThemePreviews

/**
 * Square podcast or episode artwork with a themed placeholder.
 *
 * Artwork is decorative: the show or episode title always sits next to it, so the image itself is
 * hidden from accessibility services rather than announced twice.
 *
 * @param url artwork URL; null or a load failure falls back to a podcast glyph.
 * @param modifier layout modifier. Prefer the [size] parameter for the square case.
 * @param size one of the named rungs, or null when the caller sizes the artwork itself (the hero
 *   artwork in the expanded player is a fraction of the screen width, not a fixed dp).
 * @param shape the mask. Defaults to the artwork squircle; the hero uses the larger radius.
 */
@Composable
fun PodcastArtwork(
    url: String?,
    modifier: Modifier = Modifier,
    size: ArtworkSize? = null,
    shape: Shape = BPodcatTheme.shapes.artwork,
) {
    var failed by remember(url) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .then(if (size != null) Modifier.size(size.dimension) else Modifier)
            .clip(shape)
            .background(BPodcatTheme.colors.artworkPlaceholder)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        if (url != null && !failed) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Podcasts,
                contentDescription = null,
                tint = BPodcatTheme.colors.onArtworkPlaceholder,
                modifier = Modifier.fillMaxSize(PLACEHOLDER_GLYPH_FRACTION),
            )
        }
    }
}

/**
 * Three bars that rise and fall while an episode is playing.
 *
 * The app previously had no way at all to show which row in a list was the one currently loaded in
 * the player. A tinted title is easy to miss and reads as "selected"; an animated equaliser is
 * unambiguous and needs no legend.
 *
 * Decorative: the row that owns it carries the state in its own semantics, so this is hidden from
 * accessibility services rather than announced as three anonymous bars.
 *
 * @param modifier layout modifier.
 * @param playing whether the bars animate; when false they rest at their minimum height, which is
 *   what "loaded but paused" should look like.
 */
@Composable
fun NowPlayingBars(
    modifier: Modifier = Modifier,
    playing: Boolean = true,
) {
    val transition = rememberInfiniteTransition(label = "bars")

    Row(
        modifier = modifier
            .height(BAR_MAX_HEIGHT)
            .clearAndSetSemantics { },
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(BAR_GAP),
    ) {
        BAR_DELAYS_MS.forEach { delayMillis ->
            // Each bar runs the same animation offset in time, so the group reads as a waveform
            // rather than three bars moving in lockstep.
            val fraction by transition.animateFloat(
                initialValue = BAR_MIN_FRACTION,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = BAR_CYCLE_MS, delayMillis = delayMillis),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "bar",
            )
            Box(
                modifier = Modifier
                    .width(BAR_WIDTH)
                    .height(BAR_MAX_HEIGHT * if (playing) fraction else BAR_MIN_FRACTION)
                    .clip(BPodcatTheme.shapes.pill)
                    .background(BPodcatTheme.colors.nowPlaying),
            )
        }
    }
}

/** How much of the artwork box the fallback glyph fills. */
private const val PLACEHOLDER_GLYPH_FRACTION = 0.45f

private val BAR_WIDTH = 3.dp
private val BAR_GAP = 2.dp
private val BAR_MAX_HEIGHT = 14.dp
private const val BAR_MIN_FRACTION = 0.3f
private const val BAR_CYCLE_MS = 420

/** Per-bar phase offsets; prime-ish spacing so the loop never looks like it repeats. */
private val BAR_DELAYS_MS = listOf(0, 130, 70)

@ThemePreviews
@Composable
private fun PodcastArtworkPreview() {
    BPodcatTheme {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkSize.entries.forEach { size ->
                PodcastArtwork(url = null, size = size)
            }
            NowPlayingBars()
        }
    }
}

@Preview(name = "Hero artwork")
@Composable
private fun PodcastArtworkHeroPreview() {
    BPodcatTheme {
        PodcastArtwork(
            url = null,
            modifier = Modifier.size(220.dp),
            shape = BPodcatTheme.shapes.artworkLarge,
        )
    }
}
