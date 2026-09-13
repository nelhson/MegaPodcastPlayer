package md.borisveriga.megapodcastplayer.wear.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import md.borisveriga.megapodcastplayer.core.common.di.Dispatcher
import md.borisveriga.megapodcastplayer.core.common.di.MegaPodcastPlayerDispatcher
import md.borisveriga.megapodcastplayer.core.common.result.suspendRunCatching

/**
 * The things the watch has already explained once and need not explain again.
 *
 * There is one of them, and there is unlikely ever to be many: a wrist has no room for a tour, and
 * a hint that comes back is worse than one that never appeared. This exists because the one gesture
 * on the watch that is not visible — tapping the progress bar to take hold of it, then turning the
 * bezel — cannot be discovered by looking at the screen, and a sentence the first time is the whole
 * of the fix.
 *
 * A marker file rather than a preferences store: this module holds a handful of facts and no
 * storage dependency, and one boolean does not earn the first one. The file's *existence* is the value, so
 * there is nothing to parse and nothing that can be corrupt — an unreadable state is indistinguishable
 * from a fresh install, and a hint shown once more after one is the harmless side of that trade.
 *
 * @property context used only for its private files directory.
 * @property ioDispatcher both methods here touch the disk.
 */
@Singleton
class WatchHints @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(MegaPodcastPlayerDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    private val scrubHintFile: File get() = File(context.filesDir, SCRUB_HINT_FILE)

    /**
     * Whether the wearer has already been told how to scrub.
     *
     * @return true when the hint has been shown before and must not be shown again.
     */
    suspend fun hasSeenScrubHint(): Boolean = withContext(ioDispatcher) {
        scrubHintFile.exists()
    }

    /**
     * Records that the hint has now been shown.
     *
     * A write that fails is not reported: the consequence is one more sentence on one more scrub,
     * which is not something the wearer could act on and not something worth a crash report.
     */
    suspend fun markScrubHintSeen() {
        withContext(ioDispatcher) {
            suspendRunCatching { scrubHintFile.createNewFile() }
        }
    }

    private companion object {
        /** Named for what it records, since the file's presence is the whole of its content. */
        const val SCRUB_HINT_FILE = "scrub-hint-seen"
    }
}
