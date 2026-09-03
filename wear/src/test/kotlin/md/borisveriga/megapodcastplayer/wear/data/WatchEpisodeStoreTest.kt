package md.borisveriga.megapodcastplayer.wear.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import md.borisveriga.megapodcastplayer.core.wearprotocol.OfflineEpisode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

/**
 * Tests the one thing on the watch that outlives the app: the episodes it holds.
 *
 * The failure this is mostly about is a half-received episode looking playable. A Bluetooth link
 * drops when a phone goes into a bag, and what it leaves behind is a file that opens, plays for four
 * minutes and stops — which is worse than an episode that plainly did not arrive.
 *
 * Robolectric only for the application context's files directory; nothing here touches the Data
 * Layer or a player.
 */
@RunWith(AndroidJUnit4::class)
class WatchEpisodeStoreTest {

    private val offer = OfflineEpisode(
        id = "ep-1",
        title = "The one about batteries",
        showTitle = "Radio Hardware",
        durationMs = 3_600_000L,
        sizeBytes = AUDIO.size.toLong(),
    )

    @Before
    fun clearTheDirectory() {
        RuntimeEnvironment.getApplication().filesDir.resolve("watch_episodes").deleteRecursively()
    }

    /**
     * A store writing into the test's own files directory.
     *
     * Built inside each test rather than in [clearTheDirectory] because its dispatcher has to share
     * the test's scheduler; one made outside `runTest` brings a second scheduler with it, and the
     * two deadlock.
     */
    private fun TestScope.newStore() = WatchEpisodeStore(
        RuntimeEnvironment.getApplication(),
        StandardTestDispatcher(testScheduler),
    )

    @Test
    fun `a complete transfer is stored and can be read back`() = runTest {
        val store = newStore()
        val received = store.receive(offer, ByteArrayInputStream(AUDIO))

        assertTrue(received)
        val stored = store.episodes.value.single()
        assertEquals("ep-1", stored.id)
        assertEquals("The one about batteries", stored.title)
        assertEquals("Radio Hardware", stored.showTitle)
        assertEquals(AUDIO.size.toLong(), stored.sizeBytes)
        assertTrue(store.audioFile("ep-1").exists())
    }

    /** The bug in the title: a short file that plays for four minutes and stops. */
    @Test
    fun `a transfer that stops early is thrown away rather than left looking playable`() = runTest {
        val store = newStore()
        val received = store.receive(offer, ByteArrayInputStream(AUDIO.copyOf(AUDIO.size / 2)))

        assertFalse(received)
        assertTrue(store.episodes.value.isEmpty())
        assertFalse(store.audioFile("ep-1").exists())
    }

    /**
     * A phone that never learned an episode's length still has to be able to send it; refusing would
     * mean refusing every episode whose feed omitted the enclosure length.
     */
    @Test
    fun `an episode of unknown size is accepted on any bytes at all`() = runTest {
        val store = newStore()
        val received = store.receive(offer.copy(sizeBytes = 0L), ByteArrayInputStream(AUDIO))

        assertTrue(received)
        assertEquals(AUDIO.size.toLong(), store.episodes.value.single().sizeBytes)
    }

    @Test
    fun `an empty stream is not an episode`() = runTest {
        val store = newStore()
        val received = store.receive(offer.copy(sizeBytes = 0L), ByteArrayInputStream(ByteArray(0)))

        assertFalse(received)
        assertTrue(store.episodes.value.isEmpty())
    }

    @Test
    fun `the index survives a restart`() = runTest {
        val store = newStore()
        store.receive(offer, ByteArrayInputStream(AUDIO))

        val reopened = newStore()
        reopened.load()

        assertEquals(listOf("ep-1"), reopened.episodes.value.map { it.id })
    }

    /**
     * The index and the directory can disagree — storage cleaners exist, and so do failed renames.
     * A row whose audio has gone is a row that would open a player onto nothing.
     */
    @Test
    fun `an entry whose audio has gone is dropped on load`() = runTest {
        val store = newStore()
        store.receive(offer, ByteArrayInputStream(AUDIO))
        store.audioFile("ep-1").delete()

        store.load()

        assertTrue(store.episodes.value.isEmpty())
    }

    @Test
    fun `a position played here is remembered and marked as owed to the phone`() = runTest {
        val store = newStore()
        store.receive(offer, ByteArrayInputStream(AUDIO))

        store.setPosition("ep-1", positionMs = 900_000L, isPlayed = false)

        val stored = store.episodes.value.single()
        assertEquals(900_000L, stored.positionMs)
        assertFalse(stored.positionReported)

        store.markPositionReported("ep-1")
        assertTrue(store.episodes.value.single().positionReported)
    }

