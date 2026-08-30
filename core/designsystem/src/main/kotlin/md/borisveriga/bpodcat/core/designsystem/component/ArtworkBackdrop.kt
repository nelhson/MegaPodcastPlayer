package md.borisveriga.bpodcat.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
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
import md.borisveriga.bpodcat.core.designsystem.theme.BPodcatTheme
import md.borisveriga.bpodcat.core.designsystem.theme.ThemePreviews

/**
 * Cover art blurred into a background, with a scrim over it.
 *
 * Used behind the expanded player and the show header. The artwork is the most colourful thing the
 * app has and letting it wash the surface behind the controls is what makes those screens feel
 * like a music app rather than a list of strings.
 *
 * The scrim is not optional and is not derived from the theme surface: cover art is arbitrary
 * third-party imagery, so the only way to guarantee the text on top stays legible is to darken
 * whatever is underneath by a fixed amount. That is why [BPodcatTheme.colors] carries explicit
 * scrim stops rather than reusing `surface` at an alpha.
 *
 * @param url artwork to blur; null renders the plain surface, which is the correct empty state.
 * @param modifier layout modifier.
 * @param blurRadius how far to blur. Large by default — this must read as colour and light, never
 *   as a recognisable image competing with the sharp artwork in front of it.
 * @param content drawn on top of the backdrop.
 */
@Composable
fun ArtworkBackdrop(
    url: String?,
    modifier: Modifier = Modifier,
    blurRadius: Dp = BLUR_RADIUS,
    content: @Composable BoxScope.() -> Unit,
) {
    val scrimTop = BPodcatTheme.colors.artworkScrimTop
    val scrimBottom = BPodcatTheme.colors.artworkScrimBottom

    Box(modifier = modifier) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    // Decorative by definition: the same artwork is already on screen, sharp, with
                    // the show's name beside it.
                    .clearAndSetSemantics { }
                    .blur(radius = blurRadius, edgeTreatment = BlurredEdgeTreatment.Rectangle)
                    .drawWithCache {
                        val brush = Brush.verticalGradient(listOf(scrimTop, scrimBottom))
                        onDrawWithContent {
                            drawContent()
                            drawRect(brush)
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
    BPodcatTheme {
        ArtworkBackdrop(url = null, modifier = Modifier.height(220.dp)) {
            PodcastArtwork(url = null, size = ArtworkSize.Header)
        }
    }
}
