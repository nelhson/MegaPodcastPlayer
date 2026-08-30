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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the shipped migrations against real on-disk databases written the way the preceding version
 * wrote them.
 *
 * `MIGRATION_1_2`'s failure mode is not subtle: if its `DEFAULT 'RSS'` and `PodcastEntity`'s
 * `@ColumnInfo(defaultValue)` ever disagree, Room's identity check rejects the database at open and
 * the app cannot start for anyone upgrading — while a fresh install works perfectly, so nothing else
 * would catch it.
 *
 * That check runs as part of opening the database in [migrates a v1 library without losing data],
 * so the assertions below are really the second line of defence; getting that far at all is the
 * first.
 *
 * `MIGRATION_2_3` is the opposite shape — it changes no schema and only deletes rows — so there its
 * assertions *are* the test: what it removes, what it leaves alone, and what its cascade takes with
 * it.
 *
 * `MIGRATION_3_4` only adds an index, so its test is really Room's own schema validation:
 * an index whose name does not match what Room derives from the entity is rejected at open.
 *
 * Every open here chains the whole migration list, which also proves the three compose.
 *
 * `MigrationTestHelper` is deliberately not used: under Robolectric it wants the exported schema
 * JSON on the asset path, which means extra source-set plumbing for no extra safety, since the
 * statements below are copied verbatim from the committed `1.json` and `2.json`.
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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
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

    @Test
    fun `sweeps out episodes a hostile feed could have written before the url guard shipped`() = runTest {
        // MIGRATION_2_3's whole reason for existing. Version 2 accepted any enclosure URL, so a
        // library upgraded from it can still hold rows that would hand the player a local file or
        // another app's content provider. There is no correct URL to repair those to, so they go.
        writeVersion2Database()

        val database = Room.databaseBuilder(context, BPodcatDatabase::class.java, DB_NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()

        try {
            val episodeDao = database.episodeDao()

            assertNull(episodeDao.getById("episode-file"))
            assertNull(episodeDao.getById("episode-content"))
            assertNull(episodeDao.getById("episode-rtmp"))

            // Everything legitimate survives, listening positions and all.
            assertEquals(123_000L, checkNotNull(episodeDao.getById("episode-http")).positionMs)
            assertNotNull(episodeDao.getById("episode-https"))
            // The YouTube sentinel is minted internally and must not look hostile to the sweep.
            assertNotNull(episodeDao.getById("episode-youtube"))
            // Scheme comparison is case-insensitive on both sides of the guard: SQLite's LIKE is
            // ASCII-case-insensitive, and isPlayableMediaUrl compares the scheme ignoring case.
            assertNotNull(episodeDao.getById("episode-uppercase"))
        } finally {
            database.close()
        }
    }

    @Test
    fun `the sweep takes queue entries with it rather than leaving orphans`() = runTest {
        // Not as obvious as it looks, and this assertion is why: `queue.episode_id` has an ON
        // DELETE CASCADE, but Room runs migrations with `PRAGMA foreign_keys = OFF`, so inside a
        // migration the cascade silently does nothing. Without MIGRATION_2_3's explicit second
        // statement the deleted episode leaves a queue row the player can never resolve — which is
        // exactly what this test caught when the migration relied on the cascade.
        writeVersion2Database()

        val database = Room.databaseBuilder(context, BPodcatDatabase::class.java, DB_NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()

        try {
            val queued = database.queueDao().getEntries().map { it.episodeId }

            assertEquals(listOf("episode-http"), queued)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a v3 library gains the feed index without losing anything`() = runTest {
        writeVersion3Database()

        val database = Room.databaseBuilder(context, BPodcatDatabase::class.java, DB_NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()

        try {
            // Opening at all is the real assertion. Room reads the migrated database's indices
            // and compares them to the entity definitions, so a `published_at` index named
            // anything other than what Room generates for `Index(value = ["published_at"])` —
            // or a missing one — fails here rather than on the first upgrader's device.
            val episodeDao = database.episodeDao()

            assertEquals(123_000L, checkNotNull(episodeDao.getById("episode-http")).positionMs)
            assertNotNull(episodeDao.getById("episode-https"))
        } finally {
            database.close()
        }
    }

    @Test
    fun `the feed index migration is safe to meet twice`() = runTest {
        // A library that already carries the index — because Room created it on a fresh install at
        // v4 and the user then restored a backup, or simply because the statement ran once already
        // — must not abort the upgrade. `IF NOT EXISTS` is what makes that true; this pins it.
        writeVersion3Database(
            extraStatements = listOf(
                "CREATE INDEX IF NOT EXISTS `index_episodes_published_at` " +
                    "ON `episodes` (`published_at`)",
            ),
        )

        val database = Room.databaseBuilder(context, BPodcatDatabase::class.java, DB_NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()

        try {
            assertNotNull(database.episodeDao().getById("episode-http"))
        } finally {
            database.close()
        }
    }

    /**
     * Creates a database exactly as version 3 left it.
     *
     * Version 3 is schema-identical to version 2 — [MIGRATION_2_3] only deletes rows — so the DDL
     * and the identity hash are version 2's. Only the URLs [MIGRATION_2_3] would have swept are
     * left out, because a real v3 database cannot contain them.
     *
     * @param extraStatements DDL to run after the schema, for seeding a database that is already
     *   partly migrated.
     */
    private fun writeVersion3Database(extraStatements: List<String> = emptyList()) {
        val file = context.getDatabasePath(DB_NAME)
        file.parentFile?.mkdirs()

        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            for (statement in VERSION_2_SCHEMA + extraStatements) {
                db.execSQL(statement)
            }
            db.execSQL(
                "INSERT INTO room_master_table (id, identity_hash) VALUES (42, ?)",
                arrayOf(VERSION_3_IDENTITY_HASH),
            )
            db.execSQL(
                """
                INSERT INTO podcasts
                    (id, itunes_id, title, author, feed_url, artwork_url, description,
                     added_at, last_refresh_at, etag, last_modified, auto_refresh, source)
                VALUES ('podcast-1', NULL, 'Podlodka Podcast', '',
                        'https://feeds.example.com/podlodka.rss', NULL, '',
                        1756684800000, NULL, NULL, NULL, 1, 'RSS')
                """.trimIndent(),
            )
            for ((id, audioUrl) in VERSION_3_EPISODES) {
                db.execSQL(
                    """
                    INSERT INTO episodes
                        (id, podcast_id, guid, title, description, audio_url, artwork_url,
                         duration_ms, published_at, size_bytes, position_ms, is_played, is_new,
                         download_state, downloaded_bytes, download_percent)
                    VALUES (?, 'podcast-1', ?, 'Episode', '', ?, NULL,
                            NULL, 1756684800000, NULL, 123000, 0, 0,
                            'NOT_DOWNLOADED', 0, 0)
                    """.trimIndent(),
                    arrayOf(id, "guid-$id", audioUrl),
                )
            }
            db.version = 3
        } finally {
            db.close()
        }
    }

    /**
     * Creates a database exactly as version 2 left it, seeded with one show and seven episodes:
     * three whose URLs [MIGRATION_2_3] must delete and four it must keep. Both a hostile and a
     * legitimate episode are queued, so the queue cleanup is observable.
     */
    private fun writeVersion2Database() {
        val file = context.getDatabasePath(DB_NAME)
        file.parentFile?.mkdirs()

        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            for (statement in VERSION_2_SCHEMA) {
                db.execSQL(statement)
            }
            db.execSQL(
                "INSERT INTO room_master_table (id, identity_hash) VALUES (42, ?)",
                arrayOf(VERSION_2_IDENTITY_HASH),
            )
            db.execSQL(
                """
                INSERT INTO podcasts
                    (id, itunes_id, title, author, feed_url, artwork_url, description,
                     added_at, last_refresh_at, etag, last_modified, auto_refresh, source)
                VALUES ('podcast-1', NULL, 'Hostile Feed', '',
                        'https://feeds.example.com/hostile.rss', NULL, '',
                        1756684800000, NULL, NULL, NULL, 1, 'RSS')
                """.trimIndent(),
            )
            for ((id, audioUrl) in VERSION_2_EPISODES) {
                db.execSQL(
                    """
                    INSERT INTO episodes
                        (id, podcast_id, guid, title, description, audio_url, artwork_url,
                         duration_ms, published_at, size_bytes, position_ms, is_played, is_new,
                         download_state, downloaded_bytes, download_percent)
                    VALUES (?, 'podcast-1', ?, 'Episode', '', ?, NULL,
                            NULL, 1756684800000, NULL, 123000, 0, 0,
                            'NOT_DOWNLOADED', 0, 0)
                    """.trimIndent(),
                    arrayOf(id, "guid-$id", audioUrl),
                )
            }
            db.execSQL("INSERT INTO queue (episode_id, position) VALUES ('episode-http', 0)")
            db.execSQL("INSERT INTO queue (episode_id, position) VALUES ('episode-file', 1)")
            db.version = 2
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

        /** `database.identityHash` from the committed `schemas/…/2.json`. */
        const val VERSION_2_IDENTITY_HASH = "506ffd1e71abd818989ed68b690bedf5"

        /**
         * Episode rows seeded into a version 2 database, as `id to audio_url`.
         *
         * The first three are what [MIGRATION_2_3] exists to remove; the rest are what it must not
         * touch.
         */
        val VERSION_2_EPISODES = listOf(
            "episode-file" to "file:///data/data/md.borisveriga.bpodcat/databases/bpodcat.db",
            "episode-content" to "content://com.other.app.provider/secret",
            "episode-rtmp" to "rtmp://stream.example.com/live",
            "episode-http" to "http://cdn.example.com/one.mp3",
            "episode-https" to "https://cdn.example.com/two.mp3",
            "episode-youtube" to "youtube://video/niTJ2221aS8",
            "episode-uppercase" to "HTTPS://cdn.example.com/three.mp3",
        )

        /**
         * Version 2's DDL, copied verbatim from `2.json` with `${'$'}{TABLE_NAME}` substituted.
         *
         * Version 3 is schema-identical — [MIGRATION_2_3] only deletes rows — which is why no
         * version 3 DDL is needed here and why Room's identity check passes with the hash above.
         */
        val VERSION_2_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `room_master_table` " +
                "(`id` INTEGER PRIMARY KEY, `identity_hash` TEXT)",
            "CREATE TABLE IF NOT EXISTS `podcasts` (`id` TEXT NOT NULL, `itunes_id` INTEGER, " +
                "`title` TEXT NOT NULL, `author` TEXT NOT NULL, `feed_url` TEXT NOT NULL, " +
                "`artwork_url` TEXT, `description` TEXT NOT NULL, `added_at` INTEGER NOT NULL, " +
                "`last_refresh_at` INTEGER, `etag` TEXT, `last_modified` TEXT, " +
                "`auto_refresh` INTEGER NOT NULL, `source` TEXT NOT NULL DEFAULT 'RSS', " +
                "PRIMARY KEY(`id`))",
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

        /**
         * `database.identityHash` from the committed `schemas/…/3.json`.
         *
         * Identical to version 2's: [MIGRATION_2_3] deletes rows and changes no schema, so Room
         * derives the same hash for both. Named separately anyway, so that a future version 3
         * schema change makes the two diverge here rather than silently reusing a stale constant.
         */
        const val VERSION_3_IDENTITY_HASH = VERSION_2_IDENTITY_HASH

        /**
         * Episode rows seeded into a version 3 database, as `id to audio_url`.
         *
         * Only URLs [MIGRATION_2_3] would have kept: by version 3 the hostile ones are gone, so a
         * real database cannot hold them.
         */
        val VERSION_3_EPISODES = listOf(
            "episode-http" to "http://cdn.example.com/one.mp3",
            "episode-https" to "https://cdn.example.com/two.mp3",
        )
    }
}
