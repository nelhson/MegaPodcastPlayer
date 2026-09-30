package md.borisveriga.megapodcastplayer.core.media.download

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.storage.StorageManager
import androidx.core.net.toUri
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadCursor
import androidx.media3.exoplayer.offline.DownloadIndex
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadProgress
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.VideoDownload
import md.borisveriga.megapodcastplayer.core.model.VideoQuality
import md.borisveriga.megapodcastplayer.core.model.youTubeVideoOnlySentinel
import md.borisveriga.megapodcastplayer.core.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tests for [EpisodeDownloader].
 *
 * The downloader is a thin adapter over Media3, so what is worth pinning is the translation at
 * each edge: the intents it sends to [EpisodeDownloadService], the statuses it derives from the
 * manager's callbacks and index, and the failures it deliberately turns into "nothing happened" —
 * a refused foreground start, an unreadable index, a cache that cannot be measured. The manager
 * itself is a mock; driving a real one would test Media3, not this class.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EpisodeDownloaderTest {

    /** Everything that touches the manager hops to `Dispatchers.Main`; this makes that a no-op. */
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val downloadManager: DownloadManager = mockk(relaxed = true)
    private val cache: Cache = mockk()

    /** A downloader over the mocked manager, sending its intents through [context]. */
    private fun downloader(context: Context = application) = EpisodeDownloader(
        context = context,
        downloadManager = downloadManager,
        cache = cache,
        ioDispatcher = mainDispatcherRule.dispatcher,
    )

    @Test
    fun `download sends an add request with the episode id as content id`() = runTest {
        downloader().download("ep-1", "https://example.com/1.mp3")

        val intent = nextServiceIntent()
        assertEquals(DownloadService.ACTION_ADD_DOWNLOAD, intent.action)
        assertTrue(intent.getBooleanExtra(DownloadService.KEY_FOREGROUND, false))
        val request = intent.getParcelableExtra<DownloadRequest>(DownloadService.KEY_DOWNLOAD_REQUEST)
        assertNotNull(request)
        assertEquals("ep-1", request?.id)
        assertEquals("https://example.com/1.mp3".toUri(), request?.uri)
        // No custom cache key: the player and the downloader must agree on the URL as the key, or
        // a downloaded episode streams all over again.
        assertNull(request?.customCacheKey)
    }

    @Test
    fun `a background download asks for a background start`() = runTest {
        downloader().download("ep-1", "https://example.com/1.mp3", foreground = false)

        val intent = nextServiceIntent()
        assertEquals(DownloadService.ACTION_ADD_DOWNLOAD, intent.action)
        assertFalse(intent.getBooleanExtra(DownloadService.KEY_FOREGROUND, true))
    }

    @Test
    fun `remove sends a remove request for that episode`() = runTest {
        downloader().remove("ep-2")

        val intent = nextServiceIntent()
        assertEquals(DownloadService.ACTION_REMOVE_DOWNLOAD, intent.action)
        assertEquals("ep-2", intent.getStringExtra(DownloadService.KEY_CONTENT_ID))
    }

    @Test
    fun `remove all sends the bulk removal request`() = runTest {
        downloader().removeAll()

        assertEquals(DownloadService.ACTION_REMOVE_ALL_DOWNLOADS, nextServiceIntent().action)
    }

    @Test
    fun `a refused foreground start is swallowed`() = runTest {
        // What Android throws when a periodic refresh tries to auto-download from the background.
        // Media3's scheduler will retry; the refresh must not crash.
        val refusing: Context = mockk(relaxed = true) {
            every { startForegroundService(any()) } throws IllegalStateException("not allowed")
        }

        downloader(refusing).download("ep-1", "https://example.com/1.mp3")
    }

    @Test
    fun `status updates mirror the manager's change and removal events`() = runTest {
        val listener = slot<DownloadManager.Listener>()
        every { downloadManager.addListener(capture(listener)) } returns Unit

        downloader().statusUpdates.test {
            listener.captured.onDownloadChanged(
                downloadManager,
                download("ep-1", Download.STATE_DOWNLOADING, bytesDownloaded = 500L, percent = 5f),
                /* finalException = */ null,
            )
            val downloading = awaitItem()
            assertEquals("ep-1", downloading.episodeId)
            assertEquals(DownloadState.DOWNLOADING, downloading.state)
            assertEquals(500L, downloading.downloadedBytes)

            listener.captured.onDownloadRemoved(
                downloadManager,
                download("ep-1", Download.STATE_REMOVING, bytesDownloaded = 500L, percent = 5f),
            )
            assertEquals(EpisodeDownloadStatus.notDownloaded("ep-1"), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }

        verify(exactly = 1) { downloadManager.removeListener(listener.captured) }
    }

    @Test
    fun `current statuses read every row of the index`() = runTest {
        val index: DownloadIndex = mockk()
        every { downloadManager.downloadIndex } returns index
        every { index.getDownloads() } returns FakeDownloadCursor(
            listOf(
                download("ep-1", Download.STATE_COMPLETED, bytesDownloaded = 1_000L, percent = 99f),
                download("ep-2", Download.STATE_QUEUED, bytesDownloaded = 0L, percent = Float.NaN),
            ),
        )

        val statuses = downloader().currentStatuses()

        assertEquals(listOf("ep-1", "ep-2"), statuses.map { it.episodeId })
        assertEquals(DownloadState.COMPLETED, statuses[0].state)
        assertEquals(100f, statuses[0].percent, 0.001f)
        assertEquals(DownloadState.QUEUED, statuses[1].state)
    }

    @Test
    fun `an unreadable index yields an empty list rather than a crash`() = runTest {
        val index: DownloadIndex = mockk()
        every { downloadManager.downloadIndex } returns index
        every { index.getDownloads() } throws IOException("corrupt")

        assertTrue(downloader().currentStatuses().isEmpty())
    }

    @Test
    fun `downloaded bytes come from the cache`() = runTest {
        every { cache.cacheSpace } returns 123_456L

        assertEquals(123_456L, downloader().downloadedBytes())
    }

    @Test
    fun `an unmeasurable cache reports zero`() = runTest {
        every { cache.cacheSpace } throws IllegalStateException("released")

        assertEquals(0L, downloader().downloadedBytes())
    }

    @Test
    fun `unanswerable storage reports zero free bytes`() = runTest {
        val broken: Context = mockk(relaxed = true) {
            every { getSystemService(StorageManager::class.java) } throws IllegalStateException()
        }

        assertEquals(0L, downloader(broken).freeBytes())
    }

    @Test
    fun `unmetered-only switches the manager's requirements`() = runTest {
        val downloader = downloader()

        downloader.setUnmeteredOnly(true)
        verify { downloadManager.requirements = Requirements(Requirements.NETWORK_UNMETERED) }

        downloader.setUnmeteredOnly(false)
        verify { downloadManager.requirements = Requirements(Requirements.NETWORK) }
    }

    @Test
    fun `remove takes the episode's picture as well as its sound`() = runTest {
        downloader().remove("ep-2")

        assertEquals("ep-2", nextServiceIntent().getStringExtra(DownloadService.KEY_CONTENT_ID))
        val picture = nextServiceIntent()
        assertEquals(DownloadService.ACTION_REMOVE_DOWNLOAD, picture.action)
        assertEquals("ep-2#video", picture.getStringExtra(DownloadService.KEY_CONTENT_ID))
    }

    @Test
    fun `removing the picture leaves the sound alone`() = runTest {
        downloader().removeVideo("ep-2")

        assertEquals("ep-2#video", nextServiceIntent().getStringExtra(DownloadService.KEY_CONTENT_ID))
        assertNull(shadowOf(application).nextStartedService)
    }

    @Test
    fun `a picture is downloaded under the sentinel the player asks for`() = runTest {
        givenIndex()

        downloader().downloadVideo("ep-1", VIDEO_ID, VideoQuality(1080))

        val intent = nextServiceIntent()
        assertEquals(DownloadService.ACTION_ADD_DOWNLOAD, intent.action)
        val request = intent.getParcelableExtra<DownloadRequest>(DownloadService.KEY_DOWNLOAD_REQUEST)
        assertEquals("ep-1#video", request?.id)
        // The exact string the player mints for 1080p: anything else and the download is never
        // found, and the video streams while its copy sits on disk.
        assertEquals(youTubeVideoOnlySentinel(VIDEO_ID, VideoQuality(1080)).toUri(), request?.uri)
        assertNull(request?.customCacheKey)
        assertNull(shadowOf(application).nextStartedService)
    }

    @Test
    fun `asking again for the height already kept adds without removing`() = runTest {
        val sentinel = youTubeVideoOnlySentinel(VIDEO_ID, VideoQuality(720))
        givenIndex(download("ep-1#video", Download.STATE_COMPLETED, uri = sentinel))

        downloader().downloadVideo("ep-1", VIDEO_ID, VideoQuality(720))

        assertEquals(DownloadService.ACTION_ADD_DOWNLOAD, nextServiceIntent().action)
        assertNull(shadowOf(application).nextStartedService)
    }

    @Test
    fun `another height removes the picture kept before queuing the new one`() = runTest {
        val old = youTubeVideoOnlySentinel(VIDEO_ID, VideoQuality(720))
        givenIndex(download("ep-1#video", Download.STATE_COMPLETED, uri = old))

        downloader().downloadVideo("ep-1", VIDEO_ID, VideoQuality(1080))

        val removal = nextServiceIntent()
        assertEquals(DownloadService.ACTION_REMOVE_DOWNLOAD, removal.action)
        assertEquals("ep-1#video", removal.getStringExtra(DownloadService.KEY_CONTENT_ID))
        val add = nextServiceIntent()
        assertEquals(DownloadService.ACTION_ADD_DOWNLOAD, add.action)
        val request = add.getParcelableExtra<DownloadRequest>(DownloadService.KEY_DOWNLOAD_REQUEST)
        assertEquals(youTubeVideoOnlySentinel(VIDEO_ID, VideoQuality(1080)).toUri(), request?.uri)
    }

    @Test
    fun `status updates and current statuses leave pictures out`() = runTest {
        val sentinel = youTubeVideoOnlySentinel(VIDEO_ID, VideoQuality(720))
        givenIndex(
            download("ep-1", Download.STATE_COMPLETED),
            download("ep-1#video", Download.STATE_DOWNLOADING, uri = sentinel),
        )
        val listener = slot<DownloadManager.Listener>()
        every { downloadManager.addListener(capture(listener)) } returns Unit
        val downloader = downloader()

        assertEquals(listOf("ep-1"), downloader.currentStatuses().map { it.episodeId })
        downloader.statusUpdates.test {
            val picture = download("ep-1#video", Download.STATE_DOWNLOADING, uri = sentinel)
            listener.captured.onDownloadChanged(downloadManager, picture, null)
            listener.captured.onDownloadRemoved(downloadManager, picture)
            listener.captured.onDownloadChanged(downloadManager, download("ep-2", Download.STATE_QUEUED), null)
            // The sound of ep-2 is the first thing through: neither picture event was.
            assertEquals("ep-2", awaitItem().episodeId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `video downloads start from the index and follow the manager`() = runTest {
        val sentinel = youTubeVideoOnlySentinel(VIDEO_ID, VideoQuality(720))
        givenIndex(
            download("ep-1", Download.STATE_COMPLETED),
            download("ep-1#video", Download.STATE_COMPLETED, uri = sentinel),
        )
        val listener = slot<DownloadManager.Listener>()
        every { downloadManager.addListener(capture(listener)) } returns Unit

        downloader().videoDownloads.test {
            assertEquals(
                mapOf("ep-1" to VideoDownload(VideoQuality(720), DownloadState.COMPLETED, 100f)),
                awaitItem(),
            )

            val other = youTubeVideoOnlySentinel("otherVideo1", VideoQuality(1080))
            listener.captured.onDownloadChanged(
                downloadManager,
                download("ep-2#video", Download.STATE_DOWNLOADING, percent = 40f, uri = other),
                null,
            )
            assertEquals(
                VideoDownload(VideoQuality(1080), DownloadState.DOWNLOADING, 40f),
                awaitItem()["ep-2"],
            )

            listener.captured.onDownloadRemoved(
                downloadManager,
                download("ep-1#video", Download.STATE_REMOVING, uri = sentinel),
            )
            assertEquals(setOf("ep-2"), awaitItem().keys)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a picture change heard while the index is read beats the older snapshot`() = runTest {
        val sentinel = youTubeVideoOnlySentinel(VIDEO_ID, VideoQuality(720))
        val listener = slot<DownloadManager.Listener>()
        every { downloadManager.addListener(capture(listener)) } returns Unit
        val index: DownloadIndex = mockk()
        every { downloadManager.downloadIndex } returns index
        every { index.getDownloads() } answers {
            // The removal lands after the listener is registered and before the snapshot, which
            // still lists the picture, is applied.
            listener.captured.onDownloadRemoved(
                downloadManager,
                download("ep-1#video", Download.STATE_REMOVING, uri = sentinel),
            )
            FakeDownloadCursor(listOf(download("ep-1#video", Download.STATE_COMPLETED, uri = sentinel)))
        }

        downloader().videoDownloads.test {
            assertEquals(emptyMap<String, VideoDownload>(), expectMostRecentItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Makes the manager's index hold exactly [downloads]. */
    private fun givenIndex(vararg downloads: Download) {
        val index: DownloadIndex = mockk()
        every { downloadManager.downloadIndex } returns index
        every { index.getDownloads() } answers { FakeDownloadCursor(downloads.toList()) }
        every { index.getDownload(any()) } answers {
            downloads.firstOrNull { it.request.id == firstArg<String>() }
        }
    }

    /** The next intent sent to a service, which must be [EpisodeDownloadService]. */
    private fun nextServiceIntent(): Intent {
        val intent = shadowOf(application).nextStartedService
        assertNotNull("no service was started", intent)
        assertEquals(EpisodeDownloadService::class.java.name, intent.component?.className)
        return intent
    }

    /**
     * A [Download] with content id [id] in [state] with the given progress, fetching [uri]; an
     * episode's audio unless told otherwise.
     */
    private fun download(
        id: String,
        state: Int,
        bytesDownloaded: Long = 0L,
        percent: Float = 0f,
        uri: String = "https://example.com/$id.mp3",
    ): Download = Download(
        DownloadRequest.Builder(id, uri.toUri()).build(),
        state,
        /* startTimeMs = */ 0L,
        /* updateTimeMs = */ 0L,
        /* contentLength = */ 10_000L,
        /* stopReason = */ Download.STOP_REASON_NONE,
        /* failureReason = */ Download.FAILURE_REASON_NONE,
        DownloadProgress().apply {
            this.bytesDownloaded = bytesDownloaded
            this.percentDownloaded = percent
        },
    )

    private companion object {
        /** The YouTube video behind the episode whose picture is downloaded. */
        const val VIDEO_ID = "niTJ2221aS8"
    }

    /** An in-memory [DownloadCursor] over a fixed list, as the index would return. */
    private class FakeDownloadCursor(private val downloads: List<Download>) : DownloadCursor {
        private var position = -1
        private var closed = false

        override fun getDownload(): Download = downloads[position]

        override fun getCount(): Int = downloads.size

        override fun getPosition(): Int = position

        override fun moveToPosition(position: Int): Boolean {
            this.position = position
            return position in downloads.indices
        }

        override fun isClosed(): Boolean = closed

        override fun close() {
            closed = true
        }
    }
}
