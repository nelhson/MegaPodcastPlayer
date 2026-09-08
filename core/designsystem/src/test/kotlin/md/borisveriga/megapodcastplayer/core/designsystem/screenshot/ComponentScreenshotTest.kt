package md.borisveriga.megapodcastplayer.core.designsystem.screenshot

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import md.borisveriga.megapodcastplayer.core.designsystem.component.ArtworkBackdropPreview
import md.borisveriga.megapodcastplayer.core.designsystem.component.DownloadButtonPreview
import md.borisveriga.megapodcastplayer.core.designsystem.component.EmptyStatePreview
import md.borisveriga.megapodcastplayer.core.designsystem.component.EpisodeCardPreview
import md.borisveriga.megapodcastplayer.core.designsystem.component.EpisodeRowPreview
import md.borisveriga.megapodcastplayer.core.designsystem.component.EpisodeShelfPreview
import md.borisveriga.megapodcastplayer.core.designsystem.component.LoadingStatePreview
import md.borisveriga.megapodcastplayer.core.designsystem.component.MegaPodcastPlayerTopAppBarPreview
import md.borisveriga.megapodcastplayer.core.designsystem.component.PlayPauseButtonPreview
import md.borisveriga.megapodcastplayer.core.designsystem.component.PodcastArtworkHeroPreview
import md.borisveriga.megapodcastplayer.core.designsystem.component.PodcastArtworkPreview
import md.borisveriga.megapodcastplayer.core.designsystem.component.SectionHeaderPreview
import md.borisveriga.megapodcastplayer.core.designsystem.component.SettingsChoiceRowPreview
import md.borisveriga.megapodcastplayer.core.designsystem.component.SettingsSwitchRowPreview
import md.borisveriga.megapodcastplayer.core.designsystem.component.ShowRowPreview
import md.borisveriga.megapodcastplayer.core.designsystem.component.ShowRowSelectedPreview
import md.borisveriga.megapodcastplayer.core.designsystem.component.ShowRowYouTubePreview
import md.borisveriga.megapodcastplayer.core.designsystem.component.ShowTileNoBadgePreview
import md.borisveriga.megapodcastplayer.core.designsystem.component.ShowTilePreview
import md.borisveriga.megapodcastplayer.core.designsystem.component.ShowTileSelectedPreview
import md.borisveriga.megapodcastplayer.core.designsystem.component.SortMenuChipPreview
import md.borisveriga.megapodcastplayer.core.designsystem.component.SortToggleChipPreview
import md.borisveriga.megapodcastplayer.core.designsystem.component.SourceBadgePreview
import md.borisveriga.megapodcastplayer.core.designsystem.component.WaveScrubberPreview
import md.borisveriga.megapodcastplayer.core.designsystem.component.WavyProgressLinePreview
import md.borisveriga.megapodcastplayer.core.designsystem.theme.MegaPodcastPlayerTheme
import md.borisveriga.megapodcastplayer.core.testing.SCREENSHOT_QUALIFIERS
import md.borisveriga.megapodcastplayer.core.testing.ScreenshotVariant
import md.borisveriga.megapodcastplayer.core.testing.captureScreenshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Goldens for the design system's components — the shared vocabulary every screen speaks.
 *
 * **Each test renders the component's own `@Preview`.** That is the whole trick of this suite and
 * the reason DS-5 asked for previews and screenshots as one item rather than two: the preview is
 * where the curated state already lives — three episode rows in three different conditions, every
 * download state in a row — and recording it means the golden cannot drift from the thing a
 * designer looks at in the IDE. Adding a component's preview to this file is one import and three
 * lines; inventing a second copy of its state here would be a second thing to keep true.
 *
 * The previews are `internal` rather than `private` for exactly this, and for nothing else.
 *
 * Every test runs once per [ScreenshotVariant], so one failure names the component *and* the
 * rendering it broke in. Record with `-Pmegapodcastplayer.screenshots.record`, then look at the
 * images: a re-recorded golden is a design change being accepted.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = SCREENSHOT_QUALIFIERS)
class ComponentScreenshotTest(private val variant: ScreenshotVariant) {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * Renders [content] in the app's theme, on the theme's own ground.
     *
     * The surface matters: a component that paints no background of its own would otherwise be
     * captured against transparency, where a contrast regression is invisible. The previews apply
     * the theme again inside this one, which costs nothing — the values are the same — and is what
     * lets them stay ordinary previews rather than becoming test fixtures.
     */
    private fun capture(name: String, content: @Composable () -> Unit) =
        composeRule.captureScreenshot(name, variant) {
            MegaPodcastPlayerTheme(darkTheme = variant.darkTheme) {
                Surface(color = MaterialTheme.colorScheme.background) { content() }
            }
        }

    @Test
    fun episodeRow() = capture("episode-row") { EpisodeRowPreview() }

    @Test
    fun episodeShelf() = capture("episode-shelf") { EpisodeShelfPreview() }

    @Test
    fun episodeCard() = capture("episode-card") { EpisodeCardPreview() }

    @Test
    fun showRow() = capture("show-row") { ShowRowPreview() }

    @Test
    fun showRowYouTube() = capture("show-row-youtube") { ShowRowYouTubePreview() }

    @Test
    fun showRowSelected() = capture("show-row-selected") { ShowRowSelectedPreview() }

    @Test
    fun showTile() = capture("show-tile") { ShowTilePreview() }

    @Test
    fun showTileSelected() = capture("show-tile-selected") { ShowTileSelectedPreview() }

    @Test
    fun showTileNoBadge() = capture("show-tile-no-badge") { ShowTileNoBadgePreview() }

    @Test
    fun topAppBar() = capture("top-app-bar") { MegaPodcastPlayerTopAppBarPreview() }

    @Test
    fun sectionHeader() = capture("section-header") { SectionHeaderPreview() }

    @Test
    fun playPauseButton() = capture("play-pause-button") { PlayPauseButtonPreview() }

    @Test
    fun downloadButton() = capture("download-button") { DownloadButtonPreview() }

    @Test
    fun sourceBadge() = capture("source-badge") { SourceBadgePreview() }

    @Test
    fun sortToggleChip() = capture("sort-toggle-chip") { SortToggleChipPreview() }

    @Test
    fun sortMenuChip() = capture("sort-menu-chip") { SortMenuChipPreview() }

    @Test
    fun settingsSwitchRow() = capture("settings-switch-row") { SettingsSwitchRowPreview() }

    @Test
    fun settingsChoiceRow() = capture("settings-choice-row") { SettingsChoiceRowPreview() }

    @Test
    fun waveScrubber() = capture("wave-scrubber") { WaveScrubberPreview() }

    @Test
    fun wavyProgressLine() = capture("wavy-progress-line") { WavyProgressLinePreview() }

    @Test
    fun podcastArtwork() = capture("podcast-artwork") { PodcastArtworkPreview() }

    @Test
    fun podcastArtworkHero() = capture("podcast-artwork-hero") { PodcastArtworkHeroPreview() }

    @Test
    fun artworkBackdrop() = capture("artwork-backdrop") { ArtworkBackdropPreview() }

    @Test
    fun loadingState() = capture("loading-state") { LoadingStatePreview() }

    @Test
    fun emptyState() = capture("empty-state") { EmptyStatePreview() }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants(): List<Array<Any>> = ScreenshotVariant.entries.map { arrayOf(it) }
    }
}
