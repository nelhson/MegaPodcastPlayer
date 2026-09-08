package md.borisveriga.megapodcastplayer.core.data.chapters

import javax.inject.Inject
import javax.inject.Singleton
import md.borisveriga.megapodcastplayer.core.database.dao.EpisodeDao
import md.borisveriga.megapodcastplayer.core.database.model.asExternalModel
import md.borisveriga.megapodcastplayer.core.media.PlaybackChapterSource
import md.borisveriga.megapodcastplayer.core.model.chapters.Chapter

/**
 * Answers the playback service's "what are this episode's chapters" with [ChapterResolver]'s
 * answer.
 *
 * Two lines of work and a whole class, because the point is *which* two lines: the row is read
 * here and resolved by the same resolver the player screen uses, so the notification's *next* and
 * the app's transport cannot disagree about where a chapter begins. Anything cleverer — a cache of
 * its own, a different source order — would be a second opinion, and two opinions about a chapter
 * boundary is a bug the user experiences as the button being wrong in one place.
 *
 * @property episodeDao reads the row the resolver works from.
 * @property chapterResolver the one place chapters are resolved.
 */
@Singleton
class EpisodeChapterSource @Inject constructor(
    private val episodeDao: EpisodeDao,
    private val chapterResolver: ChapterResolver,
) : PlaybackChapterSource {

    override suspend fun chaptersFor(episodeId: String): List<Chapter> {
        // A queue entry can outlive the show it came from; that is an absence, not a failure.
        val episode = episodeDao.getById(episodeId) ?: return emptyList()
        return chapterResolver.chaptersFor(episode.asExternalModel()).chapters
    }
}
