package md.borisveriga.megapodcastplayer.bell

import md.borisveriga.megapodcastplayer.R

/**
 * What the end-of-episode bell's notification says, decided without touching Android.
 *
 * Split out of [SystemBellRinger] for the same reason the new-episode notification's content is:
 * the decision — which of the two bodies to use, and with what argument — is pure, and is the only
 * part worth testing. Everything that needs a `Context` stays on the other side of it.
 *
 * @property titleRes the headline; fixed, because the episode name goes in the body where there is
 *   room for it.
 * @property textRes the body, chosen by whether an episode title was available.
 * @property episodeTitle the argument for [textRes], or null when [textRes] takes none.
 */
internal data class BellNotificationContent(
    val titleRes: Int,
    val textRes: Int,
    val episodeTitle: String?,
)

/**
 * Works out what the bell should say about the episode that just ended.
 *
 * A blank title is treated as no title rather than rendered: a feed that supplies an empty
 * `<title>` would otherwise produce a notification reading " has ended", which is worse than the
 * generic wording.
 *
 * @param episodeTitle what finished, as the player knew it, or null.
 * @return the content to render; never null, because the bell was armed and must say something.
 */
internal fun bellNotificationContent(episodeTitle: String?): BellNotificationContent {
    val title = episodeTitle?.trim()?.takeIf { it.isNotEmpty() }
    return BellNotificationContent(
        titleRes = R.string.bell_title,
        textRes = if (title != null) R.string.bell_text else R.string.bell_text_unknown_episode,
        episodeTitle = title,
    )
}
