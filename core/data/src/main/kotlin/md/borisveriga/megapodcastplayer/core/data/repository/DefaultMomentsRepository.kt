package md.borisveriga.megapodcastplayer.core.data.repository

import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter
import md.borisveriga.megapodcastplayer.core.common.di.Dispatcher
import md.borisveriga.megapodcastplayer.core.common.di.MegaPodcastPlayerDispatcher
import md.borisveriga.megapodcastplayer.core.common.result.suspendRunCatching
import md.borisveriga.megapodcastplayer.core.database.dao.MomentDao
import md.borisveriga.megapodcastplayer.core.database.model.MomentEntity
import md.borisveriga.megapodcastplayer.core.database.model.asExternalModel
import md.borisveriga.megapodcastplayer.core.model.MOMENT_MERGE_WINDOW_MS
import md.borisveriga.megapodcastplayer.core.model.Moment
import md.borisveriga.megapodcastplayer.core.model.MomentWithEpisode
import md.borisveriga.megapodcastplayer.core.model.momentsMarkdown

/**
 * Room-backed implementation of [MomentsRepository].
 *
 * @property momentDao the moments table.
 * @property clock injected so a saved timestamp and an export header are deterministic in tests.
 * @property crashReporter told when a mark cannot be written. The user is shown that nothing was
 *   saved, but the reason — a foreign key naming an episode a refresh has since pruned — is
 *   otherwise invisible.
 * @property ioDispatcher dispatcher for the database work.
 */
@Singleton
class DefaultMomentsRepository @Inject constructor(
    private val momentDao: MomentDao,
    private val clock: Clock,
    private val crashReporter: CrashReporter,
    @Dispatcher(MegaPodcastPlayerDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : MomentsRepository {

    override fun observeMoments(): Flow<List<MomentWithEpisode>> =
        momentDao.observeAllWithEpisode().map { rows -> rows.map { it.asExternalModel() } }

    override fun observeCountForEpisode(episodeId: String?): Flow<Int> =
        episodeId?.let(momentDao::observeCountForEpisode) ?: flowOf(0)

    override fun observeForEpisode(episodeId: String?): Flow<List<Moment>> =
        episodeId
            ?.let { id -> momentDao.observeForEpisode(id).map { rows -> rows.map(MomentEntity::asExternalModel) } }
            ?: flowOf(emptyList())

    override suspend fun mark(episodeId: String, positionMs: Long, note: String?): Moment? =
        withContext(ioDispatcher) {
            val at = positionMs.coerceAtLeast(0L)
            val nearby = momentDao.findNear(
                episodeId = episodeId,
                fromMs = at - MOMENT_MERGE_WINDOW_MS,
                toMs = at + MOMENT_MERGE_WINDOW_MS,
            )
            // An existing mark wins, and its note is left alone: a second press is the user
            // checking that the first one took, not an instruction to overwrite what they wrote.
            if (nearby != null) return@withContext nearby.asExternalModel()

            val row = MomentEntity(
                episodeId = episodeId,
                positionMs = at,
                note = note?.trim()?.takeIf { it.isNotEmpty() },
                createdAt = clock.millis(),
            )
            val id = suspendRunCatching { momentDao.insert(row) }
                .getOrElse { failure ->
                    // A foreign key naming an episode a refresh has since pruned is the one way
                    // this fails, and the user is only told that nothing was saved.
                    crashReporter.recordNonFatal("Moment insert failed", failure)
                    null
                }
                ?.takeIf { it > 0L }
                ?: return@withContext null

            row.copy(id = id).asExternalModel()
        }

    override suspend fun setNote(id: Long, note: String?) = withContext(ioDispatcher) {
        momentDao.updateNote(id, note?.trim()?.takeIf { it.isNotEmpty() })
    }

    override suspend fun delete(id: Long) = withContext(ioDispatcher) {
        momentDao.deleteById(id)
    }

    override suspend fun exportMarkdown(): String = withContext(ioDispatcher) {
        val moments = momentDao.getAllWithEpisode().map { it.asExternalModel() }
        if (moments.isEmpty()) "" else momentsMarkdown(moments, clock.millis())
    }
}
