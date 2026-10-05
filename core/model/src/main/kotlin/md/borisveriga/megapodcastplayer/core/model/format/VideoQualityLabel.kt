package md.borisveriga.megapodcastplayer.core.model.format

/**
 * Formats a video rendition by its height, as `720p`.
 *
 * The way every video service names a rendition, and a numeric format rather than copy, so it is
 * not translated — the rule `formatSpeed` follows for `1.5x`. There were three copies of it, one
 * string resource in each of the player, the show page and Downloads, which is three places for
 * the same quality to come to read two ways.
 *
 * Here rather than beside `formatSpeed` in `:core:common` because the shared video-download sheet
 * in `:core:designsystem` needs it too, and the design system sees this module and not that one.
 *
 * @param height the rendition's height in pixels.
 * @return e.g. `720p`.
 */
fun formatVideoQuality(height: Int): String = height.toString() + QUALITY_SUFFIX

/** The mark after a rendition's height; not translated, like `p` in `720p`. */
private const val QUALITY_SUFFIX = "p"
