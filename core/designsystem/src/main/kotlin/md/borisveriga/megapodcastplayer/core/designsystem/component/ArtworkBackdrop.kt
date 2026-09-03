package md.borisveriga.megapodcastplayer.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.designsystem.theme.ThemePreviews

/**
 * Cover art blurred into a background, with a scrim over it.
 *
 * Used behind the expanded player and the show header. The artwork is the most colourful thing the
 * app has and letting it wash the surface behind the controls is what makes those screens feel
 * like a music app rather than a list of strings.
 *
 * The scrim is not optional and is not derived from the theme surface: cover art is arbitrary
 * third-party imagery, so the only way to guarantee the text on top stays legible is to darken
 * whatever is underneath by a fixed amount. That is why [MegaPodcastPlayerTheme.colors] carries explicit
 * scrim stops rather than reusing `surface` at an alpha.
 *
 * @param url artwork to blur; null renders the plain surface, which is the correct empty state.
 * @param modifier layout modifier.
 * @param blurRadius how far to blur. Large by default — this must read as colour and light, never
 *   as a recognisable image competing with the sharp artwork in front of it.
 * @param scrim what is laid over the blur. The default darkens towards the bottom, which is what
 *   the player wants: its text sits on the backdrop. A caller whose text sits *below* the backdrop
 *   should pass a gradient that ends in the surface colour instead, so the wash meets the page
 *   rather than stopping against it in a hard line.
 * @param content drawn on top of the backdrop.
 */
@Composable
fun ArtworkBackdrop(
    url: String?,
    modifier: Modifier = Modifier,
    blurRadius: Dp = BLUR_RADIUS,
    scrim: Brush = Brush.verticalGradient(
        listOf(MegaPodcastPlayerTheme.colors.artworkScrimTop, MegaPodcastPlayerTheme.colors.artworkScrimBottom),
    ),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    // `matchParentSize`, not `fillMaxSize`: this is a background, and it must take
                    // its size from whatever is drawn on top of it rather than the other way round.
                    // `fillMaxSize` claims the incoming maximum height, which in a scrolling list
                    // is the whole viewport — a wash meant to sit behind a 96dp cover would push
                    // everything under it off the screen.
                    .matchParentSize()
                    // Decorative by definition: the same artwork is already on screen, sharp, with
                    // the show's name beside it.
                    .clearAndSetSemantics { }
                    .blur(radius = blurRadius, edgeTreatment = BlurredEdgeTreatment.Rectangle)
                    .drawWithCache {
                        onDrawWithContent {
                            drawContent()
                            drawRect(scrim)
                        }
                    },
            )
        }
        content()
    }
}

private val BLUR_RADIUS = 64.dp

@ThemePreviews
@Composable
private fun ArtworkBackdropPreview() {
    MegaPodcastPlayerTheme {
        ArtworkBackdrop(url = null, modifier = Modifier.height(220.dp)) {
            PodcastArtwork(url = null, size = ArtworkSize.Header)
        }
    }
}
