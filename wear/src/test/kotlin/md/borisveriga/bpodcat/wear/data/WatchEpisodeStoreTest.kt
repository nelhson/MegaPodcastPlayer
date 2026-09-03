package md.borisveriga.bpodcat.wear.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import md.borisveriga.bpodcat.core.wearprotocol.OfflineEpisode
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

    private companion object {
        /** Stand-in audio: the store never looks inside a file, only at how much of it arrived. */
        val AUDIO = ByteArray(4_096) { (it % 251).toByte() }
    }
}
