package md.borisveriga.bpodcat.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Schema migrations for the app database.
 *
 * Every migration here is registered in
 * [md.borisveriga.bpodcat.core.database.di.DatabaseModule.providesDatabase] and covered by a test
 * that opens a real database written by the previous version. The exported JSON under
 * `core/database/schemas` is the reference for what each version is supposed to look like.
 */

/**
 * Adds `podcasts.source`, which records whether a show is an RSS feed or a YouTube playlist.
 *
 * Everything already in a v1 library predates YouTube support and is therefore an RSS feed, so the
 * column default classifies every existing row correctly and no data has to be inspected or
 * rewritten.
 *
 * The `DEFAULT 'RSS'` must stay in step with `@ColumnInfo(defaultValue = "RSS")` on
 * [md.borisveriga.bpodcat.core.database.model.PodcastEntity]: Room compares the migrated schema
 * against the entity definition at open time and refuses to start if they disagree.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE podcasts ADD COLUMN source TEXT NOT NULL DEFAULT 'RSS'")
    }
}
