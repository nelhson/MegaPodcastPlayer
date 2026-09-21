package md.borisveriga.megapodcastplayer.wear.ui

/**
 * What a banked distance is worth in volume steps.
 *
 * Both gestures that move the watch's volume bar — the bezel and a finger dragged along it —
 * report pixels, and the phone's volume moves in whole steps of its own scale. [bankVolumeSteps]
 * is the one place that conversion is done, so that the two gestures cannot come to disagree.
 *
 * @property steps whole volume steps to move by; negative is quieter, zero is "not yet".
 * @property remainderPx the distance left over, to be carried into the next event.
 */
internal data class BankedSteps(
    val steps: Int,
    val remainderPx: Float,
)

/**
 * Splits a banked distance into the whole steps it is worth and what is left over.
 *
 * Events arrive many to a step — a slow drag is a stream of one- and two-pixel deltas — so each
 * is added to a bank and only whole steps are withdrawn. The remainder keeps its sign and stays
 * in the bank, which is what lets a slow movement add up rather than round to nothing each time.
 *
 * @param bankedPx the distance accumulated so far, signed.
 * @param pixelsPerStep how far one step is. Zero or less means the scale is not known yet — a bar
 *   that has not been measured, a phone that reported no steps — and then nothing moves and
 *   nothing is kept, so that a distance banked against no scale cannot land as a jump later.
 * @return the steps to move now and the distance to keep.
 */
internal fun bankVolumeSteps(bankedPx: Float, pixelsPerStep: Float): BankedSteps {
    if (pixelsPerStep <= 0f) return BankedSteps(steps = 0, remainderPx = 0f)
    // Truncation toward zero, not flooring: half a step quieter is no step, not one.
    val steps = (bankedPx / pixelsPerStep).toInt()
    return BankedSteps(steps = steps, remainderPx = bankedPx - steps * pixelsPerStep)
}
