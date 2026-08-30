package md.borisveriga.bpodcat.sync

import md.borisveriga.bpodcat.core.data.repository.NewEpisode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the pure half of the new-episode notification: what it says and where it points.
 *
 * No Android here on purpose — the decisions worth pinning down are the truncation and the tap
 * target, and neither needs a notification manager to verify.
 */
class NewEpisodeNotificationContentTest {

    private fun newEpisode(id: String, podcastId: String = "pod-1") = NewEpisode(
        episodeId = id,
        episodeTitle = "Episode $id",
        podcastId = podcastId,
        podcastTitle = "Show $podcastId",
    )

    @Test
    fun `nothing discovered means nothing to say`() {
        assertNull(newEpisodeNotificationContent(emptyList()))
    }

    @Test
    fun `episodes from one show point the tap at that show`() {
        val content = newEpisodeNotificationContent(
            listOf(newEpisode("a"), newEpisode("b")),
        )

        assertEquals("pod-1", content?.targetPodcastId)
        assertEquals(2, content?.episodeCount)
        assertEquals(0, content?.overflowCount)
    }

    @Test
    fun `episodes from several shows have no single destination`() {
        val content = newEpisodeNotificationContent(
            listOf(newEpisode("a"), newEpisode("z", podcastId = "pod-2")),
        )

        assertNull(
            "With two shows there is no honest destination but the app itself",
            content?.targetPodcastId,
        )
    }

    @Test
    fun `a long run is truncated and the remainder is counted rather than dropped`() {
        val episodes = List(MAX_NOTIFICATION_LINES + 3) { index -> newEpisode("e$index") }

        val content = newEpisodeNotificationContent(episodes)

        assertEquals(episodes.size, content?.episodeCount)
        assertEquals(MAX_NOTIFICATION_LINES, content?.lines?.size)
        assertEquals(3, content?.overflowCount)
        // The lines kept are the first ones, so the order the feeds were visited survives.
        assertEquals("e0", content?.lines?.first()?.episodeId)
    }
}
