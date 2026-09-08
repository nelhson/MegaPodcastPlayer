package md.borisveriga.megapodcastplayer.widget

import md.borisveriga.megapodcastplayer.core.media.PlayableEpisode
import md.borisveriga.megapodcastplayer.core.media.PlaybackState
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.EpisodeWithShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [widgetSnapshot].
 *
 * The widget is the one surface here that draws with no app on screen and, most of the time, with
 * no app process either — so what it says when *nothing is playing* is the whole design, and it is
 * arithmetic rather than layout. The cases below are the four states a home screen can catch this
 * app in: playing, paused, closed with something to carry on with, and freshly installed.
 *
 * The last two assertions are about cost rather than correctness. A snapshot that differs from its
 * predecessor is a `RemoteViews` tree crossing into the launcher's process, and the player emits
 * twice a second, so a snapshot that changed on every emission would be a widget that redrew itself
 * a hundred times a minute.
 */
class WidgetSnapshotTest {

    private fun episode(
        id: String,
        title: String = "Episode $id",
        positionMs: Long = 0L,
        durationMs: Long? = 3_600_000L,
    ) = Episode(
        id = id,
        podcastId = "p1",
        guid = id,
        title = title,
        description = "",
        audioUrl = "https://example.com/$id.mp3",
        artworkUrl = "https://example.com/$id.jpg",
        durationMs = durationMs,
        publishedAt = null,
        sizeBytes = null,
        positionMs = positionMs,
    )

    private fun shelfEntry(id: String, positionMs: Long = 60_000L) =
        EpisodeWithShow(episode(id, positionMs = positionMs), "Podlodka Podcast", null)

    private fun playable(id: String, positionMs: Long = 0L) =
        PlayableEpisode(episode(id, positionMs = positionMs), "Podlodka Podcast", null)

    private val playing = PlaybackState(
        isConnected = true,
        episodeId = "e1",
        title = "Podlodka #492",
        showTitle = "Podlodka Podcast",
        artworkUrl = "https://example.com/e1.jpg",
        isPlaying = true,
        positionMs = 900_000L,
        durationMs = 3_600_000L,
    )

    @Test
    fun `a loaded episode is what the transport acts on`() {
        val snapshot = widgetSnapshot(playing, resumable = null, inProgress = emptyList(), shelfLimit = 4)

        assertEquals("e1", snapshot.episode?.id)
        assertEquals("Podlodka #492", snapshot.episode?.title)
        assertTrue(snapshot.isLoaded)
        assertTrue(snapshot.isPlaying)
        assertEquals(25, snapshot.progressPercent)
    }

    @Test
    fun `a loaded episode that is paused is still loaded`() {
        // The difference the widget draws between these two is one glyph. The difference between
        // either of them and the next case is the entire transport.
        val snapshot = widgetSnapshot(
            playing.copy(isPlaying = false),
            resumable = null,
            inProgress = emptyList(),
            shelfLimit = 4,
        )

        assertTrue(snapshot.isLoaded)
        assertFalse(snapshot.isPlaying)
    }

    @Test
    fun `with nothing loaded the widget offers what the app would carry on with`() {
        // The state a home screen finds this app in most of the time: the process is not running,
        // so there is no player to ask, and `resumableQueue` is the same answer the launcher's
        // Resume shortcut gives.
        val snapshot = widgetSnapshot(
            PlaybackState(),
            resumable = playable("e9", positionMs = 1_800_000L),
            inProgress = emptyList(),
            shelfLimit = 4,
        )

        assertEquals("e9", snapshot.episode?.id)
        assertFalse(snapshot.isLoaded)
        assertFalse(snapshot.isPlaying)
        // Half an hour into an hour, taken from the stored position rather than from a player that
        // is not running.
        assertEquals(50, snapshot.progressPercent)
    }

    @Test
    fun `a resumable episode is never reported as playing`() {
        // `isPlaying` comes from the player, and a player with nothing loaded cannot be playing —
        // but the flag it carries is whatever it was left at, so the mapper has to say so.
        val snapshot = widgetSnapshot(
            PlaybackState(isPlaying = true),
            resumable = playable("e9"),
            inProgress = emptyList(),
            shelfLimit = 4,
        )

        assertFalse(snapshot.isPlaying)
        assertFalse(snapshot.isLoaded)
    }

    @Test
    fun `a fresh install has nothing to say and says so`() {
        val snapshot = widgetSnapshot(PlaybackState(), null, emptyList(), shelfLimit = 4)

        assertNull(snapshot.episode)
        assertTrue(snapshot.isEmpty)
        assertEquals(0, snapshot.progressPercent)
    }

    @Test
    fun `the shelf never repeats the episode above it`() {
        // The loaded episode is the most half-finished thing the user owns, so it heads the
        // *Continue listening* shelf as well. Without this the widget shows the same cover twice.
        val snapshot = widgetSnapshot(
            playing,
            resumable = null,
            inProgress = listOf(shelfEntry("e1"), shelfEntry("e2"), shelfEntry("e3")),
            shelfLimit = 4,
        )

        assertEquals(listOf("e2", "e3"), snapshot.continueListening.map { it.id })
    }

    @Test
    fun `the shelf drops the resumable episode too, not just the loaded one`() {
        val snapshot = widgetSnapshot(
            PlaybackState(),
            resumable = playable("e2"),
            inProgress = listOf(shelfEntry("e2"), shelfEntry("e3")),
            shelfLimit = 4,
        )

        assertEquals(listOf("e3"), snapshot.continueListening.map { it.id })
    }

    @Test
    fun `the shelf is trimmed after the episode above it has been removed, not before`() {
        // The order matters. Trimming first would leave a shelf of three when the fourth was
        // dropped for being the one playing, which is a home screen with a gap in it.
        val snapshot = widgetSnapshot(
            playing,
            resumable = null,
            inProgress = listOf("e1", "e2", "e3", "e4", "e5").map(::shelfEntry),
            shelfLimit = 4,
        )

        assertEquals(listOf("e2", "e3", "e4", "e5"), snapshot.continueListening.map { it.id })
    }

    @Test
    fun `an episode of unknown length reports no progress rather than a wrong one`() {
        val snapshot = widgetSnapshot(
            PlaybackState(),
            resumable = playable("e9", positionMs = 1_800_000L).let {
                PlayableEpisode(it.episode.copy(durationMs = null), it.showTitle, it.showArtworkUrl)
            },
            inProgress = emptyList(),
            shelfLimit = 4,
        )

        assertEquals(0, snapshot.progressPercent)
    }

    @Test
    fun `a second of playback does not produce a different snapshot`() {
        // The reason progress is whole percent. Two positions half a second apart in an hour-long
        // episode are the same picture, and a snapshot that said otherwise would push a RemoteViews
        // tree across a process boundary to redraw an identical bar.
        val first = widgetSnapshot(playing, null, emptyList(), shelfLimit = 4)
        val aMomentLater = widgetSnapshot(
            playing.copy(positionMs = playing.positionMs + 500L),
            null,
            emptyList(),
            shelfLimit = 4,
        )

        assertEquals(first, aMomentLater)
    }

    @Test
    fun `enough playback does produce a different snapshot`() {
        // The other half of the same claim: quantising must not mean the bar stops moving.
        val first = widgetSnapshot(playing, null, emptyList(), shelfLimit = 4)
        val muchLater = widgetSnapshot(
            playing.copy(positionMs = playing.positionMs + 60_000L),
            null,
            emptyList(),
            shelfLimit = 4,
        )

        assertEquals(27, muchLater.progressPercent)
        assertTrue(first != muchLater)
    }
}
