package md.borisveriga.bpodcat.sync

import md.borisveriga.bpodcat.core.data.repository.NewEpisode

/**
 * Tells the user that a background refresh found something.
 *
 * An interface rather than a concrete class so [RefreshWorker] can be unit-tested without a
 * notification manager: everything Android-specific lives in [SystemNewEpisodeNotifier].
 */
interface NewEpisodeNotifier {

    /**
     * Posts — or replaces — the "new episodes" notification.
     *
     * @param newEpisodes what the refresh discovered. An empty list posts nothing, so callers do
     *   not have to check first.
     */
    fun notifyNewEpisodes(newEpisodes: List<NewEpisode>)
}

/**
 * The text of the new-episode notification, decided without touching Android.
 *
 * Kept separate from [SystemNewEpisodeNotifier] because the interesting decisions — how many lines
 * to show, whether tapping can open one particular show — are pure and worth testing on their own.
 *
 * @property episodeCount how many episodes were discovered in total; the headline number.
 * @property lines the episodes to list, already capped at [MAX_NOTIFICATION_LINES].
 * @property overflowCount episodes that did not fit on a line, so the notification can say how many
 *   it is not showing rather than quietly dropping them.
 * @property targetPodcastId the show to open when the notification is tapped, or null when the
 *   episodes span more than one show and there is no single right destination.
 */
internal data class NewEpisodeNotificationContent(
    val episodeCount: Int,
    val lines: List<NewEpisode>,
    val overflowCount: Int,
    val targetPodcastId: String?,
)

/** How many episodes the notification lists before it starts counting the rest. */
internal const val MAX_NOTIFICATION_LINES = 5

/**
 * Works out what to say about [newEpisodes].
 *
 * @param newEpisodes the episodes a refresh discovered, in the order the feeds were visited.
 * @return the content to render, or null when there is nothing to say.
 */
internal fun newEpisodeNotificationContent(
    newEpisodes: List<NewEpisode>,
): NewEpisodeNotificationContent? {
    if (newEpisodes.isEmpty()) return null
    val lines = newEpisodes.take(MAX_NOTIFICATION_LINES)
    return NewEpisodeNotificationContent(
        episodeCount = newEpisodes.size,
        lines = lines,
        overflowCount = newEpisodes.size - lines.size,
        // `singleOrNull` is doing the deciding: one show means the tap can go straight to it, two
        // or more means the library is the only honest destination.
        targetPodcastId = newEpisodes.map { it.podcastId }.distinct().singleOrNull(),
    )
}
