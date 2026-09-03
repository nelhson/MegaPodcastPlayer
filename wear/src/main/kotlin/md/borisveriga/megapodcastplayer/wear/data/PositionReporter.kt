package md.borisveriga.megapodcastplayer.wear.data

import javax.inject.Inject
import javax.inject.Singleton
import md.borisveriga.megapodcastplayer.core.wearprotocol.WearCommand

/**
 * Carries positions played on the watch back to the phone.
 *
 * Without this, listening on a run would be invisible: the phone would still have the episode
 * waiting where it was before you left, and finishing it on the watch would leave it unplayed in the
 * library. The whole feature is only half itself if the two devices disagree about what has been
 * heard.
 *
 * Delivery is best-effort *and* eventual, which is the point of the pair of methods here. A report
 * is attempted the moment playback stops — usually with the phone in a pocket a metre away — and
 * anything that did not get through stays marked unreported on disk, to be swept up by [flush] the
 * next time the phone is reachable. A run finished out of Bluetooth range therefore lands on the
 * phone when the wearer walks back through the door, rather than never.
 *
 * @property store where the unreported positions are kept.
 * @property client the route to the phone.
 */
@Singleton
class PositionReporter @Inject constructor(
    private val store: WatchEpisodeStore,
    private val client: PhonePlayerClient,
) {

    /**
     * Tells the phone about one episode.
     *
     * @param episodeId the episode played on the watch.
     * @return true when the phone took it, which is also when it stops being pending.
     */
    suspend fun report(episodeId: String): Boolean {
        val episode = store.episodes.value.firstOrNull { it.id == episodeId } ?: return false
        return report(episode)
    }

    /**
     * Tells the phone about everything it has not been told.
     *
     * Called when the phone comes back into range. Reports run one at a time rather than in
     * parallel: they are small messages over a link that is the scarce resource, and there are at
     * most a handful.
     *
     * @return how many were delivered.
     */
    suspend fun flush(): Int = store.episodes.value
        .filterNot { it.positionReported }
        .count { report(it) }

    /**
     * Sends one position and records the outcome.
     *
     * @param episode the episode whose position is being reported.
     */
    private suspend fun report(episode: StoredEpisode): Boolean {
        val delivered = client.send(
            WearCommand.ReportPosition(
                episodeId = episode.id,
                positionMs = episode.positionMs,
                isPlayed = episode.isPlayed,
            ),
        )
        if (delivered) store.markPositionReported(episode.id)
        return delivered
    }
}
