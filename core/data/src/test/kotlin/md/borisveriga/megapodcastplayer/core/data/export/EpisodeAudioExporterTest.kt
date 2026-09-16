package md.borisveriga.megapodcastplayer.core.data.export

import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter
import md.borisveriga.megapodcastplayer.core.database.dao.EpisodeDao
import md.borisveriga.megapodcastplayer.core.database.dao.PodcastDao
import md.borisveriga.megapodcastplayer.core.database.model.EpisodeEntity
import md.borisveriga.megapodcastplayer.core.database.model.PodcastEntity
import md.borisveriga.megapodcastplayer.core.media.download.DownloadedAudioReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [EpisodeAudioExporter], against an in-memory folder and cache.
 *
 * The cases are the ones the user can see in the folder afterwards: files in order under the right
 * names, nothing overwritten on a second run, no half-written file left behind by a failure or a
 * cancellation, and one bad episode not costing the rest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EpisodeAudioExporterTest {

    private val m4a = byteArrayOf(0, 0, 0, 0x20) + "ftypM4A ".toByteArray() + ByteArray(200_000) { 7 }
    private val mp3 = "ID3".toByteArray() + ByteArray(1_000) { 3 }

    private lateinit var podcastDao: PodcastDao
    private lateinit var episodeDao: EpisodeDao
    private lateinit var crashReporter: CrashReporter
    private lateinit var reader: FakeReader
    private lateinit var directory: FakeDirectory
    private lateinit var exporter: EpisodeAudioExporter

    private val show = PodcastEntity(
        id = "show",
        itunesId = null,
        title = "Talks: 2026",
        author = "",
        feedUrl = "https://www.youtube.com/playlist?list=PL1",
        artworkUrl = null,
        description = "",
        addedAt = 0L,
        lastRefreshAt = null,
        etag = null,
        lastModified = null,
        autoRefresh = true,
    )

    private fun episode(id: String, title: String) = EpisodeEntity(
        id = id,
        podcastId = show.id,
        guid = id,
        title = title,
        description = "",
        audioUrl = "youtube://video/$id",
        artworkUrl = null,
        durationMs = null,
        publishedAt = null,
        sizeBytes = null,
    )

    @Before
    fun setUp() {
        podcastDao = mockk()
        episodeDao = mockk()
        crashReporter = mockk(relaxed = true)
        reader = FakeReader()
        directory = FakeDirectory()
        coEvery { podcastDao.getById(show.id) } returns show
        exporter = EpisodeAudioExporter(
            podcastDao = podcastDao,
            episodeDao = episodeDao,
            reader = reader,
            directory = directory,
            crashReporter = crashReporter,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
    }

    private fun givenEpisodes(vararg episodes: EpisodeEntity) {
        coEvery { episodeDao.getDownloadedForExport(show.id) } returns episodes.toList()
    }

    @Test
    fun `episodes are written in order into a folder named after the show`() = runTest {
        givenEpisodes(episode("a", "First"), episode("b", "Second"))
        reader.audio["youtube://video/a"] = m4a
        reader.audio["youtube://video/b"] = mp3
        val progress = mutableListOf<ExportProgress>()

        val summary = exporter.export(show.id, "tree", onProgress = { progress += it }).getOrThrow()

        assertEquals(ExportSummary(exported = 2, alreadyThere = 0, failed = 0), summary)
        val folder = directory.folders.getValue("Talks 2026")
        assertArrayEquals(m4a, folder.getValue("001 - First.m4a").bytes.toByteArray())
        assertArrayEquals(mp3, folder.getValue("002 - Second.mp3").bytes.toByteArray())
        assertEquals("audio/mp4", folder.getValue("001 - First.m4a").mimeType)
        assertEquals(
            listOf(ExportProgress(0, 2), ExportProgress(1, 2), ExportProgress(2, 2)),
            progress,
        )
    }

    @Test
    fun `a second run leaves files already there alone`() = runTest {
        givenEpisodes(episode("a", "First"), episode("b", "Second"))
        reader.audio["youtube://video/a"] = m4a
        reader.audio["youtube://video/b"] = m4a
        exporter.export(show.id, "tree", onProgress = {}).getOrThrow()
        directory.created.clear()

        val summary = exporter.export(show.id, "tree", onProgress = {}).getOrThrow()

        assertEquals(ExportSummary(exported = 0, alreadyThere = 2, failed = 0), summary)
        assertTrue(directory.created.isEmpty())
        assertEquals(1, directory.folders.size)
    }

    @Test
    fun `a second run recognises files whose numbers have moved`() = runTest {
        givenEpisodes(episode("a", "First"), episode("b", "Second"))
        reader.audio["youtube://video/a"] = m4a
        reader.audio["youtube://video/b"] = mp3
        exporter.export(show.id, "tree", onProgress = {}).getOrThrow()
        directory.created.clear()

        // A new video joined the playlist at the top, so every earlier episode moved down one.
        givenEpisodes(episode("c", "Newest"), episode("a", "First"), episode("b", "Second"))
        reader.audio["youtube://video/c"] = mp3
        val summary = exporter.export(show.id, "tree", onProgress = {}).getOrThrow()

        assertEquals(ExportSummary(exported = 1, alreadyThere = 2, failed = 0), summary)
        assertEquals(listOf("001 - Newest.mp3"), directory.created)
    }

    @Test
    fun `a file cut short by an earlier run is replaced by a whole copy`() = runTest {
        givenEpisodes(episode("a", "First"))
        reader.audio["youtube://video/a"] = m4a
        // What a process killed mid-copy leaves: the right name, too few bytes.
        directory.folders["Talks 2026"] = mutableMapOf(
            "001 - First.m4a" to FakeFile("audio/mp4").apply { bytes.write(m4a, 0, 1_000) },
        )

        val summary = exporter.export(show.id, "tree", onProgress = {}).getOrThrow()

        assertEquals(ExportSummary(exported = 1, alreadyThere = 0, failed = 0), summary)
        val folder = directory.folders.getValue("Talks 2026")
        assertEquals(setOf("001 - First.m4a"), folder.keys)
        assertArrayEquals(m4a, folder.getValue("001 - First.m4a").bytes.toByteArray())
    }

    @Test
    fun `an incomplete download fails alone and is reported`() = runTest {
        givenEpisodes(episode("a", "First"), episode("b", "Second"))
        reader.audio["youtube://video/a"] = m4a
        reader.audio["youtube://video/b"] = m4a
        reader.incomplete += "youtube://video/a"

        val summary = exporter.export(show.id, "tree", onProgress = {}).getOrThrow()

        assertEquals(ExportSummary(exported = 1, alreadyThere = 0, failed = 1), summary)
        assertEquals(setOf("002 - Second.m4a"), directory.folders.getValue("Talks 2026").keys)
        verify { crashReporter.recordNonFatal(any(), any()) }
    }

    @Test
    fun `a copy that breaks off deletes its partial file and the export carries on`() = runTest {
        givenEpisodes(episode("a", "First"), episode("b", "Second"))
        reader.audio["youtube://video/a"] = m4a
        reader.audio["youtube://video/b"] = m4a
        reader.failAfterHeader += "youtube://video/a"

        val summary = exporter.export(show.id, "tree", onProgress = {}).getOrThrow()

        assertEquals(ExportSummary(exported = 1, alreadyThere = 0, failed = 1), summary)
        assertEquals(setOf("002 - Second.m4a"), directory.folders.getValue("Talks 2026").keys)
        verify { crashReporter.recordNonFatal("Episode audio export failed", any()) }
    }

    @Test
    fun `cancelling mid-copy deletes the partial file and is not counted as a failure`() = runTest {
        givenEpisodes(episode("a", "First"))
        reader.audio["youtube://video/a"] = m4a
        directory.cancelOnWrite = true

        val result = runCatching { exporter.export(show.id, "tree", onProgress = {}) }

        assertTrue(result.exceptionOrNull() is CancellationException)
        assertTrue(directory.folders.getValue("Talks 2026").isEmpty())
        verify(exactly = 0) { crashReporter.recordNonFatal(any(), any()) }
    }

    @Test
    fun `a folder that cannot be created fails the whole export`() = runTest {
        givenEpisodes(episode("a", "First"))
        directory.refuseFolders = true

        val result = exporter.export(show.id, "tree", onProgress = {})

        assertTrue(result.isFailure)
        verify { crashReporter.recordNonFatal("Episode audio export could not start", any()) }
    }

    @Test
    fun `a show that no longer exists fails the export`() = runTest {
        coEvery { podcastDao.getById("gone") } returns null

        assertTrue(exporter.export("gone", "tree", onProgress = {}).isFailure)
    }

    /** A cache holding whole audio by URL, with switches for the two ways reading goes wrong. */
    private class FakeReader : DownloadedAudioReader {
        val audio = mutableMapOf<String, ByteArray>()
        val incomplete = mutableSetOf<String>()
        val failAfterHeader = mutableSetOf<String>()

        override fun isFullyDownloaded(audioUrl: String): Boolean =
            audioUrl in audio && audioUrl !in incomplete

        override fun contentLength(audioUrl: String): Long? = audio[audioUrl]?.size?.toLong()

        override fun open(audioUrl: String): InputStream {
            val bytes = audio.getValue(audioUrl)
            if (audioUrl !in failAfterHeader) return ByteArrayInputStream(bytes)
            return object : InputStream() {
                private val header = ByteArrayInputStream(bytes.copyOf(12))
                override fun read(): Int = header.read().also { if (it < 0) throw IOException("gone") }
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    header.read(b, off, len).also { if (it < 0) throw IOException("gone") }
            }
        }
    }

    /**
     * A single picked folder holding named sub-folders of files.
     *
     * Locations are `folder` for a sub-folder and `folder/name` for a file.
     */
    private class FakeDirectory : ExportDirectory {
        val folders = mutableMapOf<String, MutableMap<String, FakeFile>>()
        val created = mutableListOf<String>()
        var refuseFolders = false
        var cancelOnWrite = false

        override fun root(treeUri: String): String = treeUri

        override fun findOrCreateFolder(parent: String, name: String): String {
            if (refuseFolders) throw IOException("No permission")
            folders.getOrPut(name) { mutableMapOf() }
            return name
        }

        override fun files(folder: String): List<ExportedFile> =
            folders.getValue(folder).map { (name, file) ->
                ExportedFile("$folder/$name", name, file.bytes.size().toLong())
            }

        override fun createFile(folder: String, name: String, mimeType: String): String {
            folders.getValue(folder)[name] = FakeFile(mimeType)
            created += name
            return "$folder/$name"
        }

        override fun openOutput(file: String): OutputStream {
            val target = locate(file).bytes
            if (!cancelOnWrite) return target
            return object : OutputStream() {
                override fun write(b: Int) = throw CancellationException("Stopped")
            }
        }

        override fun delete(file: String) {
            val (folder, name) = file.split("/", limit = 2)
            folders.getValue(folder).remove(name)
        }

        private fun locate(file: String): FakeFile {
            val (folder, name) = file.split("/", limit = 2)
            return folders.getValue(folder).getValue(name)
        }
    }

    /** One file's type and contents. */
    private class FakeFile(val mimeType: String) {
        val bytes = ByteArrayOutputStream()
    }
}