    @Test
    fun `removing an episode takes its audio with it`() = runTest {
        val store = newStore()
        store.receive(offer, ByteArrayInputStream(AUDIO))

        store.remove("ep-1")

        assertTrue(store.episodes.value.isEmpty())
        assertFalse(store.audioFile("ep-1").exists())
    }

    @Test
    fun `clearing the watch removes every file`() = runTest {
        val store = newStore()
        store.receive(offer, ByteArrayInputStream(AUDIO))
        store.receive(offer.copy(id = "ep-2"), ByteArrayInputStream(AUDIO))

        store.removeAll()

        assertTrue(store.episodes.value.isEmpty())
        assertFalse(store.audioFile("ep-1").exists())
        assertFalse(store.audioFile("ep-2").exists())
    }

    @Test
    fun `a second transfer joins the first rather than replacing it`() = runTest {
        val store = newStore()
        store.receive(offer, ByteArrayInputStream(AUDIO))
        store.receive(
            offer.copy(id = "ep-2", title = "The one about antennas"),
            ByteArrayInputStream(AUDIO),
        )

        assertEquals(listOf("ep-1", "ep-2"), store.episodes.value.map { it.id }.sorted())
    }

    /** Asking twice for the same episode must leave one copy, not two entries pointing at one file. */
    @Test
    fun `receiving an episode already held replaces it`() = runTest {
        val store = newStore()
        store.receive(offer, ByteArrayInputStream(AUDIO))
        store.receive(offer, ByteArrayInputStream(AUDIO))

        assertEquals(1, store.episodes.value.size)
    }

    /**
     * The shape a Bluetooth channel actually delivers.
     *
     * Nothing promises a stream fills the buffer it is handed; a channel returns whatever has
     * arrived, which on a slow link is a fraction of it. The copy has to keep going until the stream
     * says it is done rather than until one read comes back short, and the sink it writes through
     * has to cope with being handed small pieces.
     */
    @Test
    fun `an episode arriving a byte at a time still lands whole`() = runTest {
        val store = newStore()
        val received = store.receive(offer, DribblingInputStream(AUDIO))

        assertTrue(received)
        assertEquals(AUDIO.size.toLong(), store.episodes.value.single().sizeBytes)
        assertArrayEquals(AUDIO, store.audioFile("ep-1").readBytes())
    }

    /** A finished transfer must leave no bar behind, whether it worked or not. */
    @Test
    fun `a transfer is no longer listed as arriving once it ends`() = runTest {
        val store = newStore()

        store.receive(offer, ByteArrayInputStream(AUDIO))
        assertTrue(store.transfers.value.isEmpty())

        store.receive(offer.copy(id = "ep-2"), ByteArrayInputStream(AUDIO.copyOf(AUDIO.size / 2)))
        assertTrue(store.transfers.value.isEmpty())
    }

    /**
     * The button this is all for: an episode the wearer no longer wants arriving.
     *
     * A real dispatcher and a real thread, unlike every other test here, because this is the one
     * about two things happening at once — the copy, and the tap that stops it. On the test
     * scheduler the copy loop is a single indivisible task, and there is nowhere to put the tap.
     */
    @Test
    fun `a cancelled transfer stops, is unlisted and leaves nothing on disk`() = runTest {
        val store = WatchEpisodeStore(RuntimeEnvironment.getApplication(), Dispatchers.IO)
        val input = HandOffInputStream(AUDIO)

        val receiving = async(Dispatchers.Default) { store.receive(offer, input) }
        assertTrue("The copy never started", input.awaitFirstRead())

        store.cancel("ep-1")
        // Let the stream serve one more block, so that what stops the loop is the flag rather than
        // the closed stream: this is the case where bytes are still arriving.
        input.release()

        assertFalse("A cancelled transfer must not report success", receiving.await())
        assertTrue(store.episodes.value.isEmpty())
        assertTrue(store.transfers.value.isEmpty())
        assertFalse(store.audioFile("ep-1").exists())
        assertFalse(partialFile("ep-1").exists())
    }

