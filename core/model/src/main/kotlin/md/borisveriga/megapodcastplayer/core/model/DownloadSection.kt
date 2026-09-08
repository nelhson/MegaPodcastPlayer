package md.borisveriga.megapodcastplayer.core.model

/**
 * The four things a row on the downloads screen can be.
 *
 * The screen used to be one list in whatever order the download stack left it, which meant a
 * failure, a transfer, an episode waiting for Wi-Fi and a finished episode all looked like the same
 * kind of row and only the small grey line under the title told them apart. They are not the same
 * kind of row: one needs attention, one needs patience, one needs a network, and one is simply
 * there. Grouping them says so before anything is read.
 *
 * The declaration order is the order they are drawn in, and it puts the problems first: a failure
 * is the only thing here the user has to do something about.
 */
enum class DownloadSection {

    /** Gave up. The only rows on this screen that are waiting on the user. */
    FAILED,

    /** Transferring now. */
    DOWNLOADING,

    /** Asked for, not started — usually because the download is waiting for Wi-Fi. */
    WAITING,

    /** On the device. What the screen is ultimately a list of. */
    READY,
    ;

    companion object {

        /**
         * Which section a download state belongs to.
         *
         * @param state the episode's download state.
         * @return the section it is drawn under.
         */
        fun of(state: DownloadState): DownloadSection = when (state) {
            DownloadState.FAILED -> FAILED

            DownloadState.DOWNLOADING -> DOWNLOADING

            DownloadState.QUEUED -> WAITING

            // NOT_DOWNLOADED cannot reach this screen — the query behind it selects tracked
            // downloads — and if it ever did, it describes itself exactly as a finished episode
            // does, so it belongs with them rather than in a section of its own.
            DownloadState.COMPLETED, DownloadState.NOT_DOWNLOADED -> READY
        }
    }
}

/**
 * One section of the downloads screen, with the rows under it.
 *
 * @property section which of the four this is.
 * @property downloads the rows, in the order they arrived; never empty — a section with nothing in
 *   it is not drawn at all.
 */
data class DownloadGroup(
    val section: DownloadSection,
    val downloads: List<EpisodeWithShow>,
) {
    /** Whether the rows here can be dragged into a different arrangement. */
    val isReorderable: Boolean get() = section == DownloadSection.READY
}

/**
 * Splits the tracked downloads into the sections the screen draws.
 *
 * Keeps the incoming order within each section, which is what preserves the arrangement the user
 * dragged into place among the finished episodes. Empty sections are dropped, so a screen with
 * nothing but finished episodes shows one group rather than three empty headings.
 *
 * @return the sections that have anything in them, problems first.
 */
fun List<EpisodeWithShow>.groupIntoSections(): List<DownloadGroup> {
    val bySection = groupBy { DownloadSection.of(it.episode.downloadState) }
    return DownloadSection.entries.mapNotNull { section ->
        bySection[section]?.takeIf { it.isNotEmpty() }?.let { DownloadGroup(section, it) }
    }
}
