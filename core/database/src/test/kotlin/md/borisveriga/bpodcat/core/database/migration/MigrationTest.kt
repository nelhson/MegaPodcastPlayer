package md.borisveriga.bpodcat.core.database.migration

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import md.borisveriga.bpodcat.core.database.BPodcatDatabase
import md.borisveriga.bpodcat.core.model.PodcastSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests [MIGRATION_1_2] against a real on-disk database written the way version 1 wrote it.
 *
 * This is the first migration the app has ever shipped, and the failure mode it guards is not
 * subtle: if `MIGRATION_1_2`'s `DEFAULT 'RSS'` and `PodcastEntity`'s `@ColumnInfo(defaultValue)`
 * ever disagree, Room's identity check rejects the database at open and the app cannot start for
 * anyone upgrading — while a fresh install works perfectly, so nothing else would catch it.
 *
 * That check runs as part of opening the database in [migrates a v1 library without losing data],
 * so the assertions below are really the second line of defence; getting that far at all is the
 * first.
 *
 * `MigrationTestHelper` is deliberately not used: under Robolectric it wants the exported schema
 * JSON on the asset path, which means extra source-set plumbing for no extra safety, since the
 * statements below are copied verbatim from the committed `1.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun deleteAnyLeftoverDatabase() {
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun `migrates a v1 library without losing data`() = runTest {
        writeVersion1Database()

        val database = Room.databaseBuilder(context, BPodcatDatabase::class.java, DB_NAME)
            .addMigrations(MIGRATION_1_2)
            .build()

        try {
            // Reading through the DAO forces the open, and therefore the migration and Room's
            // schema validation of the result.
            val podcast = checkNotNull(database.podcastDao().getById("podcast-1"))

            assertEquals("Podlodka Podcast", podcast.title)
            assertEquals("https://feeds.example.com/podlodka.rss", podcast.feedUrl)
            // Everything that predates YouTube support is an RSS feed, and the column default is
            // what says so — no row is inspected or rewritten by the migration.
            assertEquals(PodcastSource.RSS, podcast.source)

            val episode = checkNotNull(database.episodeDao().getById("episode-1"))
            assertEquals("https://cdn.example.com/episode-1.mp3", episode.audioUrl)
            // The position is the thing a careless migration would cost the user.
            assertEquals(123_000L, episode.positionMs)
            assertEquals(true, episode.isPlayed)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a migrated database accepts youtube shows`() = runTest {
        writeVersion1Database()

        val database = Room.databaseBuilder(context, BPodcatDatabase::class.java, DB_NAME)
            .addMigrations(MIGRATION_1_2)
            .build()

        try {
            val existing = checkNotNull(database.podcastDao().getById("podcast-1"))
            database.podcastDao().upsert(
                existing.copy(
                    id = "podcast-2",
                    feedUrl = "https://www.youtube.com/feeds/videos.xml?playlist_id=PL123456789012",
                    source = PodcastSource.YOUTUBE,
                ),
            )

            assertEquals(
                PodcastSource.YOUTUBE,
                checkNotNull(database.podcastDao().getById("podcast-2")).source,
            )
            // The pre-existing row is untouched by the write.
            assertEquals(
                PodcastSource.RSS,
                checkNotNull(database.podcastDao().getById("podcast-1")).source,
            )
        } finally {
            database.close()
        }
    }

    /**
     * Creates a database exactly as version 1 left it: the committed `1.json` DDL, a seeded
     * `room_master_table`, `user_version = 1`, and one show with one partly-played episode.
     */
    private fun writeVersion1Database() {
        val file = context.getDatabasePath(DB_NAME)
        file.parentFile?.mkdirs()

        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            for (statement in VERSION_1_SCHEMA) {
                db.execSQL(statement)
            }
            db.execSQL(
                "INSERT INTO room_master_table (id, identity_hash) VALUES (42, ?)",
                arrayOf(VERSION_1_IDENTITY_HASH),
            )
            db.execSQL(
                """
                INSERT INTO podcasts
                    (id, itunes_id, title, author, feed_url, artwork_url, description,
                     added_at, last_refresh_at, etag, last_modified, auto_refresh)
                VALUES ('podcast-1', 1209828744, 'Podlodka Podcast', 'Егор Толстой',
                        'https://feeds.example.com/podlodka.rss', NULL, '',
                        1756684800000, NULL, NULL, NULL, 1)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO episodes
                    (id, podcast_id, guid, title, description, audio_url, artwork_url,
                     duration_ms, published_at, size_bytes, position_ms, is_played, is_new,
                     download_state, downloaded_bytes, download_percent)
                VALUES ('episode-1', 'podcast-1', 'guid-1', 'Episode 1', 'notes',
                        'https://cdn.example.com/episode-1.mp3', NULL,
                        3600000, 1756684800000, NULL, 123000, 1, 0,
                        'NOT_DOWNLOADED', 0, 0)
                """.trimIndent(),
            )
            db.version = 1
        } finally {
            db.close()
        }
    }

    private companion object {
        const val DB_NAME = "migration-test.db"

        /** `database.identityHash` from the committed `schemas/…/1.json`. */
        const val VERSION_1_IDENTITY_HASH = "b2f5b9a64b49395701e6a96147adc88a"

        /**
         * Version 1's DDL, copied verbatim from `1.json` with `${'$'}{TABLE_NAME}` substituted.
         */
        val VERSION_1_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `room_master_table` " +
                "(`id` INTEGER PRIMARY KEY, `identity_hash` TEXT)",
            "CREATE TABLE IF NOT EXISTS `podcasts` (`id` TEXT NOT NULL, `itunes_id` INTEGER, " +
                "`title` TEXT NOT NULL, `author` TEXT NOT NULL, `feed_url` TEXT NOT NULL, " +
                "`artwork_url` TEXT, `description` TEXT NOT NULL, `added_at` INTEGER NOT NULL, " +
                "`last_refresh_at` INTEGER, `etag` TEXT, `last_modified` TEXT, " +
                "`auto_refresh` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_podcasts_feed_url` ON `podcasts` (`feed_url`)",
            "CREATE TABLE IF NOT EXISTS `episodes` (`id` TEXT NOT NULL, " +
                "`podcast_id` TEXT NOT NULL, `guid` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                "`description` TEXT NOT NULL, `audio_url` TEXT NOT NULL, `artwork_url` TEXT, " +
                "`duration_ms` INTEGER, `published_at` INTEGER, `size_bytes` INTEGER, " +
                "`position_ms` INTEGER NOT NULL DEFAULT 0, " +
                "`is_played` INTEGER NOT NULL DEFAULT 0, `is_new` INTEGER NOT NULL DEFAULT 0, " +
                "`download_state` TEXT NOT NULL DEFAULT 'NOT_DOWNLOADED', " +
                "`downloaded_bytes` INTEGER NOT NULL DEFAULT 0, " +
                "`download_percent` REAL NOT NULL DEFAULT 0, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`podcast_id`) REFERENCES `podcasts`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_episodes_podcast_id_published_at` " +
                "ON `episodes` (`podcast_id`, `published_at`)",
            "CREATE INDEX IF NOT EXISTS `index_episodes_download_state` " +
                "ON `episodes` (`download_state`)",
            "CREATE TABLE IF NOT EXISTS `queue` (`episode_id` TEXT NOT NULL, " +
                "`position` INTEGER NOT NULL, PRIMARY KEY(`episode_id`), " +
                "FOREIGN KEY(`episode_id`) REFERENCES `episodes`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
    }
}
