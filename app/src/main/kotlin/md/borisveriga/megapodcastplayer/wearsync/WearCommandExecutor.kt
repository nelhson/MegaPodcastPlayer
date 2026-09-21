package md.borisveriga.megapodcastplayer.wearsync

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import md.borisveriga.megapodcastplayer.core.data.playback.EpisodePlayer
import md.borisveriga.megapodcastplayer.core.data.repository.MomentsRepository
import md.borisveriga.megapodcastplayer.core.data.repository.PlaybackRepository
import md.borisveriga.megapodcastplayer.core.media.PlaybackConnection
import md.borisveriga.megapodcastplayer.core.wearprotocol.WearCommand

/**
 * Applies a command that arrived from the watch to the phone's player.
 *
 * This is the whole of the watch's authority over playback: it can ask for exactly the things
 * [WearCommand] names, and each maps onto the same call the phone's own player screen makes. The
 * separation from [WearCommandService] is what lets the mapping be unit-tested — a
 * `WearableListenerService` cannot be instantiated in a JVM test.
 *
 * Nothing here reports success back to the watch. The watch learns what happened the same way the
 * phone's UI does: from the next [md.borisveriga.megapodcastplayer.core.wearprotocol.NowPlayingSnapshot].
 *
 * @property connection the phone's player.
 * @property playbackRepository the durable queue and playback preferences.
 * @property momentsRepository where a mark from the wrist is written.
 * @property episodePlayer resolves an episode id into something the player can accept.
 * @property publisher used to answer
 *   [WearCommand.RequestState] and to confirm the outcome of the rest.
 */
@Singleton
internal class WearCommandExecutor @Inject constructor(
    private val connection: PlaybackConnection,
    private val playbackRepository: PlaybackRepository,
    private val momentsRepository: MomentsRepository,
    private val episodePlayer: EpisodePlayer,
    private val publisher: NowPlayingPublisher,
) {

    /**
     * Runs one command.
     *
     * The sender is not a parameter: every command acts on the phone, where there is nobody to
     * address, and [WearCommandService] has already refused anything from a node it does not trust.
     *
     * @param command what the watch asked for.
     */
    suspend fun execute(command: WearCommand) {
        when (command) {
            WearCommand.TogglePlayPause -> connection.togglePlayPause()

            // The skip intervals are the phone's preference, not the watch's: see
            // WearCommand.SkipForward on why the amount does not travel with the command.
            WearCommand.SkipForward ->
                connection.skipForward(playbackRepository.observePlaybackSettings().first().skipForwardMs)

            WearCommand.SkipBack ->
                connection.skipBack(playbackRepository.observePlaybackSettings().first().skipBackMs)

            WearCommand.SkipToNext -> connection.skipToNext()

            WearCommand.SkipToPrevious -> connection.skipToPrevious()

            // Written as well as applied, exactly as the phone's speed button does, so the choice
            // survives the playback service being killed.
            WearCommand.CycleSpeed -> {
                val next = playbackRepository.observePlaybackSettings().first().nextSpeed()
                playbackRepository.setSpeed(next)
                connection.setSpeed(next)
            }

            is WearCommand.SeekTo -> connection.seekTo(command.positionMs)

            is WearCommand.PlayEpisode -> episodePlayer.play(command.episodeId)

            // The queue and nothing else: what is playing is not interrupted, which is the whole
            // difference between this and PlayEpisode. The watch sees the result in the next
            // snapshot, where the episode has moved from the downloaded list into the queue.
            is WearCommand.QueueEpisode -> playbackRepository.enqueue(command.episodeId)

            // Answered by the publish below, which every command does anyway.
            WearCommand.RequestState -> Unit

            WearCommand.MarkMoment -> markMoment()
        }

        // The state flow would eventually carry the change to the watch on its own, but only once
        // the player has finished reacting. Publishing here closes the gap between the tap and the
        // button changing shape, which on a watch is the difference between working and broken.
        publisher.publishCurrent()
    }

    /**
     * Saves a moment at the phone's own playhead.
     *
     * The position is read from the phone's player here rather than taken from the watch's screen:
     * what the watch draws is an extrapolation of a snapshot that is up to a second old, and a
     * moment is a claim about a particular second.
     *
     * A mark with nothing playing is dropped rather than guessed at.
     */
    private suspend fun markMoment() {
        val state = connection.currentState()
        val playing = state.episodeId ?: return
        momentsRepository.mark(playing, state.positionMs)
    }
}
