package md.borisveriga.bpodcat.core.model

/**
 * The playback preferences the user can change from the player.
 *
 * Kept in `:core:model` rather than in `:core:datastore` because the watch reads the same values
 * over the Data Layer and must not depend on the phone's storage implementation.
 *
 * @property speed playback rate, `1f` being normal speed. Constrained to [SPEED_RANGE].
 * @property skipForwardMs how far the "skip ahead" button jumps.
 * @property skipBackMs how far the "skip back" button jumps. Deliberately smaller than
 *   [skipForwardMs] by default: skipping back is nearly always about re-hearing a sentence, while
 *   skipping forward is about getting past an ad break.
 * @property autoPlayNext whether finishing an episode starts the next queued one.
 */
data class PlaybackSettings(
    val speed: Float = DEFAULT_SPEED,
    val skipForwardMs: Long = DEFAULT_SKIP_FORWARD_MS,
    val skipBackMs: Long = DEFAULT_SKIP_BACK_MS,
    val autoPlayNext: Boolean = true,
) {
    companion object {
        /** Normal speed. */
        const val DEFAULT_SPEED = 1f

        /** 30 s clears a typical mid-roll ad. */
        const val DEFAULT_SKIP_FORWARD_MS = 30_000L

        /** 10 s is roughly one missed sentence. */
        const val DEFAULT_SKIP_BACK_MS = 10_000L

        /**
         * Speeds the player accepts.
         *
         * Below `0.5x` speech stops being intelligible; above `3x` ExoPlayer's time-stretching
         * artefacts dominate.
         */
        val SPEED_RANGE = 0.5f..3f

        /** The speeds the player's speed button cycles through. */
        val SPEED_STEPS = listOf(0.8f, 1f, 1.2f, 1.5f, 1.75f, 2f, 2.5f, 3f)
    }

    /**
     * Returns the next speed in [SPEED_STEPS], wrapping around after the fastest.
     *
     * A custom speed that is not one of the steps advances to the first step above it, so the button
     * always moves in the direction the user expects.
     */
    fun nextSpeed(): Float =
        SPEED_STEPS.firstOrNull { it > speed + SPEED_EPSILON } ?: SPEED_STEPS.first()
}

/** Floating-point slack when comparing speeds, which arrive as rounded preference values. */
private const val SPEED_EPSILON = 0.001f
