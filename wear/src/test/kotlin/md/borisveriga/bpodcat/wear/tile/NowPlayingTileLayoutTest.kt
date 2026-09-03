package md.borisveriga.bpodcat.wear.tile

import androidx.wear.protolayout.LayoutElementBuilders
import md.borisveriga.bpodcat.core.wearprotocol.NowPlayingSnapshot
import md.borisveriga.bpodcat.core.wearprotocol.WearCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks what the tile actually contains.
 *
 * A tile cannot be rendered in a unit test — the drawing happens in the system's process — but it is
 * built as a proto, and a proto can be read. So these assert on the built layout rather than on a
 * picture: that the words the wearer needs are in it, that the buttons carry the ids the taps come
 * back as, and that a tile with nothing playing offers no transport controls to press.
 */
class NowPlayingTileLayoutTest {

    private val copy = TileCopy(
        idleTitle = "Nothing playing",
        idleBody = "Start an episode on your phone.",
        unreachable = "Could not reach your phone",
        play = "Play",
        pause = "Pause",
        skipBack = "Skip back",
        skipForward = "Skip ahead",
    )

    private val playing = NowPlayingSnapshot(
        episodeId = "ep-1",
        title = "The one about batteries",
        showTitle = "Radio Hardware",
        isPlaying = true,
        positionMs = 252_000L,
        durationMs = 3_600_000L,
    )

    @Test
    fun `the tile names the episode and its show`() {
        val rendered = render(playing)

        assertTrue(rendered.contains("The one about batteries"))
        assertTrue(rendered.contains("Radio Hardware"))
    }

    /** Every button is a tap that has to come back with an id, or it does nothing at all. */
    @Test
    fun `the transport buttons carry the ids their taps come back as`() {
        val rendered = render(playing)

        assertTrue(rendered.contains(TileClicks.PLAY_PAUSE))
        assertTrue(rendered.contains(TileClicks.SKIP_FORWARD))
        assertTrue(rendered.contains(TileClicks.SKIP_BACK))
    }

    @Test
    fun `a playing tile offers pause and a paused one offers play`() {
        // Matched as whole property values, because the layout also carries the *click* id
        // "play_pause" — a bare "play" would match that and prove nothing.
        assertTrue(render(playing).contains(propertyValue(TileImages.PAUSE)))
        assertFalse(render(playing).contains(propertyValue(TileImages.PLAY)))

        val paused = render(playing.copy(isPlaying = false))
        assertTrue(paused.contains(propertyValue(TileImages.PLAY)))
        assertFalse(paused.contains(propertyValue(TileImages.PAUSE)))
    }

    /**
     * The idle tile must not draw controls: they would send commands about an episode the phone does
     * not have loaded, and each one would silently do nothing.
     */
    @Test
    fun `an idle tile explains itself instead of showing dead controls`() {
        val rendered = render(NowPlayingSnapshot())

        assertTrue(rendered.contains("Nothing playing"))
        assertTrue(rendered.contains("Start an episode on your phone."))
        assertFalse(rendered.contains(TileClicks.PLAY_PAUSE))
    }

    /**
     * A tile only ever learns the phone is unreachable by failing to reach it, so the one tap that
     * failed has to be worth something.
     */
    @Test
    fun `an undelivered tap says so on the tile`() {
        assertTrue(render(playing, commandFailed = true).contains("Could not reach your phone"))
        assertFalse(render(playing).contains("Could not reach your phone"))
    }

    @Test
    fun `a show the phone did not name leaves the line out rather than drawing an empty one`() {
        val rendered = render(playing.copy(showTitle = ""))

        assertTrue(rendered.contains("The one about batteries"))
        assertFalse(rendered.contains("Radio Hardware"))
    }

    @Test
    fun `tapping the tile itself opens the app`() {
        val rendered = render(playing)

        assertTrue(rendered.contains(TileClicks.OPEN_APP))
        assertTrue(rendered.contains("md.borisveriga.bpodcat.wear.MainActivity"))
    }

    @Test
    fun `only the transport buttons ask the phone for anything`() {
        assertEquals(WearCommand.TogglePlayPause, tileCommandFor(TileClicks.PLAY_PAUSE))
        assertEquals(WearCommand.SkipForward, tileCommandFor(TileClicks.SKIP_FORWARD))
        assertEquals(WearCommand.SkipBack, tileCommandFor(TileClicks.SKIP_BACK))
        assertNull(tileCommandFor(TileClicks.OPEN_APP))
        // What an ordinary refresh, with nothing tapped, arrives as.
        assertNull(tileCommandFor(""))
    }

    /**
     * The bug this guards: a zero-weight row child collapses and the renderer stretches its sibling
     * over the whole width, so an unstarted episode would show a *full* bar and a finished one an
     * empty one — both of them confidently wrong.
     */
    @Test
    fun `neither half of the progress bar is ever given no width`() {
        assertTrue(playedBarWeight(0f) > 0f)
        assertTrue(playedBarWeight(1f) < 1f)
        assertEquals(0.5f, playedBarWeight(0.5f), TOLERANCE)
    }

    @Test
    fun `play state guesses only at what the tile draws`() {
        assertFalse(playing.optimistically(WearCommand.TogglePlayPause).isPlaying)
        assertTrue(
            playing.copy(isPlaying = false).optimistically(WearCommand.TogglePlayPause).isPlaying,
        )
        // A skip moves a position the next real snapshot corrects within a second; guessing at it
        // would mean re-implementing the phone's configured interval on the watch.
        assertEquals(playing, playing.optimistically(WearCommand.SkipForward))
    }

/**
     * How the rendered layout spells one string property, so a substring match cannot hit a longer
     * value that merely starts the same way.
     */
    private fun propertyValue(value: String): String = "value=$value,"

    /** Builds the layout and reads it back as text, which is all a proto can be asserted on. */
    private fun render(
        snapshot: NowPlayingSnapshot,
        commandFailed: Boolean = false,
    ): String {
        val layout = nowPlayingTileLayout(
            snapshot = snapshot,
            positionMs = snapshot.positionMs,
            accentArgb = ACCENT,
            copy = copy,
            commandFailed = commandFailed,
            packageName = "md.borisveriga.bpodcat",
        )
        return LayoutElementBuilders.Layout.fromLayoutElement(layout).toString()
    }

    private companion object {
        const val ACCENT = 0xFF7CC6FF.toInt()
        const val TOLERANCE = 0.0001f
    }
}
