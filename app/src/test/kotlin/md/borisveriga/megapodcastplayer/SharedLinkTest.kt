package md.borisveriga.megapodcastplayer

import android.content.Intent
import androidx.core.net.toUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the share-and-tap entry point.
 *
 * This is the app's only door that anything else on the device can knock on, so both halves matter:
 * a real podcast link has to get through — the whole point is that adding a show stops being five
 * steps — and everything else has to be turned away here rather than reaching the add screen and
 * failing there. The manifest narrows the hosts; this narrows what those hosts may say.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedLinkTest {

    private fun shared(text: String?) = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }

    private fun tapped(url: String) = Intent(Intent.ACTION_VIEW, url.toUri())

    @Test
    fun `a shared Apple Podcasts link is offered`() {
        val link = "https://podcasts.apple.com/us/podcast/podlodka-podcast/id1209828744"

        assertEquals(link, shared(link).podcastLinkOrNull())
    }

    @Test
    fun `a link shared inside a sentence is picked out of it`() {
        // What a share from a chat app actually looks like.
        val intent = shared("listen to this one https://podcasts.apple.com/us/podcast/x/id1209828744 it is good")

        assertEquals(
            "https://podcasts.apple.com/us/podcast/x/id1209828744",
            intent.podcastLinkOrNull(),
        )
    }

    @Test
    fun `a shared YouTube playlist is offered`() {
        val link = "https://www.youtube.com/playlist?list=PLabcdefghijklmnop"

        assertEquals(link, shared(link).podcastLinkOrNull())
    }

    @Test
    fun `a shared feed URL is offered`() {
        val link = "https://podlodka.io/rss"

        assertEquals(link, shared(link).podcastLinkOrNull())
    }

    @Test
    fun `shared text with no link in it is turned away`() {
        assertNull(shared("have you heard the new episode?").podcastLinkOrNull())
        assertNull(shared("").podcastLinkOrNull())
        assertNull(shared(null).podcastLinkOrNull())
    }

    @Test
    fun `a tapped Apple link is offered`() {
        val link = "https://podcasts.apple.com/gb/podcast/podlodka/id1209828744"

        assertEquals(link, tapped(link).podcastLinkOrNull())
    }

    @Test
    fun `a tapped Apple page that names no show is turned away`() {
        // The manifest lets every apple.com podcast URL through; a charts or genre page is one of
        // them, and there is nothing to add from it.
        assertNull(tapped("https://podcasts.apple.com/us/charts").podcastLinkOrNull())
    }

    @Test
    fun `a YouTube link with no usable playlist is turned away`() {
        // A bare video, and the two playlists YouTube regenerates per viewer, whose feeds are
        // empty or private.
        assertNull(tapped("https://www.youtube.com/watch?v=dQw4w9WgXcQ").podcastLinkOrNull())
        assertNull(tapped("https://www.youtube.com/playlist?list=WL").podcastLinkOrNull())
    }

    @Test
    fun `the feed schemes are rewritten to the transport they mean`() {
        // `podcast://` and `feed://` are how a directory page offers a feed; neither is fetchable.
        assertEquals("https://podlodka.io/rss", tapped("podcast://podlodka.io/rss").podcastLinkOrNull())
        assertEquals("https://podlodka.io/rss", tapped("feed://podlodka.io/rss").podcastLinkOrNull())
    }

    @Test
    fun `an intent asking for something else is ignored`() {
        assertNull(Intent(Intent.ACTION_MAIN).podcastLinkOrNull())
    }
}
