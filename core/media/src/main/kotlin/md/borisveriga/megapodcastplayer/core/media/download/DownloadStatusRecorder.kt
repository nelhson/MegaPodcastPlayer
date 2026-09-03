package md.borisveriga.megapodcastplayer.core.media.download

/**
 * Receives download progress so it can be stored where the UI reads it.
 *
 * The same dependency inversion as
 * [md.borisveriga.megapodcastplayer.core.media.PlaybackProgressRecorder]: `:core:data` depends on
 * `:core:media`, so the download service cannot write to Room itself. The data layer implements this
 * and Hilt binds it in.
 */
interface DownloadStatusRecorder {

    /**
     * Records the current state of one download.
     *
     * Called on every Media3 download event, which during an active download is roughly once a
     * second, so implementations must be cheap.
     *
     * @param status the episode's download state, bytes and percentage.
     */
    suspend fun recordDownloadStatus(status: EpisodeDownloadStatus)

    /**
     * Records that every download was removed at once — the settings screen's "remove all".
     *
     * A separate call rather than one [recordDownloadStatus] per episode because Media3 reports a
     * bulk removal without enumerating what it removed.
     */
    suspend fun recordAllDownloadsRemoved()
}
