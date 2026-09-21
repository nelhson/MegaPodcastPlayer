package md.borisveriga.megapodcastplayer.core.media

import android.media.AudioManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [applyMediaVolume]: the watch's volume arriving at the phone's media stream, or not. */
class DeviceVolumeTest {

    /** An audio service whose media stream runs 0..15, as a phone's does. */
    private fun audioManager(fixed: Boolean = false): AudioManager = mockk(relaxed = true) {
        every { isVolumeFixed } returns fixed
        every { getStreamMinVolume(AudioManager.STREAM_MUSIC) } returns 0
        every { getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 15
    }

    @Test
    fun `a level within the scale is set as it is, and without the system's volume panel`() {
        val audioManager = audioManager()

        assertTrue(audioManager.applyMediaVolume(9))

        verify(exactly = 1) { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 9, 0) }
    }

    @Test
    fun `a level off either end of the scale is clamped to it`() {
        val audioManager = audioManager()

        audioManager.applyMediaVolume(40)
        audioManager.applyMediaVolume(-3)

        verify(exactly = 1) { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 15, 0) }
        verify(exactly = 1) { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0) }
    }

    /** The branch that does nothing has to say so, or the caller cannot report it. */
    @Test
    fun `a device whose volume is fixed is left alone, and says so`() {
        val audioManager = audioManager(fixed = true)

        assertFalse(audioManager.applyMediaVolume(9))

        verify(exactly = 0) { audioManager.setStreamVolume(any(), any(), any()) }
    }
}
