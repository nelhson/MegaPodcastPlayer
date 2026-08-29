package md.borisveriga.bpodcat.core.media.download

import androidx.media3.exoplayer.offline.Download
import md.borisveriga.bpodcat.core.model.DownloadState

/**
 * A snapshot of one episode's download, flattened out of Media3's [Download].
 *
 * The download index is Media3's private business — it keys off content ids, carries failure
 * reasons and stop reasons, and lives in its own SQLite file. Everything outside `:core:media`
 * only ever needs these four values, so the translation happens once, here.
 *
 * @property episodeId the episode; Media3's `contentId`.
 * @property state offline availability as the rest of the app models it.
 * @property downloadedBytes bytes written so far.
 * @property percent progress in `0f..100f`; `0f` before the content length is known.
 */
data class EpisodeDownloadStatus(
    val episodeId: String,
    val state: DownloadState,
    val downloadedBytes: Long,
    val percent: Float,
) {
    companion object {
        /**
         * The status of an episode Media3 has no record of.
         *
         * Used when a download is removed: the index row is gone, so there is nothing left to read
         * a state off, and "not downloaded" is exactly what that means.
         */
        fun notDownloaded(episodeId: String): EpisodeDownloadStatus = EpisodeDownloadStatus(
            episodeId = episodeId,
            state = DownloadState.NOT_DOWNLOADED,
            downloadedBytes = 0L,
            percent = 0f,
        )
    }
}

/**
 * Translates a Media3 download state constant into the app's [DownloadState].
 *
 * `STATE_STOPPED` maps to [DownloadState.QUEUED] rather than to a paused state of its own: the only
 * thing that stops a download here is an unmet requirement — no Wi-Fi — and from the user's point of
 * view an episode waiting for Wi-Fi and an episode waiting its turn are the same thing.
 * `STATE_REMOVING` maps to [DownloadState.NOT_DOWNLOADED] because the audio is already on its way
 * out and the button should say "download" immediately, not a beat later.
 */
internal fun downloadStateOf(media3State: Int): DownloadState = when (media3State) {
    Download.STATE_QUEUED, Download.STATE_STOPPED, Download.STATE_RESTARTING -> DownloadState.QUEUED
    Download.STATE_DOWNLOADING -> DownloadState.DOWNLOADING
    Download.STATE_COMPLETED -> DownloadState.COMPLETED
    Download.STATE_FAILED -> DownloadState.FAILED
    Download.STATE_REMOVING -> DownloadState.NOT_DOWNLOADED
    else -> DownloadState.NOT_DOWNLOADED
}

/**
 * Flattens a Media3 [Download] into an [EpisodeDownloadStatus].
 *
 * A completed download reports `100f` explicitly: Media3 leaves `percentDownloaded` at whatever the
 * last progress callback set, which for a small file can be a shade under 100 forever, and a
 * progress bar frozen at 99% on a finished download is a bug report waiting to happen.
 */
internal fun Download.asEpisodeDownloadStatus(): EpisodeDownloadStatus {
    val mapped = downloadStateOf(state)
    return EpisodeDownloadStatus(
        episodeId = request.id,
        state = mapped,
        downloadedBytes = bytesDownloaded,
        percent = if (mapped == DownloadState.COMPLETED) {
            100f
        } else {
            percentDownloaded.takeIf { !it.isNaN() }?.coerceIn(0f, 100f) ?: 0f
        },
    )
}
