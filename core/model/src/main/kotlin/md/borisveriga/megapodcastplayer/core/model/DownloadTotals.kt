package md.borisveriga.megapodcastplayer.core.model

/**
 * What a set of downloads costs on the device.
 *
 * @property completedCount how many of them have finished: a transfer half done or failed is not
 *   yet storage the user can act on.
 * @property totalBytes what the finished ones occupy, their finished videos included — a picture is
 *   usually several times its sound, and a total without it said a video-heavy phone was nearly
 *   empty.
 */
data class DownloadTotals(
    val completedCount: Int,
    val totalBytes: Long,
)

/**
 * Adds up what these downloads occupy.
 *
 * One definition shared by the whole screen and a single folder of it, so the two figures can
 * never be computed two ways.
 *
 * @param videoDownloads every episode's video download, keyed by episode id.
 * @return the count and size of what has finished.
 */
fun List<EpisodeWithShow>.totals(videoDownloads: Map<String, VideoDownload>): DownloadTotals {
    val completed = filter { it.episode.downloadState == DownloadState.COMPLETED }
    return DownloadTotals(
        completedCount = completed.size,
        totalBytes = completed.sumOf { it.episode.downloadedBytes } +
            // The pictures of these downloads only, finished ones only — the same rule as the sound.
            mapNotNull { videoDownloads[it.episode.id] }.filter { it.isComplete }.sumOf { it.bytes },
    )
}
