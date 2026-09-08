package md.borisveriga.megapodcastplayer.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import md.borisveriga.megapodcastplayer.core.data.repository.NewEpisode
import md.borisveriga.megapodcastplayer.core.data.repository.PodcastRepository
import md.borisveriga.megapodcastplayer.core.data.repository.RefreshSummary
import md.borisveriga.megapodcastplayer.core.data.repository.ShowSettingsRepository

/**
 * The periodic background refresh.
 *
 * Re-fetches the feeds of every show whose per-show "background refresh" toggle is on
 * (`Podcast.autoRefresh`), stores whatever is new, and tells the user about it. Audio is never
 * downloaded here — that decision belongs to the auto-download setting, which the repository
 * applies on its own as it discovers episodes.
 *
 * Scheduled by [RefreshScheduler]; see it for the interval and constraints.
 *
 * @property podcastRepository does the actual refreshing.
 * @property newEpisodeNotifier tells the user what was found.
 * @property showSettings consulted for which shows are allowed to interrupt; see [doWork].
 */
@HiltWorker
class RefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParameters: WorkerParameters,
    private val podcastRepository: PodcastRepository,
    private val newEpisodeNotifier: NewEpisodeNotifier,
    private val showSettings: ShowSettingsRepository,
) : CoroutineWorker(appContext, workerParameters) {

    override suspend fun doWork(): Result {
        // refreshAll reports per-feed failures rather than throwing, so there is no try/catch here:
        // a thrown exception at this point is either cancellation (which CoroutineWorker handles)
        // or a genuine defect, and neither should be turned into a silent retry.
        val summary = podcastRepository.refreshAll(onlyAutoRefreshable = true)

        // Filtered rather than suppressed wholesale: a run that finds episodes of three shows, one
        // of which is muted, should still say so about the other two. The muted show's episodes
        // are stored and badged exactly as before — the toggle declines the interruption, not the
        // episode.
        newEpisodeNotifier.notifyNewEpisodes(summary.newEpisodes.filter { it.notifiable() })

        return if (summary.everyFeedFailed) {
            // Every feed failing usually means the network came back only far enough to satisfy the
            // CONNECTED constraint. Backing off and retrying is worth more than waiting six hours.
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        } else {
            Result.success()
        }
    }

    /**
     * Whether this show is one the user wants to hear about.
     *
     * @return true when the show's new-episode notifications are on, which is the default.
     */
    private suspend fun NewEpisode.notifiable(): Boolean =
        showSettings.observeSettings(podcastId).first().notifyNewEpisodes

    private companion object {

        /**
         * How many times a run may retry before it is written off until the next period.
         *
         * With WorkManager's default exponential backoff (30 s, 60 s, 120 s) three attempts span
         * about four minutes, which is long enough for a flaky connection to settle and short
         * enough not to spend the battery on a network that is genuinely gone.
         */
        const val MAX_ATTEMPTS = 3
    }
}

/**
 * True when the run attempted at least one feed and every one of them failed.
 *
 * A library with no auto-refreshable shows is not a failure — it is nothing to do — so the empty
 * case has to be excluded explicitly.
 */
private val RefreshSummary.everyFeedFailed: Boolean
    get() = failedTitles.isNotEmpty() && refreshedCount == 0 && notModifiedCount == 0
