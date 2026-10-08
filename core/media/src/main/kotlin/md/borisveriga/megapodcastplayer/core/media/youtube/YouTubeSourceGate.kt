package md.borisveriga.megapodcastplayer.core.media.youtube

import androidx.annotation.WorkerThread
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import md.borisveriga.megapodcastplayer.core.common.di.ApplicationScope
import md.borisveriga.megapodcastplayer.core.datastore.UserPreferencesDataSource
import md.borisveriga.megapodcastplayer.core.model.YouTubeSource

/**
 * The user's [YouTubeSource], readable without suspending.
 *
 * Exists for one caller: [YouTubeDataSpecResolver], which Media3 calls on a loader or download
 * thread, synchronously, and which has to know whether it may extract at all. The preference is a
 * `Flow`, so this keeps a mirror of its latest value and answers from that.
 *
 * The mirror is empty for the instant between construction and the store's first emission. A call
 * in that instant blocks for the value rather than guessing: a guess of "extractor" would let one
 * extraction through under the official source, and a guess of "official" would fail an ordinary
 * play under the extractor. Blocking is acceptable precisely where this is called — the resolver's
 * own contract says so — and happens at most once per process.
 *
 * @property userPreferences where the choice lives.
 * @property scope application scope, so the mirror outlives every screen as the player does.
 */
@Singleton
class YouTubeSourceGate @Inject constructor(
    private val userPreferences: UserPreferencesDataSource,
    @ApplicationScope scope: CoroutineScope,
) {

    /** The latest value the store has emitted, or null before it has emitted anything. */
    private val mirror: StateFlow<YouTubeSource?> =
        userPreferences.youTubeSource.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * The current choice.
     *
     * Blocking only on the very first call before the mirror has a value; see the class KDoc for
     * why that is right here and nowhere else.
     *
     * @return where YouTube is read from and played by, right now.
     */
    @WorkerThread
    fun current(): YouTubeSource = mirror.value ?: runBlocking { userPreferences.youTubeSource.first() }
}
