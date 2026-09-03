package md.borisveriga.megapodcastplayer.wearsync

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import md.borisveriga.megapodcastplayer.core.data.playback.EpisodePlayer
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
 * @property episodePlayer resolves an episode id into something the player can accept.
 * @property publisher used to answer
 *   [WearCommand.RequestState] and to confirm the outcome of the rest.
 * @property libraryPublisher republishes what the phone holds offline, for the same reason.
 * @property audioSender copies an episode's audio to the watch that asked for it.
 */
@Singleton
internal class WearCommandExecutor @Inject constructor(
    private val connection: PlaybackConnection,
    private val playbackRepository: PlaybackRepository,
    private val episodePlayer: EpisodePlayer,
    private val publisher: NowPlayingPublisher,
    private val libraryPublisher: OfflineLibraryPublisher,
    private val audioSender: EpisodeAudioSender,
) {

    /**
     * Runs one command.
     *
     * @param command what the watch asked for.
     * @param sourceNodeId the watch that asked. Only the commands that send something *back* to a
     *   particular device need it; everything else acts on the phone, where there is nobody to
     *   address.
     */
    suspend fun execute(command: WearCommand, sourceNodeId: String) {
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

            // The snapshot is answered by the publish below, which every command does anyway. The
            // offline library is not — it is published on its own clock — so an opening watch that
            // asks for state gets both, which is the moment it needs both.
            WearCommand.RequestState -> libraryPublisher.publishCurrent()

            is WearCommand.CopyToWatch -> audioSender.send(sourceNodeId, command.episodeId)

            // Audio the watch played is audio the phone did not, so this is the one command that
            // writes playback state rather than asking for it. A finished episode goes back to the
            // start, exactly as finishing it on the phone would.
            is WearCommand.ReportPosition -> playbackRepository.setPlayed(
                episodeId = command.episodeId,
                isPlayed = command.isPlayed,
                positionMs = if (command.isPlayed) 0L else command.positionMs,
            )
        }

        // The state flow would eventually carry the change to the watch on its own, but only once
        // the player has finished reacting. Publishing here closes the gap between the tap and the
        // button changing shape, which on a watch is the difference between working and broken.
        publisher.publishCurrent()
    }
}
