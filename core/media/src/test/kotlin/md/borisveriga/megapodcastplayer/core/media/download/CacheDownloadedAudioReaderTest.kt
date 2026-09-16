package md.borisveriga.megapodcastplayer.core.media.download

import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [CacheDownloadedAudioReader], against a real [SimpleCache] on disk.
 *
 * A fake cache would only prove the reader agrees with the fake; what matters is that bytes Media3
 * wrote under a stored audio URL come back identical, and that a download Media3 never finished is
 * recognised as unfinished rather than exported short.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CacheDownloadedAudioReaderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var cache: SimpleCache
    private lateinit var reader: CacheDownloadedAudioReader

    @Before
    fun setUp() {
        cache = SimpleCache(
            temporaryFolder.newFolder("cache"),
            NoOpCacheEvictor(),
            StandaloneDatabaseProvider(ApplicationProvider.getApplicationContext()),
        )
        reader = CacheDownloadedAudioReader(cache)
    }

    @After
    fun tearDown() {
        cache.release()
    }

    /** Writes [bytes] into the cache under [key], reading at most [length] of them. */
    private fun write(key: String, bytes: ByteArray, length: Long = C.LENGTH_UNSET.toLong()) {
        val source = CacheDataSource(cache, ByteArrayDataSource(bytes))
        val spec = DataSpec.Builder().setUri(key).setLength(length).build()
        CacheWriter(source, spec, /* temporaryBuffer = */ null, /* progressListener = */ null).cache()
    }

    @Test
    fun `a whole download reads back byte for byte`() {
        val audio = Random(7).nextBytes(300_000)
        write("youtube://video/abc", audio)

        assertTrue(reader.isFullyDownloaded("youtube://video/abc"))
        val read = reader.open("youtube://video/abc").use { it.readBytes() }
        assertArrayEquals(audio, read)
    }

    @Test
    fun `a partly cached download is not fully downloaded`() {
        write("https://cdn.example.com/1.mp3", Random(1).nextBytes(10_000), length = 4_000L)

        assertFalse(reader.isFullyDownloaded("https://cdn.example.com/1.mp3"))
    }

    @Test
    fun `a whole download knows its length`() {
        write("youtube://video/abc", Random(3).nextBytes(12_345))

        assertEquals(12_345L, reader.contentLength("youtube://video/abc"))
    }

    @Test
    fun `an episode never downloaded has no length`() {
        assertNull(reader.contentLength("https://cdn.example.com/none.mp3"))
    }

    @Test
    fun `an episode never downloaded is not fully downloaded`() {
        assertFalse(reader.isFullyDownloaded("https://cdn.example.com/none.mp3"))
    }

    @Test(expected = IOException::class)
    fun `reading an uncached episode fails instead of reaching the network`() {
        reader.open("https://cdn.example.com/none.mp3").use { it.read() }
    }
}
