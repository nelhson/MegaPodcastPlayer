package md.borisveriga.bpodcat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests for [PlaybackSettings]'s speed cycling. */
class PlaybackSettingsTest {

    @Test
    fun `next speed advances through the steps`() {
        assertEquals(1f, PlaybackSettings(speed = 0.8f).nextSpeed(), 0.001f)
        assertEquals(1.2f, PlaybackSettings(speed = 1f).nextSpeed(), 0.001f)
    }

    @Test
    fun `next speed wraps around after the fastest step`() {
        assertEquals(
            PlaybackSettings.SPEED_STEPS.first(),
            PlaybackSettings(speed = PlaybackSettings.SPEED_STEPS.last()).nextSpeed(),
            0.001f,
        )
    }

    @Test
    fun `a custom speed advances to the first step above it`() {
        assertEquals(1.5f, PlaybackSettings(speed = 1.3f).nextSpeed(), 0.001f)
    }

    @Test
    fun `a speed that is a step is not treated as below itself`() {
        // Guards the epsilon: 1f must advance to 1.2f, never back to itself.
        assertEquals(1.2f, PlaybackSettings(speed = 1.0000001f).nextSpeed(), 0.001f)
    }
}
