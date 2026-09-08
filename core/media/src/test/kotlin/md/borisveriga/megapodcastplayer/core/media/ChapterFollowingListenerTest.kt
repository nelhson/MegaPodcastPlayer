package md.borisveriga.megapodcastplayer.core.media

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import md.borisveriga.megapodcastplayer.core.model.chapters.Chapter
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [ChapterFollowingListener].
 *
 * The rule being pinned is an ordering one, and the failure it prevents is silent: a chapter list
 * left in place across an episode change makes the notification's *next* seek to a timestamp from
 * the episode before, which is not an error anywhere and simply drops the listener somewhere
 * arbitrary. So each test is about *when* the list is right rather than what is in it — cleared
 * before the new one is asked for, and applied only if the episode it was asked for is still the
 * one loaded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChapterFollowingListenerTest {

    private val chapters = listOf(
        Chapter(startMs = 0L, title = "Intro"),
        Chapter(startMs = 60_000L, title = "The interview"),
    )

    private val wrapped = FakePlayer(itemCount = 2)
    private val player = ChapterAwarePlayer(wrapped)

    @Test
    fun `a transition resolves and applies the loaded episode's chapters`() {
        val source = FakeChapterSource(mapOf("ep-0" to chapters))
        val listener = listenerFor(source)

        listener.onMediaItemTransition(mediaItem("ep-0"), Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        player.seekToNext()

        assertEquals(listOf("ep-0"), source.requested)
        assertEquals(listOf(Seek(mediaItemIndex = 0, positionMs = 60_000L)), wrapped.contentSeeks)
    }

    @Test
    fun `an episode with no chapters leaves next meaning the next episode`() {
        val source = FakeChapterSource(mapOf("ep-0" to chapters))
        val listener = listenerFor(source)

        listener.onMediaItemTransition(mediaItem("ep-1"), Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        player.seekToNext()

        assertEquals(listOf(1), wrapped.mediaItemSeeks)
    }

    @Test
    fun `the old list is dropped before the new one has been resolved`() {
        val source = FakeChapterSource(mapOf("ep-0" to chapters, "ep-1" to chapters))
        val listener = listenerFor(source)
        listener.onMediaItemTransition(mediaItem("ep-0"), Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

        source.hold()
        wrapped.loadItem(1)
        listener.onMediaItemTransition(mediaItem("ep-1"), Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        player.seekToNext()

        // Nothing has resolved, so the wrapper is back to knowing nothing about chapters — and with
        // the last episode loaded, that means the press does nothing rather than the wrong thing.
        assertEquals(emptyList<Seek>(), wrapped.contentSeeks)
        assertEquals(emptyList<Int>(), wrapped.mediaItemSeeks)
    }

    @Test
    fun `chapters that arrive for an episode no longer loaded are discarded`() {
        val source = FakeChapterSource(mapOf("ep-0" to chapters))
        val listener = listenerFor(source)

        source.hold()
        listener.onMediaItemTransition(mediaItem("ep-0"), Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        // The episode changed without the listener hearing about it, which is the case the
        // cancellation on the next transition cannot catch.
        wrapped.loadItem(1)
        source.release()
        player.seekToNext()

        assertEquals(emptyList<Seek>(), wrapped.contentSeeks)
    }

    /**
     * Builds the listener under test.
     *
     * Unconfined, so a resolution that is not deliberately held has finished by the time the call
     * that started it returns.
     */
    private fun listenerFor(source: FakeChapterSource) = ChapterFollowingListener(
        player = player,
        scope = CoroutineScope(UnconfinedTestDispatcher()),
        chapterSource = source,
    )

    /** A media item carrying an episode id, as the queue's items do. */
    private fun mediaItem(episodeId: String): MediaItem =
        MediaItem.Builder().setMediaId(episodeId).build()
}

/**
 * A chapter source that can be made to answer slowly.
 *
 * @param chaptersById what each episode has; anything absent has none.
 */
private class FakeChapterSource(
    private val chaptersById: Map<String, List<Chapter>>,
) : PlaybackChapterSource {

    /** The episodes asked about, in order. */
    val requested = mutableListOf<String>()

    /** Set while answers are being held back; see [hold]. */
    private var held: CompletableDeferred<Unit>? = null

    /** Makes every later call wait, so the window before an answer arrives can be looked at. */
    fun hold() {
        held = CompletableDeferred()
    }

    /** Lets a held call answer. */
    fun release() {
        held?.complete(Unit)
    }

    override suspend fun chaptersFor(episodeId: String): List<Chapter> {
        requested += episodeId
        held?.await()
        return chaptersById[episodeId].orEmpty()
    }
}
