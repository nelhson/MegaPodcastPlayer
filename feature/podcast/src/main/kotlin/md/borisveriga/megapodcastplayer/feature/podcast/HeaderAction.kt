package md.borisveriga.megapodcastplayer.feature.podcast

import md.borisveriga.megapodcastplayer.core.model.Episode

/**
 * What the one button under a show's title does.
 *
 * The button used to be *Play latest*, and it meant `episodes.first()` — the newest episode, played
 * from wherever its stored position happened to be, whether or not the user had already finished it.
 * Two things were wrong with that, and both are the kind a user meets on their second visit to a
 * show rather than their first:
 *
 *  - it ignored an episode already in progress, so the commonest reason to open a show's page —
 *    picking up where you left off — was the one thing the button could not do;
 *  - on a show that is fully caught up it silently restarted the newest episode, which reads as the
 *    app having lost the fact that you listened to it.
 *
 * So it is derived rather than assumed, from the show's own episodes in feed order.
 */
internal sealed interface HeaderAction {

    /** The episode the button plays. */
    val episodeId: String

    /**
     * Something is half-listened-to; the button picks it back up.
     *
     * @property episodeId the in-progress episode.
     * @property remainingMs how much of it is left, or null when the duration is unknown. The label
     *   says it — "Continue · 12 min left" — because how long is left is the thing that decides
     *   whether to press it now or later.
     */
    data class Continue(
        override val episodeId: String,
        val remainingMs: Long?,
    ) : HeaderAction

    /**
     * Nothing is in progress; the button starts the newest episode that has not been played.
     *
     * @property episodeId the episode to play.
     * @property isReplay true when every episode has been played and this is the newest one being
     *   offered again. The label says so, so that pressing it is a decision rather than a surprise.
     */
    data class Play(
        override val episodeId: String,
        val isReplay: Boolean,
    ) : HeaderAction
}

/**
 * Picks the header action for a show's episodes.
 *
 * The list is in feed order, which for an RSS show is newest first, so "the first one that matches"
 * is "the newest one that matches" without this having to know the order's provenance.
 *
 * Order of preference: continue what was started, then start the newest unplayed episode, then —
 * only when there is nothing left unheard — offer the newest one again.
 *
 * Reads the show's whole list rather than whatever the filter chips are showing. The button is
 * about the *show*: a filter is a way of reading the list below it, and a *Continue* that vanished
 * because the user tapped *Downloaded* would be answering a question nobody asked.
 *
 * @return the action, or null for a show with no episodes at all, where the button is not drawn.
 */
internal fun List<Episode>.headerAction(): HeaderAction? {
    firstOrNull { it.isInProgress }?.let { started ->
        return HeaderAction.Continue(
            episodeId = started.id,
            remainingMs = started.durationMs?.let { duration -> duration - started.positionMs },
        )
    }

    firstOrNull { !it.isPlayed }?.let { unplayed ->
        return HeaderAction.Play(episodeId = unplayed.id, isReplay = false)
    }

    return firstOrNull()?.let { newest ->
        HeaderAction.Play(episodeId = newest.id, isReplay = true)
    }
}
