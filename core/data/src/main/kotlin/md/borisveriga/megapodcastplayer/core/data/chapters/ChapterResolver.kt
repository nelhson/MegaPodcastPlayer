package md.borisveriga.megapodcastplayer.core.data.chapters

import javax.inject.Inject
import javax.inject.Singleton
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter
import md.borisveriga.megapodcastplayer.core.common.format.toPlainText
import md.borisveriga.megapodcastplayer.core.common.result.isConnectivityFailure
import md.borisveriga.megapodcastplayer.core.common.result.suspendRunCatching
import md.borisveriga.megapodcastplayer.core.database.dao.EpisodeDao
import md.borisveriga.megapodcastplayer.core.model.Episode
import md.borisveriga.megapodcastplayer.core.model.chapters.Chapter
import md.borisveriga.megapodcastplayer.core.model.chapters.ChapterJson
import md.borisveriga.megapodcastplayer.core.model.chapters.ChapterSource
import md.borisveriga.megapodcastplayer.core.model.chapters.chaptersFromDescription
import md.borisveriga.megapodcastplayer.core.network.chapters.ChaptersApi

/**
 * An episode's chapters, and where they came from.
 *
 * The source is kept because it is the honest answer to "can these be trusted", and the UI leans on
 * it: chapters read out of a description are inferred rather than published, and a list the app
 * guessed at is worth labelling as such if it ever turns out to be wrong.
 *
 * @property chapters the chapters, in start order; empty when the episode has none.
 * @property source where they were found, or null when there are none.
 */
data class EpisodeChapters(
    val chapters: List<Chapter> = emptyList(),
    val source: ChapterSource? = null,
) {
    /** True when there is a list worth drawing. */
    val isNotEmpty: Boolean get() = chapters.isNotEmpty()
}

/**
 * Finds an episode's chapters, from whichever source has them.
 *
 * `:core:model` has been parsing chapters since the feed parser was written, and `:core:database`
 * has been storing them, and no screen has ever asked for them. This is the missing middle: one
 * place that answers "what are this episode's chapters" so the player, the episode sheet and the
 * notification all get the same answer.
 *
 * The order of preference is a judgement about how deliberate each source is:
 *
 *  1. **the stored list** — an inline `psc:chapters` from the feed, or a document fetched earlier
 *     and cached here;
 *  2. **the publisher's `podcast:chapters` document**, fetched once and written back to the row so
 *     the next reader gets it from disk;
 *  3. **the episode description**, where a great many publishers — and every YouTube-sourced show —
 *     write their chapter list as a block of timestamps.
 *
 * [ChapterSource.ID3] is defined and not produced. Reading `CHAP` frames means reading the audio
 * itself, which for a streamed episode is a range request into a file that may be a hundred
 * megabytes; it is worth doing for a downloaded episode and is not worth doing here, on the way to
 * drawing a sheet.
 *
 * Failure is never propagated. An episode with no chapters and an episode whose chapters could not
 * be fetched look the same to every caller, because they should: there is nothing the user can do
 * about either, and a screen that refuses to open over an optional list is worse than one without
 * the list. The failure is recorded rather than swallowed.
 *
 * @property chaptersApi fetches the publisher's document.
 * @property episodeDao caches what was fetched.
 * @property crashReporter receives a fetch that failed for a reason other than having no network,
 *   which nothing else would ever surface.
 */
@Singleton
class ChapterResolver @Inject constructor(
    private val chaptersApi: ChaptersApi,
    private val episodeDao: EpisodeDao,
    private val crashReporter: CrashReporter,
) {

    /**
     * Resolves [episode]'s chapters.
     *
     * @param episode the episode, as stored.
     * @return the chapters and their source; empty when the episode has none.
     */
    suspend fun chaptersFor(episode: Episode): EpisodeChapters {
        ChapterJson.decode(episode.chaptersJson).takeIf { it.isNotEmpty() }?.let { stored ->
            return EpisodeChapters(stored, ChapterSource.INLINE_PSC)
        }

        episode.chaptersUrl?.takeIf { it.isNotBlank() }?.let { url ->
            fetched(episodeId = episode.id, url = url)
                .takeIf { it.isNotEmpty() }
                ?.let { return EpisodeChapters(it, ChapterSource.REMOTE_JSON) }
        }

        // The description is markup, and the timestamp reader works in lines: `<br>` and `</p>`
        // have to have become newlines before it sees them, which is what `toPlainText` does.
        val fromDescription = chaptersFromDescription(
            plainText = episode.description.toPlainText(),
            durationMs = episode.durationMs,
        )
        return if (fromDescription.isEmpty()) {
            EpisodeChapters()
        } else {
            EpisodeChapters(fromDescription, ChapterSource.DESCRIPTION)
        }
    }

    /**
     * Fetches and caches a publisher's chapters document.
     *
     * @param episodeId the row to cache onto.
     * @param url the absolute document URL.
     * @return the chapters, or empty when the fetch or the parse failed.
     */
    private suspend fun fetched(episodeId: String, url: String): List<Chapter> =
        suspendRunCatching {
            val chapters = chaptersApi.getChapters(url).toChapters()
            // Cached even when empty is *not* what happens: an empty list would be written as "[]"
            // and read back as "the feed said there are none", which would stop the description
            // fallback below from ever being tried again.
            if (chapters.isNotEmpty()) {
                episodeDao.setChaptersJson(episodeId, ChapterJson.encode(chapters))
            }
            chapters
        }.getOrElse { error ->
            // Nothing surfaces this to the user, and nothing should: an optional list that could
            // not be fetched is an absence, not a failure to report. But an absence nobody is shown
            // still goes somewhere — unless the network was simply not there, which is no fault of
            // the publisher's document and would be tried again on the next open anyway.
            if (!error.isConnectivityFailure) {
                crashReporter.recordNonFatal(CHAPTER_FETCH_FAILED, error)
            }
            emptyList()
        }

    private companion object {
        /** Fixed, so every failed chapter fetch groups into one report; see [CrashReporter]. */
        const val CHAPTER_FETCH_FAILED = "chapters document fetch failed"
    }
}
