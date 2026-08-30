package md.borisveriga.bpodcat.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Brand colours that Material 3 has no role for.
 *
 * Material's scheme covers "primary action", "surface", "error" and so on, but a podcast app
 * also needs to say *downloaded*, *unplayed* and *this row is the one playing right now*. Those
 * meanings were previously expressed by whichever Material role looked closest at the call site,
 * which is why the same idea rendered three different colours on three screens.
 *
 * Read these through [BPodcatTheme.colors], never by importing the values directly: the two
 * instances below are the light and dark bindings of one vocabulary.
 *
 * @property downloaded fill for the "available offline" affordance.
 * @property onDownloaded content drawn on [downloaded].
 * @property unplayed the new-episode dot and unplayed-count badge.
 * @property onUnplayed content drawn on [unplayed].
 * @property nowPlaying tint for the list row whose episode is currently loaded in the player.
 * @property nowPlayingContainer background wash for that same row.
 * @property waveform played portion of the scrubber and of inline progress lines.
 * @property waveformTrack unplayed portion of the same.
 * @property artworkPlaceholder ground behind artwork that is missing or still loading.
 * @property onArtworkPlaceholder the fallback glyph drawn on it.
 * @property youtube the YouTube source badge; the one colour in the app we do not get to choose.
 * @property artworkScrimTop top stop of the gradient laid over cover art behind text.
 * @property artworkScrimBottom bottom stop of that gradient.
 */
@Immutable
data class BPodcatColors(
    val downloaded: Color,
    val onDownloaded: Color,
    val unplayed: Color,
    val onUnplayed: Color,
    val nowPlaying: Color,
    val nowPlayingContainer: Color,
    val waveform: Color,
    val waveformTrack: Color,
    val artworkPlaceholder: Color,
    val onArtworkPlaceholder: Color,
    val youtube: Color,
    val artworkScrimTop: Color,
    val artworkScrimBottom: Color,
)

/**
 * Light bindings.
 *
 * The scrim stops are near-black at low alpha rather than the surface colour: cover art is
 * arbitrary, so the scrim has to darken reliably whatever sits under it, in both themes.
 */
internal val citronLightExtendedColors = BPodcatColors(
    downloaded = Color(0xFF386569),
    onDownloaded = Color(0xFFFFFFFF),
    unplayed = Color(0xFF4A5C00),
    onUnplayed = Color(0xFFFFFFFF),
    nowPlaying = Color(0xFF4A5C00),
    nowPlayingContainer = Color(0xFFEDF3D2),
    waveform = Color(0xFF4A5C00),
    waveformTrack = Color(0xFFB2B5A2),
    artworkPlaceholder = Color(0xFFE4E4D0),
    onArtworkPlaceholder = Color(0xFF5F6356),
    youtube = Color(0xFFC62828),
    artworkScrimTop = Color(0x00000000),
    artworkScrimBottom = Color(0xB3000000),
)

/** Dark bindings. */
internal val citronDarkExtendedColors = BPodcatColors(
    downloaded = Color(0xFFA0CFD3),
    onDownloaded = Color(0xFF003739),
    unplayed = Color(0xFFD4F24A),
    onUnplayed = Color(0xFF232B00),
    nowPlaying = Color(0xFFD4F24A),
    nowPlayingContainer = Color(0xFF262B14),
    waveform = Color(0xFFD4F24A),
    waveformTrack = Color(0xFF666A5B),
    artworkPlaceholder = Color(0xFF292B23),
    onArtworkPlaceholder = Color(0xFF909383),
    youtube = Color(0xFFFF6E6E),
    artworkScrimTop = Color(0x00000000),
    artworkScrimBottom = Color(0xCC000000),
)
