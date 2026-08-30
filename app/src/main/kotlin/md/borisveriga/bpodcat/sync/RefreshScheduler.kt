package md.borisveriga.bpodcat.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts [RefreshWorker] on WorkManager's schedule.
 *
 * Called from `BPodcatApplication.onCreate`, which is the only place that runs whether the user
 * opened the app, the system restarted the process, or the device rebooted.
 *
 * @property context application context; WorkManager is a per-process singleton keyed on it.
 */
@Singleton
class RefreshScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    /**
     * Enqueues the periodic refresh, keeping any run already scheduled.
     *
     * [ExistingPeriodicWorkPolicy.KEEP] is the important half: this runs on every process start, and
     * REPLACE would push the next run six hours out every time the user opened the app — a phone
     * used daily would then never refresh in the background at all.
     */
    fun schedule() {
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(
            REFRESH_INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    // Any connection: a feed is a few kilobytes, so waiting for Wi-Fi would delay
                    // the badge for no meaningful saving. Downloading the audio is the expensive
                    // part, and that has its own unmetered-only setting.
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    internal companion object {

        /**
         * Name of the unique work; stable across releases, because changing it would orphan the
         * run already scheduled on every installed device.
         */
        const val UNIQUE_WORK_NAME = "periodic-feed-refresh"

        /**
         * How often feeds are checked.
         *
         * Six hours is four checks a day: often enough that a daily show is noticed the morning it
         * lands, rare enough to be invisible on the battery. WorkManager treats this as a window,
         * not a promise, and will batch it with whatever else the system has to do.
         */
        const val REFRESH_INTERVAL_HOURS = 6L
    }
}
