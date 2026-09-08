package md.borisveriga.megapodcastplayer.core.data.playback

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import md.borisveriga.megapodcastplayer.core.common.di.ApplicationScope
import md.borisveriga.megapodcastplayer.core.data.repository.PlaybackRepository
import md.borisveriga.megapodcastplayer.core.data.repository.ShowSettingsRepository
import md.borisveriga.megapodcastplayer.core.media.PlaybackConnection

/**
 * Plays every show at the rate the user set for it.
 *
 * A per-show speed is the setting people actually want — one host is followable at 2× and the next
 * is not — and it only works if it survives the ways an episode starts that nobody pressed a button
 * for. The queue advancing on its own is the important one: applying the rate where playback is
 * *requested* would leave the next episode running at the previous show's speed, which is exactly
 * the "changes for no visible reason" failure the setting exists to prevent.
 *
 * So this watches which episode the player has loaded, whatever loaded it, and applies that show's
 * rate on every change. A show with no rate of its own is not left alone but put back to the app's
 * rate, which is the other half of the same promise: leaving 2.5× running after a show that asked
 * for it would make the setting leak into every show played afterwards.
 *
 * Application-scoped and started from the application object, alongside the other collectors that
 * have to outlive every screen — the player does.
 *
 * @property connection the player: the source of which episode is loaded, and where the rate goes.
 * @property playbackRepository resolves an episode id to the show it belongs to, and holds the
 *   app-wide rate.
 * @property showSettings the per-show rate, when there is one.
 * @property scope application scope.
 */
@Singleton
class ShowSpeedApplier @Inject constructor(
    private val connection: PlaybackConnection,
    private val playbackRepository: PlaybackRepository,
    private val showSettings: ShowSettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {

    /** Guards [start] so that it runs once however many times the application object calls it. */
    private val started = AtomicBoolean(false)

    /**
     * Begins applying per-show rates.
     *
     * Called from the application's `onCreate`. Returns immediately; the work runs on [scope].
     *
     * @return the collecting job, or null if it was already started. Production callers ignore it;
     *   tests join it.
     */
    fun start(): Job? {
        if (!started.compareAndSet(false, true)) return null
        return scope.launch {
            connection.playbackState
                .map { it.episodeId }
                .distinctUntilChanged()
                .filterNotNull()
                .collect { episodeId -> applyFor(episodeId) }
        }
    }

    /**
     * Sets the player's rate to whatever this episode's show should play at.
     *
     * An episode the library no longer has is left alone rather than reset: it is a transient state
     * — a row removed while its audio is still loaded — and changing the speed of what is audibly
     * playing because of it would be worse than doing nothing.
     *
     * @param episodeId the episode now loaded.
     */
    private suspend fun applyFor(episodeId: String) {
        val podcastId = playbackRepository.playableEpisode(episodeId)?.episode?.podcastId ?: return
        val appSpeed = playbackRepository.observePlaybackSettings().first().speed
        val speed = showSettings.observeSettings(podcastId).first().speedOr(appSpeed)
        connection.setSpeed(speed)
    }
}
