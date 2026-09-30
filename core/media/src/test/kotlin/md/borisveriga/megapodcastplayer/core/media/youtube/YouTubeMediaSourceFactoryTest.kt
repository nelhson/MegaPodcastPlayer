package md.borisveriga.megapodcastplayer.core.media.youtube

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.core.app.ApplicationProvider
import md.borisveriga.megapodcastplayer.core.model.youTubeAudioSentinel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [YouTubeMediaSourceFactory].
 *
 * The property that matters most is the negative one: every item that is *not* a video sentinel,
 * which is every episode ever stored, must reach Media3's default factory exactly as before.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YouTubeMediaSourceFactoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dataSourceFactory = DefaultDataSource.Factory(context)
    private val factory = YouTubeMediaSourceFactory(
        DefaultMediaSourceFactory(dataSourceFactory),
        dataSourceFactory,
    )

    private fun item(uri: String): MediaItem =
        MediaItem.Builder().setMediaId("ep-1").setUri(uri).build()

    @Test
    fun `a video sentinel becomes a merged source that still names the video item`() {
        val video = item("youtube://video-only/niTJ2221aS8?h=720")

        val source = factory.createMediaSource(video)

        assertTrue(source is MergingMediaSource)
        // The merged source reports its first child's item, which is how the rest of the app tells
        // the picture is showing.
        assertEquals(video, source.mediaItem)
    }

    @Test
    fun `the audio sentinel takes the default path`() {
        val source = factory.createMediaSource(item(youTubeAudioSentinel("niTJ2221aS8")))

        assertTrue(source is ProgressiveMediaSource)
    }

    @Test
    fun `an ordinary enclosure takes the default path`() {
        val source = factory.createMediaSource(item("https://cdn.example.com/episode-42.mp3"))

        assertTrue(source is ProgressiveMediaSource)
    }
}
