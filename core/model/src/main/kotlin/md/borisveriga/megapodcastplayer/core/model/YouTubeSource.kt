package md.borisveriga.megapodcastplayer.core.model

/**
 * Where a YouTube show's videos come from, and what plays them.
 *
 * Two answers, and the difference between them is YouTube's terms of service. The app was built on
 * reading YouTube's own player responses — whole playlists, direct audio and video streams, played
 * in the background and kept on the device — and those terms do not permit any of it. That is an
 * accepted trade-off for a sideloaded personal build, and a blocker for anything else; this choice
 * is what lets one install be either, without a rebuild.
 *
 * The choice does not change what ships. Both paths are in the APK whichever is selected, so a
 * build with this set to [OFFICIAL] is a build that *behaves* within the terms, not one that could
 * be put on a store. A build with the extractor left out is a different piece of work.
 *
 * Nothing is deleted by switching. Episodes an [OFFICIAL] show cannot see are hidden, not removed;
 * downloads of a YouTube show stay on the device and out of sight; switching back shows them all
 * again.
 */
enum class YouTubeSource {

    /**
     * YouTube's own responses, read with an extractor.
     *
     * Every video of a playlist, played by Media3 as sound or as sound and picture, in the
     * background and from a download. What the app has always done, and against YouTube's terms.
     */
    EXTRACTOR,

    /**
     * YouTube's published feed and embedded player, used as YouTube publishes them.
     *
     * The feed carries only the newest videos — fifteen, of which [OFFICIAL_EPISODE_LIMIT] are
     * shown — and the player is YouTube's own, inside the app, on the screen and nowhere else: no
     * background sound, no download, no picture without its sound or the other way round. The
     * player is not the app's player, so nothing about it reaches the notification, the lock
     * screen, the widget or the watch.
     */
    OFFICIAL,

    ;

    companion object {
        /** The extractor: what every install did before there was a choice. */
        val DEFAULT: YouTubeSource = EXTRACTOR

        /**
         * How many of a show's newest videos an [OFFICIAL] show has.
         *
         * Both what the feed is trimmed to and what the stored list is cut to when it is read, so
         * that a show switched from [EXTRACTOR] shows the same count as one added under
         * [OFFICIAL]. Under the fifteen YouTube's feed returns, so the feed never decides the number.
         */
        const val OFFICIAL_EPISODE_LIMIT: Int = 10
    }
}
