package md.borisveriga.bpodcat.core.model

/**
 * The rules that decide what BPodcat keeps on the device.
 *
 * Lives in `:core:model` for the same reason [PlaybackSettings] does: the watch has to know whether
 * an episode is expected offline before it will offer to play it, and it must not depend on the
 * phone's storage implementation to find out.
 *
 * @property autoDownloadNewEpisodes whether a feed refresh queues the episodes it discovers.
 * @property unmeteredOnly whether downloads wait for Wi-Fi. On by default: a podcast episode is tens
 *   of megabytes, and silently spending a user's mobile data is the one mistake a podcast app
 *   cannot take back.
 * @property keepLimitPerPodcast how many downloaded episodes to keep per show before the oldest are
 *   removed. [KEEP_ALL] disables the sweep entirely.
 * @property deleteAfterPlaying whether finishing an episode removes its audio from the device.
 */
data class DownloadSettings(
    val autoDownloadNewEpisodes: Boolean = false,
    val unmeteredOnly: Boolean = true,
    val keepLimitPerPodcast: Int = DEFAULT_KEEP_LIMIT,
    val deleteAfterPlaying: Boolean = true,
) {
    companion object {
        /** Sentinel for "never sweep old downloads"; the user is managing storage by hand. */
        const val KEEP_ALL = 0

        /** Three episodes is roughly a commute's worth of listening without hoarding gigabytes. */
        const val DEFAULT_KEEP_LIMIT = 3

        /** The keep-limits the settings screen offers, `0` meaning [KEEP_ALL]. */
        val KEEP_LIMIT_STEPS = listOf(KEEP_ALL, 1, 2, 3, 5, 10)
    }

    /** True when [keepLimitPerPodcast] should be enforced at all. */
    val enforcesKeepLimit: Boolean get() = keepLimitPerPodcast > KEEP_ALL

    /**
     * Chooses which of a show's downloaded episodes to remove to satisfy [keepLimitPerPodcast].
     *
     * What is kept is the newest [keepLimitPerPodcast] episodes, plus everything in [protectedIds]
     * whatever its age. Protected episodes therefore *add* to the limit rather than consuming a
     * slot: the alternative — counting them against it — would sweep the newest episodes to make
     * room for old queued ones, which is exactly backwards.
     *
     * @param downloadedNewestFirst the show's downloaded episodes, newest first — the order
     *   `EpisodeDao` already returns them in.
     * @param protectedIds episodes that must survive the sweep: what is playing, and anything the
     *   user queued. Deleting audio out from under the player is a far worse outcome than briefly
     *   holding more than the limit.
     * @return the episodes to remove, oldest first.
     */
    fun episodesToSweep(
        downloadedNewestFirst: List<Episode>,
        protectedIds: Set<String> = emptySet(),
    ): List<Episode> {
        if (!enforcesKeepLimit) return emptyList()
        val newestKept = downloadedNewestFirst.take(keepLimitPerPodcast).mapTo(mutableSetOf()) { it.id }
        return downloadedNewestFirst
            .filterNot { it.id in newestKept || it.id in protectedIds }
            // Oldest first, so a caller that stops early still frees the least useful audio.
            .reversed()
    }
}
