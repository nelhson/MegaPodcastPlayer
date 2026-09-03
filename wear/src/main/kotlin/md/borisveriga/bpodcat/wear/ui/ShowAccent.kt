package md.borisveriga.bpodcat.wear.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * A colour per show, standing in for the cover art the watch deliberately no longer draws.
 *
 * Cover art is what told the wearer, at a glance, which show they were listening to. Dropping it
 * would have taken that cue with it, so the show gets a colour instead: the same title always picks
 * the same one, so a show looks the same every time it comes round, and two shows in a row almost
 * never look alike.
 *
 * The palette is fixed rather than derived from the title's bits. A hue computed from a hash lands
 * wherever it lands, including the muddy blues and the browns that vanish against a black watch
 * face; every colour here was chosen to carry text on one.
 */
private val SHOW_ACCENTS = listOf(
    Color(0xFF7CC6FF), // sky
    Color(0xFFB7A6FF), // violet
    Color(0xFFFF9EC4), // rose
    Color(0xFFFFB27C), // amber
    Color(0xFF7FE3B0), // mint
    Color(0xFF6FE0E0), // aqua
    Color(0xFFFF9A8F), // coral
    Color(0xFFC9E572), // lime
)

/**
 * The colour standing in for a show.
 *
 * The hash is spelled out rather than taken from [String.hashCode] so the mapping is this file's to
 * keep: a show that is sky today must not become mint because a platform changed its string hash.
 *
 * @param showTitle the show's name; an empty one — a phone that sent no show — takes the first
 *   colour, which is as good as any and is at least stable.
 * @return the accent to tint that show's header, waveform and queue rows with.
 */
internal fun showAccent(showTitle: String): Color {
    val hash = showTitle.fold(0) { acc, character -> acc * HASH_MULTIPLIER + character.code }
    // `mod` rather than `%`: the fold overflows into negatives on longer titles, and `%` would keep
    // the sign and index out of the list.
    return SHOW_ACCENTS[hash.mod(SHOW_ACCENTS.size)]
}

/**
 * The same colour as a packed ARGB integer.
 *
 * The tile and the complication are drawn by the system rather than by Compose, and both speak in
 * `Int` colours. They go through this rather than keeping a palette of their own, so that a show is
 * the same colour on the watch face as it is inside the app.
 *
 * @param showTitle the show's name.
 */
internal fun showAccentArgb(showTitle: String): Int = showAccent(showTitle).toArgb()

/** The odd multiplier every string hash uses, for the same reason: it spreads short titles. */
private const val HASH_MULTIPLIER = 31
