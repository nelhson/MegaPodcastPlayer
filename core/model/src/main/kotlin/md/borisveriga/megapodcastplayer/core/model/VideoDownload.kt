package md.borisveriga.megapodcastplayer.core.model

/**
 * An episode's picture kept on the device, next to its sound.
 *
 * A YouTube episode plays as video by merging a video-only stream with the audio it already plays
 * as, so an offline video is two downloads: the episode's ordinary audio download, which
 * [Episode.downloadState] describes, and this one, which holds the picture at one rendition. It
 * lives in the download index and not in the episodes table, so adding it changed no schema.
 *
 * @property quality the rendition that was asked for; also what the player must ask for to find it
 *   on disk, because the picture is filed under a sentinel that carries this height.
 * @property state where the picture's download has got to. Never [DownloadState.NOT_DOWNLOADED]:
 *   an episode without a picture download has no [VideoDownload] at all.
 * @property percent progress in `0f..100f`; `100f` once [state] is [DownloadState.COMPLETED].
 */
data class VideoDownload(
    val quality: VideoQuality,
    val state: DownloadState,
    val percent: Float,
) {

    /** Whether every byte of the picture is on the device, so it plays with no network at all. */
    val isComplete: Boolean get() = state == DownloadState.COMPLETED
}
