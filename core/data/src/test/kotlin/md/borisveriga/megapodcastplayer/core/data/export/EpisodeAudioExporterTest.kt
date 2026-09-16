package md.borisveriga.megapodcastplayer.core.data.export

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter
import md.borisveriga.megapodcastplayer.core.data.repository.DownloadRepository
import md.borisveriga.megapodcastplayer.core.database.dao.EpisodeDao
import md.borisveriga.megapodcastplayer.core.database.dao.PodcastDao
import md.borisveriga.megapodcastplayer.core.database.model.DownloadListRowEntity
import md.borisveriga.megapodcastplayer.core.database.model.EpisodeEntity
import md.borisveriga.megapodcastplayer.core.database.model.PodcastEntity
import md.borisveriga.megapodcastplayer.core.media.download.DownloadedAudioReader
import md.borisveriga.megapodcastplayer.core.model.DownloadState
import md.borisveriga.megapodcastplayer.core.model.EpisodeFilter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [EpisodeAudioExporter], against an in-memory folder and cache.
 *
 * The cases are the ones the user can see in the folder afterwards: files in order under the right
 * names, nothing overwritten on a second run, no half-written file left behind by a failure or a
 * cancellation, and one bad episode not costing the rest. Before any of that, the download half:
 * the episodes the filter lists are the ones asked for, and nothing is copied until they arrive.
 *
 * The episode rows are a small in-memory table: [states] is what the download stack has written,
 * and the DAO's reads are answered from it, so a test moves a download along by writing to it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EpisodeAudioExporterTest {

    private val m4a = byteArrayOf(0, 0, 0, 0x20) + "ftypM4A ".toByteArray() + ByteArray(200_000) { 7 }
    private val mp3 = "ID3".toByteArray() + ByteArray(1_000) { 3 }

    private lateinit var podcastDao: PodcastDao
    private lateinit var episodeDao: EpisodeDao
    private lateinit var downloadRepository: DownloadRepository
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

    /** The show's episodes in export order, as the feed stored them. */
    private var rows = listOf<EpisodeEntity>()

    /** Each episode's download state, which the download stack would write. */
    private val states = MutableStateFlow(emptyMap<String, DownloadState>())

    private fun episode(id: String, title: String, isPlayed: Boolean = false) = EpisodeEntity(
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
        isPlayed = isPlayed,
    )

    @Before
    fun setUp() {
        podcastDao = mockk()
        episodeDao = mockk()
        downloadRepository = mockk()
        crashReporter = mockk(relaxed = true)
        reader = FakeReader()
        directory = FakeDirectory()
        coEvery { podcastDao.getById(show.id) } returns show
        coEvery { episodeDao.getForExport(show.id) } answers {
            rows.map { row ->
                row.copy(downloadState = states.value[row.id] ?: DownloadState.NOT_DOWNLOADED)
            }
        }
        every { episodeDao.observeDownloadStates(any()) } answers {
            val ids = firstArg<List<String>>()
            states.map { current -> ids.mapNotNull { current[it] } }
        }
        coEvery { episodeDao.getDownloadListForIds(any()) } returns emptyList()
        // What the real repository does before Media3 has said anything: mark the row queued.
        coEvery { downloadRepository.download(any()) } answers {
            states.value = states.value + (firstArg<String>() to DownloadState.QUEUED)
            true
        }
        exporter = EpisodeAudioExporter(
            podcastDao = podcastDao,
            episodeDao = episodeDao,
            downloadRepository = downloadRepository,
            reader = reader,
            directory = directory,
            crashReporter = crashReporter,
            clock = Clock.fixed(Instant.parse("2026-09-16T10:00:00Z"), ZoneOffset.UTC),
            ioDispatcher = UnconfinedTestDispatcher(),
        )
    }

    /** Stores [episodes] in this order, each already downloaded. */
    private fun givenEpisodes(vararg episodes: EpisodeEntity) {
        givenEpisodes(DownloadState.COMPLETED, *episodes)
    }

    /** Stores [episodes] in this order, each in [state]. */
    private fun givenEpisodes(state: DownloadState, vararg episodes: EpisodeEntity) {
        rows = episodes.toList()
        states.value = episodes.associate { it.id to state }
    }

    /** Runs an export of the whole show into a folder named after it. */
    private suspend fun export(
        filter: EpisodeFilter = EpisodeFilter.ALL,
        folderName: String = show.title,
        onProgress: suspend (ExportProgress) -> Unit = {},
    ) = exporter.export(show.id, "tree", folderName, filter, onProgress)

    @Test
    fun `episodes are written in order into a folder named after the show`() = runTest {
        givenEpisodes(episode("a", "First"), episode("b", "Second"))
        reader.audio["youtube://video/a"] = m4a
        reader.audio["youtube://video/b"] = mp3
        val progress = mutableListOf<ExportProgress>()

        val summary = export(onProgress = { progress += it }).getOrThrow()

        assertEquals(ExportSummary(exported = 2, alreadyThere = 0, failed = 0), summary)
        val folder = directory.folders.getValue("Talks 2026")
        assertArrayEquals(m4a, folder.getValue("001 - First.m4a").bytes.toByteArray())
        assertArrayEquals(mp3, folder.getValue("002 - Second.mp3").bytes.toByteArray())
        assertEquals("audio/mp4", folder.getValue("001 - First.m4a").mimeType)
        assertEquals(
            listOf(
                ExportProgress(0, 2, ExportStage.DOWNLOADING),
                ExportProgress(2, 2, ExportStage.DOWNLOADING),
                ExportProgress(0, 2, ExportStage.COPYING),
                ExportProgress(1, 2, ExportStage.COPYING),
                ExportProgress(2, 2, ExportStage.COPYING),
            ),
            progress,
        )
    }

    @Test
    fun `a second run leaves files already there alone`() = runTest {
        givenEpisodes(episode("a", "First"), episode("b", "Second"))
        reader.audio["youtube://video/a"] = m4a
        reader.audio["youtube://video/b"] = m4a
        export().getOrThrow()
        directory.created.clear()

        val summary = export().getOrThrow()

        assertEquals(ExportSummary(exported = 0, alreadyThere = 2, failed = 0), summary)
        assertTrue(directory.created.isEmpty())
        assertEquals(1, directory.folders.size)
    }

    @Test
    fun `a second run recognises files whose numbers have moved`() = runTest {
        givenEpisodes(episode("a", "First"), episode("b", "Second"))
        reader.audio["youtube://video/a"] = m4a
        reader.audio["youtube://video/b"] = mp3
        export().getOrThrow()
        directory.created.clear()

        // A new video joined the playlist at the top, so every earlier episode moved down one.
        givenEpisodes(episode("c", "Newest"), episode("a", "First"), episode("b", "Second"))
        reader.audio["youtube://video/c"] = mp3
        val summary = export().getOrThrow()

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

        val summary = export().getOrThrow()

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

        val summary = export().getOrThrow()

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

        val summary = export().getOrThrow()

        assertEquals(ExportSummary(exported = 1, alreadyThere = 0, failed = 1), summary)
        assertEquals(setOf("002 - Second.m4a"), directory.folders.getValue("Talks 2026").keys)
        verify { crashReporter.recordNonFatal("Episode audio export failed", any()) }
    }

    @Test
    fun `cancelling mid-copy deletes the partial file and is not counted as a failure`() = runTest {
        givenEpisodes(episode("a", "First"))
        reader.audio["youtube://video/a"] = m4a
        directory.cancelOnWrite = true

        val result = runCatching { export() }

        assertTrue(result.exceptionOrNull() is CancellationException)
        assertTrue(directory.folders.getValue("Talks 2026").isEmpty())
        verify(exactly = 0) { crashReporter.recordNonFatal(any(), any()) }
    }

    @Test
    fun `a folder that cannot be created fails the whole export`() = runTest {
        givenEpisodes(episode("a", "First"))
        directory.refuseFolders = true

        val result = export()

        assertTrue(result.isFailure)
        verify { crashReporter.recordNonFatal("Episode audio export could not start", any()) }
    }

    @Test
    fun `a show that no longer exists fails the export`() = runTest {
        coEvery { podcastDao.getById("gone") } returns null

        val result = exporter.export("gone", "tree", "Gone", EpisodeFilter.ALL, onProgress = {})

        assertTrue(result.isFailure)
    }

    @Test
    fun `only the episodes the filter lists are downloaded and exported`() = runTest {
        givenEpisodes(
            DownloadState.NOT_DOWNLOADED,
            episode("a", "First", isPlayed = true),
            episode("b", "Second"),
        )
        reader.audio["youtube://video/b"] = m4a

        val run = async { export(filter = EpisodeFilter.UNPLAYED) }
        runCurrent()
        states.value = states.value + ("b" to DownloadState.COMPLETED)

        val summary = run.await().getOrThrow()
        assertEquals(ExportSummary(exported = 1, alreadyThere = 0, failed = 0), summary)
        assertEquals(setOf("001 - Second.m4a"), directory.folders.getValue("Talks 2026").keys)
        coVerify(exactly = 1) { downloadRepository.download("b") }
        coVerify(exactly = 0) { downloadRepository.download("a") }
    }

    @Test
    fun `nothing is copied until the downloads it asked for have arrived`() = runTest {
        givenEpisodes(DownloadState.NOT_DOWNLOADED, episode("a", "First"), episode("b", "Second"))
        states.value = states.value + ("b" to DownloadState.COMPLETED)
        reader.audio["youtube://video/a"] = m4a
        reader.audio["youtube://video/b"] = mp3

        val run = async { export() }
        runCurrent()

        assertFalse(run.isCompleted)
        assertTrue(directory.created.isEmpty())
        coVerify(exactly = 1) { downloadRepository.download("a") }
        coVerify(exactly = 0) { downloadRepository.download("b") }

        states.value = states.value + ("a" to DownloadState.DOWNLOADING)
        runCurrent()
        assertFalse(run.isCompleted)

        states.value = states.value + ("a" to DownloadState.COMPLETED)
        assertEquals(
            ExportSummary(exported = 2, alreadyThere = 0, failed = 0),
            run.await().getOrThrow(),
        )
    }

    @Test
    fun `a download that fails is counted and the rest keep their numbers`() = runTest {
        givenEpisodes(DownloadState.FAILED, episode("a", "First"), episode("b", "Second"))
        reader.audio["youtube://video/b"] = m4a

        val run = async { export() }
        runCurrent()
        // Asked for again, because a failed download is retried; this time one of them arrives.
        coVerify { downloadRepository.download("a") }
        states.value = mapOf("a" to DownloadState.FAILED, "b" to DownloadState.COMPLETED)
        runCurrent()
        // One more round for what is still missing, and it fails again.
        coVerify(exactly = 2) { downloadRepository.download("a") }
        states.value = states.value + ("a" to DownloadState.FAILED)

        assertEquals(
            ExportSummary(exported = 1, alreadyThere = 0, failed = 1),
            run.await().getOrThrow(),
        )
        assertEquals(setOf("002 - Second.m4a"), directory.folders.getValue("Talks 2026").keys)
    }

    @Test
    fun `a row left queued by a refused service start is asked for again`() = runTest {
        // Queued in Room, unknown to Media3: the wait would never end if it were trusted.
        givenEpisodes(DownloadState.QUEUED, episode("a", "First"))
        reader.audio["youtube://video/a"] = m4a

        val run = async { export() }
        runCurrent()

        coVerify(exactly = 1) { downloadRepository.download("a") }
        states.value = states.value + ("a" to DownloadState.COMPLETED)
        assertEquals(
            ExportSummary(exported = 1, alreadyThere = 0, failed = 0),
            run.await().getOrThrow(),
        )
    }

    @Test
    fun `a request undone by another writer is made once more`() = runTest {
        givenEpisodes(DownloadState.NOT_DOWNLOADED, episode("a", "First"))
        reader.audio["youtube://video/a"] = m4a

        val run = async { export() }
        runCurrent()
        // The start-up reconcile lands after the queued write and resets the row.
        states.value = states.value + ("a" to DownloadState.NOT_DOWNLOADED)
        runCurrent()

        assertFalse(run.isCompleted)
        coVerify(exactly = 2) { downloadRepository.download("a") }
        states.value = states.value + ("a" to DownloadState.COMPLETED)
        assertEquals(
            ExportSummary(exported = 1, alreadyThere = 0, failed = 0),
            run.await().getOrThrow(),
        )
    }

    @Test
    fun `the list names only episodes whose audio reached the folder`() = runTest {
        givenEpisodes(episode("a", "First"), episode("b", "Second"))
        reader.audio["youtube://video/a"] = m4a
        reader.audio["youtube://video/b"] = m4a
        reader.incomplete += "youtube://video/a"

        export().getOrThrow()

        coVerify { episodeDao.getDownloadListForIds(listOf("b")) }
    }

    @Test
    fun `the folder takes the name the user gave it`() = runTest {
        givenEpisodes(episode("a", "First"))
        reader.audio["youtube://video/a"] = m4a

        export(folderName = "Car: Monday").getOrThrow()

        assertEquals(setOf("Car Monday"), directory.folders.keys)
    }

    @Test
    fun `a list of the exported episodes is written beside them and replaced next time`() = runTest {
        givenEpisodes(episode("a", "First"))
        reader.audio["youtube://video/a"] = m4a
        val row = DownloadListRowEntity(
            showTitle = show.title,
            feedUrl = show.feedUrl,
            episodeTitle = "First",
            audioUrl = "youtube://video/a",
            publishedAt = null,
            durationMs = null,
            sizeBytes = null,
        )
        coEvery { episodeDao.getDownloadListForIds(listOf("a")) } returns listOf(row)

        export(folderName = "Talks").getOrThrow()
        export(folderName = "Talks").getOrThrow()

        val folder = directory.folders.getValue("Talks")
        assertEquals(setOf("001 - First.m4a", "Talks.md"), folder.keys)
        assertEquals("text/markdown", folder.getValue("Talks.md").mimeType)
        val list = folder.getValue("Talks.md").bytes.toString(Charsets.UTF_8.name())
        assertTrue(list.contains(show.feedUrl))
        assertTrue(list.contains("First"))
    }

    @Test
    fun `a list that cannot be read costs the list and not the export`() = runTest {
        givenEpisodes(episode("a", "First"))
        reader.audio["youtube://video/a"] = m4a
        coEvery { episodeDao.getDownloadListForIds(any()) } throws IllegalStateException("db")

        val summary = export().getOrThrow()

        assertEquals(ExportSummary(exported = 1, alreadyThere = 0, failed = 0), summary)
        verify {
            crashReporter.recordNonFatal("Download list could not be written to the export", any())
        }
    }

    @Test
    fun `a filter that lists nothing exports nothing and asks for nothing`() = runTest {
        givenEpisodes(DownloadState.NOT_DOWNLOADED, episode("a", "First"))

        val summary = export(filter = EpisodeFilter.DOWNLOADED).getOrThrow()

        assertEquals(ExportSummary(exported = 0, alreadyThere = 0, failed = 0), summary)
        coVerify(exactly = 0) { downloadRepository.download(any()) }
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
