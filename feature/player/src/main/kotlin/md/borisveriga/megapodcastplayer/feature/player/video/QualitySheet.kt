package md.borisveriga.megapodcastplayer.feature.player.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import md.borisveriga.megapodcastplayer.core.designsystem.component.MegaPodcastPlayerBottomSheet
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.model.VideoQuality
import md.borisveriga.megapodcastplayer.feature.player.R

/**
 * The quality picker, as a sheet.
 *
 * Chips rather than a list, in the shape of the speed sheet's presets: there are at most six or
 * seven renditions and each is a two-syllable label, so a column of rows would spend a screen on
 * what fits in two lines. It lists what the video actually offers, not a fixed ladder — a chip for
 * a rendition the video does not have would be a button that does nothing.
 *
 * @param qualities the renditions on offer, lowest first; null while still being asked for.
 * @param selected the rendition the button named, which is the one marked.
 * @param failed true when the renditions could not be asked for at all.
 * @param onSelect a rendition was tapped.
 * @param onDismiss closes the sheet.
 * @param modifier layout modifier.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualitySheet(
    qualities: List<VideoQuality>?,
    selected: VideoQuality,
    failed: Boolean,
    onSelect: (VideoQuality) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MegaPodcastPlayerBottomSheet(
        onDismiss = onDismiss,
        modifier = modifier,
        title = stringResource(R.string.video_quality_title),
        subtitle = stringResource(R.string.video_quality_description),
    ) {
        QualityOptions(qualities = qualities, selected = selected, failed = failed, onSelect = onSelect)
    }
}

/**
 * The sheet's contents, without the sheet, so a preview can hold them.
 *
 * @param qualities the renditions on offer; null while still being asked for.
 * @param selected the rendition marked.
 * @param failed true when the renditions could not be asked for.
 * @param onSelect a rendition was tapped.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QualityOptions(
    qualities: List<VideoQuality>?,
    selected: VideoQuality,
    failed: Boolean,
    onSelect: (VideoQuality) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MegaPodcastPlayerTheme.spacing.screenHorizontal)
            .padding(bottom = MegaPodcastPlayerTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            failed -> Text(
                text = stringResource(R.string.video_quality_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            qualities == null -> CircularProgressIndicator()

            qualities.isEmpty() -> Text(
                text = stringResource(R.string.video_quality_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(
                    MegaPodcastPlayerTheme.spacing.sm,
                    Alignment.CenterHorizontally,
                ),
                verticalArrangement = Arrangement.spacedBy(MegaPodcastPlayerTheme.spacing.xs),
            ) {
                qualities.forEach { quality ->
                    val isSelected = quality == selected
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelect(quality) },
                        label = { Text(stringResource(R.string.video_quality_label, quality.height)) },
                        leadingIcon = if (isSelected) {
                            {
                                // The chip's own selected state is what a screen reader announces;
                                // the glyph only repeats it for eyes.
                                Icon(imageVector = Icons.Rounded.Check, contentDescription = null)
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}
