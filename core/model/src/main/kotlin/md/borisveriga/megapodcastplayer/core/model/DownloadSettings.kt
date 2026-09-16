package md.borisveriga.megapodcastplayer.core.model

/**
 * The rules that decide what MegaPodcastPlayer keeps on the device.
 *
 * Lives in `:core:model` for the same reason [PlaybackSettings] does: the watch has to know whether
 * an episode is expected offline before it will offer to play it, and it must not depend on the
 * phone's storage implementation to find out.
 *
 * @property autoDownloadNewEpisodes whether a feed refresh queues the episodes it discovers.
 * @property unmeteredOnly whether downloads wait for Wi-Fi. On by default: a podcast episode is tens
 *   of megabytes, and silently spending a user's mobile data is the one mistake a podcast app
 *   cannot take back.
 * @property keepLimitPerPodcast how many of the episodes a refresh discovers auto-download may
 *   fetch per show, newest first. [KEEP_ALL] means every one of them. It only ever bounds what is
 *   *fetched*: nothing here removes a download that is already on the device, because a refresh
 *   that deleted what the user had saved would be worse than one that saved a little too much.
 * @property deleteAfterPlaying whether finishing an episode removes its audio from the device.
 */
data class DownloadSettings(
    val autoDownloadNewEpisodes: Boolean = false,
    val unmeteredOnly: Boolean = true,
    val keepLimitPerPodcast: Int = DEFAULT_KEEP_LIMIT,
    val deleteAfterPlaying: Boolean = true,
) {
    companion object {
        /** Sentinel for "no bound": auto-download fetches everything a refresh discovers. */
        const val KEEP_ALL = 0

        /** Three episodes is roughly a commute's worth of listening without hoarding gigabytes. */
        const val DEFAULT_KEEP_LIMIT = 3

        /** The keep-limits the settings screen offers, `0` meaning [KEEP_ALL]. */
        val KEEP_LIMIT_STEPS = listOf(KEEP_ALL, 1, 2, 3, 5, 10)
    }

    /** True when [keepLimitPerPodcast] bounds auto-download at all. */
    val enforcesKeepLimit: Boolean get() = keepLimitPerPodcast > KEEP_ALL
}
