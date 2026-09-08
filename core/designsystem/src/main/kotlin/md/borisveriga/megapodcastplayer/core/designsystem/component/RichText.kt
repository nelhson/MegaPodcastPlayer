package md.borisveriga.megapodcastplayer.core.designsystem.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow

/**
 * Show notes, as the publisher wrote them.
 *
 * Episode descriptions are HTML fragments — links, bold, paragraphs, and the timestamp lists a lot
 * of publishers use as chapters. The app parsed and stored them from day one and displayed them
 * nowhere, which is why an episode could be played and queued but never *read*.
 *
 * `AnnotatedString.fromHtml` rather than a `TextView` behind an `AndroidView`: the links become
 * real Compose links, so they are focusable, they announce themselves to TalkBack, and they are
 * styled from the app's own palette instead of the platform's blue. It is also the only spelling
 * that keeps this a `Text`, which is what lets the caller give it a style and a line limit.
 *
 * Untrusted input by definition — a feed is a stranger's HTML — but nothing here executes it: the
 * parser produces styled text, `<script>` has no meaning to it, and a link is opened by the
 * platform's own handler with the user's tap behind it.
 *
 * @param html the description, exactly as stored.
 * @param modifier layout modifier.
 * @param style the base text style; links and emphasis are layered over it.
 * @param color the body colour, applied to everything but the links.
 * @param maxLines a line limit, for a collapsed preview.
 */
@Composable
fun RichText(
    html: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines: Int = Int.MAX_VALUE,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    // Parsing walks the whole document, and a description can be a page of it; keyed on the text so
    // a recomposition for any other reason — a position tick, a theme change — does not redo it.
    val text = remember(html, linkColor) {
        AnnotatedString.fromHtml(
            htmlString = html,
            linkStyles = TextLinkStyles(
                style = SpanStyle(
                    color = linkColor,
                    textDecoration = TextDecoration.Underline,
                ),
                // Pressed and focused are the two states a link has to be able to show: one for a
                // finger, one for a keyboard or a D-pad on the Fold's cover screen.
                pressedStyle = SpanStyle(
                    color = linkColor,
                    fontWeight = FontWeight.Bold,
                    textDecoration = TextDecoration.Underline,
                ),
                focusedStyle = SpanStyle(
                    color = linkColor,
                    fontWeight = FontWeight.Bold,
                    textDecoration = TextDecoration.Underline,
                ),
            ),
        )
    }

    Text(
        text = text,
        modifier = modifier,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
