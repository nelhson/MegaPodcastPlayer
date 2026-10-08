package md.borisveriga.megapodcastplayer.core.model

/**
 * How a screen asks for the player to be opened on an episode it has just started.
 *
 * Every screen that starts an episode says one of these to the shell, and the shell is the only
 * place that turns it into a face — the sheet or the video screen. The screens used to be handed
 * two callbacks, one per face, and the ones that were handed neither left the player however the
 * last episode had left it; which face came up depended on where the episode was started from.
 *
 * Distinct from [PlayerMode], which is what the player *is*. This is what was *asked*, and one of
 * the asks is to leave that alone.
 */
enum class OpenPlayerAs {
    /** As sound: the sheet, and the player is put in [PlayerMode.AUDIO]. */
    AUDIO,

    /** As picture: the video screen, and the player is put in [PlayerMode.VIDEO]. */
    VIDEO,

    /**
     * As the player was last used: the video screen when it was left in video and the episode has
     * a picture, otherwise the sheet. The mode is not changed.
     */
    REMEMBERED,

    /**
     * As YouTube's own embedded player, on a screen of its own.
     *
     * The one ask that does not open *the* player: the app's player is not given the episode, is
     * not started and has its mode left alone. What a YouTube show's episode asks for when the
     * [YouTubeSource] is [YouTubeSource.OFFICIAL], and never otherwise.
     */
    EMBEDDED,
}
