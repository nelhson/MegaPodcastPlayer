package md.borisveriga.megapodcastplayer.core.model.chapters

import kotlinx.serialization.Serializable

/**
 * One chapter of an episode.
 *
 * Serializable because the inline `psc:chapters` list arrives in the feed body, which is not kept —
 * so it is stored as JSON on the episode row rather than re-fetched. Everything else about chapters
 * is derived on demand; see `ChapterResolver`.
 *
 * @property startMs where the chapter begins, from the start of the episode.
 * @property title the chapter's name.
 * @property imageUrl artwork the publisher attached to the chapter, if any. Parsed and stored, not
 *   yet rendered anywhere.
 * @property url a link the publisher attached to the chapter, if any.
 */
@Serializable
data class Chapter(
    val startMs: Long,
    val title: String,
    val imageUrl: String? = null,
    val url: String? = null,
)

/**
 * Where a list of chapters came from.
 *
 * Kept because the sources disagree in quality and the order they are tried is a deliberate
 * judgement rather than an accident of implementation; see `ChapterResolver`.
 */
enum class ChapterSource {

    /** A `podcast:chapters` JSON document the publisher hosts. The most deliberate answer. */
    REMOTE_JSON,

    /** ID3 `CHAP` frames inside the audio the user is actually hearing. */
    ID3,

    /** A `psc:chapters` list inline in the feed. */
    INLINE_PSC,

    /**
     * Timestamps parsed out of the episode description.
     *
     * Inferred rather than published, so it is tried last — but it is the only source a
     * YouTube-sourced show has, and a YouTube description's timestamp block is exactly this shape.
     */
    DESCRIPTION,
}

/** The most chapters that will be kept from any one source. */
const val MAX_CHAPTERS: Int = 200