    /**
     * The reason cancelling closes the stream as well as setting a flag.
     *
     * A wearer cancels a copy precisely when it has stopped moving, and a copy that has stopped
     * moving is sitting inside a blocking read that no flag will ever be checked past.
     */
    @Test
    fun `a transfer stalled mid-read is still cancellable`() = runTest {
        val store = WatchEpisodeStore(RuntimeEnvironment.getApplication(), Dispatchers.IO)
        val input = StalledInputStream()

        val receiving = async(Dispatchers.Default) { store.receive(offer, input) }
        assertTrue("The copy never started", input.awaitFirstRead())

        store.cancel("ep-1")

        assertFalse(receiving.await())
        assertTrue(store.episodes.value.isEmpty())
        assertTrue(store.transfers.value.isEmpty())
        assertFalse(partialFile("ep-1").exists())
    }

    /** Cancelling something that is not arriving is the ordinary race, not an error. */
    @Test
    fun `cancelling a transfer that is not running does nothing`() = runTest {
        val store = newStore()
        store.receive(offer, ByteArrayInputStream(AUDIO))

        store.cancel("ep-1")

        assertEquals(listOf("ep-1"), store.episodes.value.map { it.id })
        assertTrue(store.audioFile("ep-1").exists())
    }

    /** Where a half-received episode is written, which nothing must be left in. */
    private fun partialFile(episodeId: String) = RuntimeEnvironment.getApplication()
        .filesDir
        .resolve("watch_episodes")
        .resolve("$episodeId.part")

    /**
     * A stream the test can stop and start, serving [CHUNK_BYTES] at a time.
     *
     * The first read goes through and announces itself; every read after that waits for the test to
     * say so. That is what puts the cancel in the middle of a transfer rather than before or after
     * one. [close] deliberately does *not* release a waiting read, so that what ends the copy in the
     * test using it is the cancellation flag and nothing else.
     *
     * @param bytes what to serve, more of it than the test will ever ask for.
     */
    private class HandOffInputStream(private val bytes: ByteArray) : InputStream() {

        private val firstRead = CountDownLatch(1)
        private val proceed = CountDownLatch(1)
        private var position = 0

        /** Waits for the copy loop to have read something. */
        fun awaitFirstRead(): Boolean = firstRead.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        /** Lets a waiting read return. */
        fun release() = proceed.countDown()

        override fun read(): Int = if (position >= bytes.size) -1 else bytes[position++].toInt()

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (firstRead.count == 0L) proceed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            firstRead.countDown()
            if (position >= bytes.size) return -1
            val served = minOf(CHUNK_BYTES, len, bytes.size - position)
            bytes.copyInto(b, off, position, position + served)
            position += served
            return served
        }
    }

    /**
     * A stream that never returns from a read until it is closed, and then fails.
     *
     * The shape of a Bluetooth channel whose far end has walked out of range: the socket is open,
     * nothing is coming, and closing it from another thread is the only thing that ends the wait.
     */
    private class StalledInputStream : InputStream() {

        private val firstRead = CountDownLatch(1)
        private val closed = CountDownLatch(1)

        /** Waits for the copy loop to be stuck in a read. */
        fun awaitFirstRead(): Boolean = firstRead.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        override fun read(): Int = read(ByteArray(1), 0, 1)

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            firstRead.countDown()
            closed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            throw IOException("The channel was closed")
        }

        override fun close() = closed.countDown()
    }

    /**
     * A stream that hands back one byte per read, however much is asked for.
     *
     * [ByteArrayInputStream] always fills the buffer, so it cannot catch a loop that assumes a read
     * returns everything it asked for. This can.
     *
     * @param bytes what to dribble out.
     */
    private class DribblingInputStream(private val bytes: ByteArray) : InputStream() {

        private var position = 0

        override fun read(): Int =
            if (position >= bytes.size) -1 else bytes[position++].toInt() and 0xFF

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= bytes.size) return -1
            if (len == 0) return 0
            b[off] = bytes[position++]
            return 1
        }
    }

    private companion object {
        /** How much [HandOffInputStream] serves per read; several blocks' worth of audio in all. */
        const val CHUNK_BYTES = 4_096

        /** A bound on every latch, so a broken cancellation fails the test rather than hanging it. */
        const val TIMEOUT_SECONDS = 10L

        /**
         * Stand-in audio: the store never looks inside a file, only at how much of it arrived.
         *
         * Larger than [WatchEpisodeStore]'s progress step so that a transfer of it reports more than
         * its opening zero, which is the case the reporting loop is written for.
         */
        val AUDIO = ByteArray(40_960) { (it % 251).toByte() }
    }
}
