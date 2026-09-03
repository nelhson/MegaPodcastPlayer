package md.borisveriga.megapodcastplayer.wearsync

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Tests how a copy to the watch is started.
 *
 * The fallback is the whole point of this class, and it is the branch nobody would notice breaking:
 * a phone that cannot start the service must still send the episode, only slower. So both paths are
 * pinned here rather than left to the one that happens to run on a given Android version.
 */
@RunWith(RobolectricTestRunner::class)
class ForegroundEpisodeAudioTransfersTest {

    private val sender = mockk<EpisodeAudioSender>(relaxed = true)

    @Test
    fun `a copy is handed to the transfer service`() = runTest {
        val application = ApplicationProvider.getApplicationContext<Application>()

        ForegroundEpisodeAudioTransfers(application, sender).start(WATCH_NODE, EPISODE_ID)

        val started = shadowOf(application).nextStartedService
        assertNotNull("Nothing was started", started)
        assertEquals(
            EpisodeTransferService::class.java.name,
            started.component?.className,
        )
        assertEquals(WATCH_NODE, started.getStringExtra(EpisodeTransferService.EXTRA_NODE_ID))
        assertEquals(EPISODE_ID, started.getStringExtra(EpisodeTransferService.EXTRA_EPISODE_ID))
        // The service, not this class, is what reaches the sender.
        coVerify(exactly = 0) { sender.send(any(), any()) }
    }

    /**
     * From Android 12 a background app may not start a foreground service without an exemption, and
     * the watch can ask at a moment when there is none. Refusing the transfer then would turn a slow
     * copy into a tap that silently does nothing.
     */
    @Test
    fun `a refused service start still sends the episode`() = runTest {
        val context = mockk<Context>(relaxed = true)
        every { context.startForegroundService(any()) } throws
            IllegalStateException("not allowed to start service")

        ForegroundEpisodeAudioTransfers(context, sender).start(WATCH_NODE, EPISODE_ID)

        coVerify(exactly = 1) { sender.send(WATCH_NODE, EPISODE_ID) }
    }

    /**
     * Stopping a copy must not go near the service. Starting one is what needs the process kept
     * alive; a cancel that started a foreground service to say "stop" would be the wrong way round,
     * and on Android 12 and later it is also the start most likely to be refused.
     */
    @Test
    fun `a cancelled copy reaches the sender without starting a service`() = runTest {
        val application = ApplicationProvider.getApplicationContext<Application>()

        ForegroundEpisodeAudioTransfers(application, sender).cancel(EPISODE_ID)

        assertNull("A service was started", shadowOf(application).nextStartedService)
        coVerify(exactly = 1) { sender.cancel(EPISODE_ID) }
    }

    private companion object {
        const val WATCH_NODE = "watch-node-1"
        const val EPISODE_ID = "ep-7"
    }
}
