package md.borisveriga.megapodcastplayer.core.model

/**
 * A timestamped bookmark the user saved while listening.
 *
 * A moment is saved *after* the thing worth remembering was said — reaction time, plus however long
 * it takes to reach a button — so playing one back starts a few seconds early; see
 * [MOMENT_PRE_ROLL_MS].
 *
 * @property id local row id.
 * @property episodeId the episode the moment is in.
 * @property positionMs where in the episode it was marked.
 * @property note the user's own note, or null when they saved without adding one.
 * @property createdAtMs when it was saved, epoch milliseconds.
 */
data class Moment(
    val id: Long,
    val episodeId: String,
    val positionMs: Long,
    val note: String?,
    val createdAtMs: Long,
) {
    /** Where playback should start to catch what prompted this moment. */
    val resumePositionMs: Long get() = (positionMs - MOMENT_PRE_ROLL_MS).coerceAtLeast(0L)
}

/**
 * A moment plus everything needed to say what it is about and to point at it from outside the app.
 *
 * The two URLs are not decoration. A moment's whole value is that it survives the app: the database
 * is recreated rather than migrated, so a share or an export that named only titles would be a
 * memory of something the user could no longer find. [feedUrl] is what re-adds the show — it is the
 * same string a backup stores, and the one [podcastIdOf] hashes — and [audioUrl] is what
 * [momentLink] turns into a link that opens at the right second.
 *
 * @property moment the moment itself.
 * @property episodeTitle the episode's title.
 * @property showTitle the show's title.
 * @property showArtworkUrl the show's artwork, for the row's thumbnail.
 * @property feedUrl the show's feed URL, which is both its identity and the way back to it.
 * @property audioUrl the episode's stored audio URL: a real enclosure URL, or the
 *   [youTubeAudioSentinel] for a video.
 */
data class MomentWithEpisode(
    val moment: Moment,
    val episodeTitle: String,
    val showTitle: String,
    val showArtworkUrl: String?,
    val feedUrl: String,
    val audioUrl: String,
) {
    /** A link that opens this moment where it was marked, or null when the URL cannot carry one. */
    val link: String? get() = momentLink(audioUrl, moment.positionMs)
}

/**
 * How far before a mark playback resumes.
 *
 * Five seconds, which is the shortest interval the app's own skip-back control offers, so it is a
 * distance the user already has a physical feel for — and short enough that a moment saved three
 * seconds into an episode clamping to zero feels right rather than lossy.
 */
const val MOMENT_PRE_ROLL_MS: Long = 5_000L

/**
 * How close two marks have to be before they are treated as one.
 *
 * The unique index on `(episode_id, position_ms)` only collapses a repeat at the identical
 * millisecond, which two presses of a button reached for without looking never are. Ten seconds is
 * wide enough to absorb that fumble and narrow enough that two genuinely different remarks — which
 * are seconds of speech apart, not fractions of one — stay two moments.
 */
const val MOMENT_MERGE_WINDOW_MS: Long = 10_000L
