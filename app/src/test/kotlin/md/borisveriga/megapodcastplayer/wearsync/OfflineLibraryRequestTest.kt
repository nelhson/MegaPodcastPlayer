package md.borisveriga.megapodcastplayer.wearsync

import com.google.android.gms.wearable.DataMap
import md.borisveriga.megapodcastplayer.core.wearprotocol.OfflineEpisode
import md.borisveriga.megapodcastplayer.core.wearprotocol.OfflineLibrary
import md.borisveriga.megapodcastplayer.core.wearprotocol.WearMessages
import md.borisveriga.megapodcastplayer.core.wearprotocol.WearPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests the data item the offline library travels in.
 *
 * Robolectric because `PutDataMapRequest` builds a `wear://` [android.net.Uri], which needs a
 * framework that is more than a stub.
 *
 * The urgency assertion is the one that earns its keep. Without it the list still encodes, still
 * publishes and still passes every other test here — and takes up to half an hour to reach the
 * watch, which on a wrist is the same thing as never.
 */
@RunWith(RobolectricTestRunner::class)
class OfflineLibraryRequestTest {

    /**
     * A non-urgent data item is batched by the Data Layer for up to half an hour. The moment this
     * list is wanted is the moment a download finished and the phone is about to be left behind.
     */
    @Test
    fun `the library is published urgently`() {
        assertTrue(offlineLibraryRequest(OfflineLibrary()).isUrgent)
    }

    @Test
    fun `the library goes on its own path`() {
        val request = offlineLibraryRequest(OfflineLibrary())

        assertEquals(WearPaths.OFFLINE_LIBRARY, request.uri.path)
    }

    @Test
    fun `the item carries the library the watch will read back`() {
        val library = OfflineLibrary(
            episodes = listOf(
                OfflineEpisode(
                    id = "ep-1",
                    title = "The one about batteries",
                    showTitle = "Radio Hardware",
                    durationMs = 3_600_000L,
                    sizeBytes = 28_000_000L,
                ),
            ),
        )

        val request = offlineLibraryRequest(library)

        val item = request.data?.let(DataMap::fromByteArray)
        val payload = item?.getByteArray(WearPaths.PAYLOAD_KEY)
        assertEquals(library, payload?.let(WearMessages::decodeLibrary))
    }
}
