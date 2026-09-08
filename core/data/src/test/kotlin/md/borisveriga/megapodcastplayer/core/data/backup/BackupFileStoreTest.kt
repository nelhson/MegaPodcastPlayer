package md.borisveriga.megapodcastplayer.core.data.backup

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [BackupFileStore].
 *
 * The failure paths matter more than the happy one: the document comes from a picker, so a stream
 * that cannot be opened is an ordinary Tuesday rather than an exceptional case, and it has to
 * become a `Result.failure` with exactly one report rather than an exception nobody catches.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupFileStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var crashReporter: CrashReporter
    private lateinit var store: BackupFileStore

    @Before
    fun setUp() {
        crashReporter = mockk(relaxed = true)
        store = BackupFileStore(
            context = ApplicationProvider.getApplicationContext(),
            crashReporter = crashReporter,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
    }

    @Test
    fun `writes then reads the same text back`() = runTest {
        val file = temporaryFolder.newFile("backup.json")
        val uri = Uri.fromFile(file)

        val written = store.write(uri, """{ "version": 1 }""")
        val read = store.read(uri)

        assertTrue(written.isSuccess)
        assertEquals("""{ "version": 1 }""", read.getOrNull())
    }

    @Test
    fun `writing a shorter document leaves no tail of the longer one behind`() = runTest {
        val file = temporaryFolder.newFile("backup.json")
        val uri = Uri.fromFile(file)
        store.write(uri, "a-very-long-previous-export-indeed")

        store.write(uri, "short")

        assertEquals("short", store.read(uri).getOrNull())
    }

    @Test
    fun `reading a document that is not there fails without throwing`() = runTest {
        val missing = Uri.fromFile(File(temporaryFolder.root, "absent.json"))

        val read = store.read(missing)

        assertTrue(read.isFailure)
        verify(exactly = 1) { crashReporter.recordNonFatal(any(), any()) }
    }

    @Test
    fun `writing to a document that cannot be opened fails without throwing`() = runTest {
        // A directory is not a document; opening it for writing cannot succeed.
        val directory = Uri.fromFile(temporaryFolder.newFolder("not-a-file"))

        val written = store.write(directory, "{}")

        assertTrue(written.isFailure)
        verify(exactly = 1) { crashReporter.recordNonFatal(any(), any()) }
    }
}
