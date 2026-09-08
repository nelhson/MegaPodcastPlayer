package md.borisveriga.megapodcastplayer.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import md.borisveriga.megapodcastplayer.core.model.AppearanceSettings
import md.borisveriga.megapodcastplayer.core.model.DownloadSettings
import md.borisveriga.megapodcastplayer.core.model.LibraryLayout
import md.borisveriga.megapodcastplayer.core.model.LibrarySort
import md.borisveriga.megapodcastplayer.core.model.PlaybackSettings
import md.borisveriga.megapodcastplayer.core.model.ShowSettings
import md.borisveriga.megapodcastplayer.core.model.ShowSettingsCodec
import md.borisveriga.megapodcastplayer.core.model.ThemeChoice

/**
 * Reads and writes the small, user-owned settings that are not worth a database table.
 *
 * Everything here is a scalar the player needs on start-up, so DataStore Preferences is a better
 * fit than Room: no schema, no migrations, and the first read is already asynchronous.
 *
 * @property dataStore the backing preferences store; injected so tests can supply a temp-file store.
 */
@Singleton
class UserPreferencesDataSource @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    /** Observes the current playback settings, falling back to [PlaybackSettings]'s defaults. */
    val playbackSettings: Flow<PlaybackSettings> = dataStore.data.map { preferences ->
        PlaybackSettings(
            // Clamped on read as well as on write so that a corrupt file can never hand ExoPlayer
            // a non-positive speed, which it throws on.
            speed = (preferences[Keys.SPEED] ?: PlaybackSettings.DEFAULT_SPEED)
                .coerceIn(PlaybackSettings.SPEED_RANGE),
            skipForwardMs = preferences[Keys.SKIP_FORWARD_MS]
                ?: PlaybackSettings.DEFAULT_SKIP_FORWARD_MS,
            skipBackMs = preferences[Keys.SKIP_BACK_MS]
                ?: PlaybackSettings.DEFAULT_SKIP_BACK_MS,
            autoPlayNext = preferences[Keys.AUTO_PLAY_NEXT] ?: true,
        )
    }

    /**
     * Observes the download rules, falling back to [DownloadSettings]'s defaults.
     *
     * The keep-limit is clamped on read as well as on write: a value below [DownloadSettings.KEEP_ALL]
     * would be invisible in the settings screen while still quietly deleting episodes.
     */
    val downloadSettings: Flow<DownloadSettings> = dataStore.data.map { preferences ->
        DownloadSettings(
            autoDownloadNewEpisodes = preferences[Keys.AUTO_DOWNLOAD] ?: false,
            unmeteredOnly = preferences[Keys.UNMETERED_ONLY] ?: true,
            keepLimitPerPodcast = (
                preferences[Keys.KEEP_LIMIT] ?: DownloadSettings.DEFAULT_KEEP_LIMIT
                ).coerceAtLeast(DownloadSettings.KEEP_ALL),
            deleteAfterPlaying = preferences[Keys.DELETE_AFTER_PLAYING] ?: true,
        )
    }

    /** Observes how the library screen draws its shows; the default until one is chosen. */
    val libraryLayout: Flow<LibraryLayout> = dataStore.data.map { preferences ->
        preferences[Keys.LIBRARY_LAYOUT]
            ?.let(LibraryLayout::valueOf)
            ?: LibraryLayout.DEFAULT
    }

    /**
     * Observes how the app should draw itself; the defaults until anything is chosen.
     *
     * One flow for the three values rather than three, because the theme cannot be applied one
     * third at a time: every reader of this wants all of it at once.
     */
    val appearanceSettings: Flow<AppearanceSettings> = dataStore.data.map { preferences ->
        AppearanceSettings(
            // A name this build does not know falls back to the default rather than throwing, for
            // the same reason the sort order does — nothing wipes this file between builds.
            theme = preferences[Keys.THEME]
                ?.let { stored -> ThemeChoice.entries.firstOrNull { it.name == stored } }
                ?: AppearanceSettings.DEFAULT.theme,
            dynamicColor = preferences[Keys.DYNAMIC_COLOR]
                ?: AppearanceSettings.DEFAULT.dynamicColor,
            pureBlack = preferences[Keys.PURE_BLACK] ?: AppearanceSettings.DEFAULT.pureBlack,
        )
    }

    /**
     * Observes the order the library lists its shows in; the default until one is chosen.
     *
     * A name this build does not know falls back to the default rather than throwing. Preferences
     * outlive an install in a way the database does not — nothing wipes this file — so a sort order
     * dropped from the enum between builds must not be able to crash the library screen.
     */
    val librarySort: Flow<LibrarySort> = dataStore.data.map { preferences ->
        preferences[Keys.LIBRARY_SORT]
            ?.let { stored -> LibrarySort.entries.firstOrNull { it.name == stored } }
            ?: LibrarySort.DEFAULT
    }

    /**
     * Observes the hand-made ordering of the downloads screen, first row first.
     *
     * Empty until the user drags something, which is what leaves the screen on the state-based
     * ordering the DAO produces until then. Ids that are no longer downloads are kept rather than
     * pruned: an episode removed and fetched again should come back where it was put, and the list
     * is at most a few hundred forty-character ids.
     */
    val downloadOrder: Flow<List<String>> = dataStore.data.map { preferences ->
        preferences[Keys.DOWNLOAD_ORDER]
            ?.split(ID_SEPARATOR)
            // A stored empty string splits to one empty id, which would match no episode but would
            // still make the order look non-empty.
            ?.filter { it.isNotEmpty() }
            .orEmpty()
    }

    /**
     * Observes the last few things the user searched for, most recent first.
     *
     * Kept for ADD-5: the search field opens on nothing, and the thing that actually happens here
     * is the same show looked up twice, because the first attempt was made on the other device.
     * Stored the same way [downloadOrder] is and for the same reason — DataStore's `stringSet` has
     * no order, and the order is the whole point of a *recent* list.
     */
    val recentSearches: Flow<List<String>> = dataStore.data.map { preferences ->
        preferences[Keys.RECENT_SEARCHES]
            ?.split(ID_SEPARATOR)
            ?.filter { it.isNotEmpty() }
            .orEmpty()
    }

    /**
     * Observes the episode the player was last given, or null if nothing has been played.
     *
     * This is what lets a cold start — or a system-initiated playback resumption from the
     * Bluetooth headset button — restore the right episode without waiting on a feed refresh.
     */
    val lastPlayedEpisodeId: Flow<String?> =
        dataStore.data.map { preferences -> preferences[Keys.LAST_PLAYED_EPISODE_ID] }

    /**
     * Observes when a backup was last exported, or null if one never has been.
     *
     * The settings screen shows this so that "no backup yet" is on screen *before* a release that
     * changes the database schema and therefore wipes it — the warning has to reach the user while
     * they can still act on it.
     */
    val lastBackupAtMs: Flow<Long?> =
        dataStore.data.map { preferences -> preferences[Keys.LAST_BACKUP_AT_MS] }

    /**
     * Sets the playback rate.
     *
     * @param speed the requested rate; clamped to [PlaybackSettings.SPEED_RANGE].
     */
    suspend fun setSpeed(speed: Float) {
        dataStore.edit { it[Keys.SPEED] = speed.coerceIn(PlaybackSettings.SPEED_RANGE) }
    }

    /**
     * Sets the skip intervals.
     *
     * @param forwardMs skip-ahead distance; values below one second are ignored as mis-taps.
     * @param backMs skip-back distance; same rule.
     */
    suspend fun setSkipIntervals(forwardMs: Long, backMs: Long) {
        dataStore.edit { preferences ->
            preferences[Keys.SKIP_FORWARD_MS] = forwardMs.coerceAtLeast(MIN_SKIP_MS)
            preferences[Keys.SKIP_BACK_MS] = backMs.coerceAtLeast(MIN_SKIP_MS)
        }
    }

    /** Enables or disables advancing to the next queued episode when one finishes. */
    suspend fun setAutoPlayNext(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_PLAY_NEXT] = enabled }
    }

    /** Enables or disables downloading episodes as a feed refresh discovers them. */
    suspend fun setAutoDownloadNewEpisodes(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_DOWNLOAD] = enabled }
    }

    /** Sets whether downloads wait for an unmetered network. */
    suspend fun setUnmeteredOnly(enabled: Boolean) {
        dataStore.edit { it[Keys.UNMETERED_ONLY] = enabled }
    }

    /**
     * Sets how many downloaded episodes to keep per show.
     *
     * @param limit the new limit; [DownloadSettings.KEEP_ALL] disables the sweep. Negative values
     *   are treated as [DownloadSettings.KEEP_ALL] rather than rejected, so a bad caller cannot
     *   produce a limit that deletes everything.
     */
    suspend fun setKeepLimitPerPodcast(limit: Int) {
        dataStore.edit {
            it[Keys.KEEP_LIMIT] = limit.coerceAtLeast(DownloadSettings.KEEP_ALL)
        }
    }

    /** Sets whether finishing an episode removes its downloaded audio. */
    suspend fun setDeleteAfterPlaying(enabled: Boolean) {
        dataStore.edit { it[Keys.DELETE_AFTER_PLAYING] = enabled }
    }

    /**
     * Records which layout the library screen is showing.
     *
     * @param layout the chosen layout; stored by name so the file stays readable and a reordered
     *   enum cannot silently change what an existing install shows.
     */
    suspend fun setLibraryLayout(layout: LibraryLayout) {
        dataStore.edit { it[Keys.LIBRARY_LAYOUT] = layout.name }
    }

    /**
     * Records which palette the app draws itself in.
     *
     * @param theme the chosen palette; stored by name, as the layout and the sort order are.
     */
    suspend fun setTheme(theme: ThemeChoice) {
        dataStore.edit { it[Keys.THEME] = theme.name }
    }

    /**
     * Records whether the palette comes from the wallpaper.
     *
     * @param enabled true to take Material You's colours instead of the app's own.
     */
    suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    /**
     * Records whether the dark theme is drawn on true black.
     *
     * @param enabled true for black backgrounds rather than very dark grey.
     */
    suspend fun setPureBlack(enabled: Boolean) {
        dataStore.edit { it[Keys.PURE_BLACK] = enabled }
    }

    /**
     * Records the order the library lists its shows in.
     *
     * @param sort the chosen order; stored by name, as [setLibraryLayout] stores its layout and for
     *   the same reason.
     */
    suspend fun setLibrarySort(sort: LibrarySort) {
        dataStore.edit { it[Keys.LIBRARY_SORT] = sort.name }
    }

    /**
     * Stores the hand-made ordering of the downloads screen.
     *
     * Written as one separated string rather than a `stringSet`, which DataStore does not order.
     * Episode ids are SHA-1 hex (see `episodeIdOf` in `:core:model`), so no id can contain the
     * separator and the round trip is lossless.
     *
     * @param episodeIds the downloads in the order they should appear, first row first.
     */
    suspend fun setDownloadOrder(episodeIds: List<String>) {
        dataStore.edit { it[Keys.DOWNLOAD_ORDER] = episodeIds.joinToString(ID_SEPARATOR) }
    }

    /**
     * Records a search worth offering again, at the head of the list.
     *
     * Case-insensitively de-duplicated, so looking the same show up a third time moves its term to
     * the front rather than filling the list with it. Newlines are folded to spaces because the
     * separator is one — a single-line field cannot produce one today, and a lossy round trip is
     * not a thing to leave waiting for the day it can.
     *
     * @param term what was searched for; blank is ignored rather than stored.
     */
    suspend fun addRecentSearch(term: String) {
        val cleaned = term.replace(ID_SEPARATOR, " ").trim()
        if (cleaned.isEmpty()) return
        dataStore.edit { preferences ->
            val existing = preferences[Keys.RECENT_SEARCHES]
                ?.split(ID_SEPARATOR)
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            val updated = (listOf(cleaned) + existing.filterNot { it.equals(cleaned, true) })
                .take(MAX_RECENT_SEARCHES)
            preferences[Keys.RECENT_SEARCHES] = updated.joinToString(ID_SEPARATOR)
        }
    }

    /** Forgets every stored search term. */
    suspend fun clearRecentSearches() {
        dataStore.edit { it.remove(Keys.RECENT_SEARCHES) }
    }

    /**
     * Records which episode the player is on.
     *
     * @param episodeId the episode, or null once the player is stopped and the queue is empty.
     */
    suspend fun setLastPlayedEpisodeId(episodeId: String?) {
        dataStore.edit { preferences ->
            if (episodeId == null) {
                preferences.remove(Keys.LAST_PLAYED_EPISODE_ID)
            } else {
                preferences[Keys.LAST_PLAYED_EPISODE_ID] = episodeId
            }
        }
    }

    /**
     * Records that a backup was exported successfully.
     *
     * @param exportedAtMs when the export was written, epoch milliseconds.
     */
    suspend fun setLastBackupAt(exportedAtMs: Long) {
        dataStore.edit { it[Keys.LAST_BACKUP_AT_MS] = exportedAtMs }
    }

    /**
     * Observes what the user has decided about individual shows, keyed by podcast id.
     *
     * A show that has never been touched is absent rather than present with defaults, so the map
     * stays the size of the decisions actually made rather than the size of the library.
     */
    val showSettings: Flow<Map<String, ShowSettings>> = dataStore.data.map { preferences ->
        ShowSettingsCodec.decode(preferences[Keys.SHOW_SETTINGS])
    }

    /**
     * Changes one show's settings.
     *
     * Read-modify-write inside `edit`, which DataStore serialises, so two screens changing two
     * different shows at once cannot lose one of the changes — the failure a naive "read the flow,
     * write the map" would have.
     *
     * A show whose settings come back to the defaults is removed rather than stored, so the map
     * does not accumulate a row for every show the user has ever glanced at with a filter on.
     *
     * @param podcastId the show being changed.
     * @param transform receives the show's current settings and returns the new ones.
     */
    suspend fun updateShowSettings(
        podcastId: String,
        transform: (ShowSettings) -> ShowSettings,
    ) {
        dataStore.edit { preferences ->
            val current = ShowSettingsCodec.decode(preferences[Keys.SHOW_SETTINGS])
            val updated = transform(current[podcastId] ?: ShowSettings.DEFAULT)
            val next = if (updated.isDefault) {
                current - podcastId
            } else {
                current + (podcastId to updated)
            }
            preferences[Keys.SHOW_SETTINGS] = ShowSettingsCodec.encode(next)
        }
    }

    /** Preference keys, kept private so the key strings are a storage detail. */
    private object Keys {
        val SPEED = floatPreferencesKey("playback_speed")
        val SKIP_FORWARD_MS = longPreferencesKey("skip_forward_ms")
        val SKIP_BACK_MS = longPreferencesKey("skip_back_ms")
        val AUTO_PLAY_NEXT = booleanPreferencesKey("auto_play_next")
        val LAST_PLAYED_EPISODE_ID = stringPreferencesKey("last_played_episode_id")
        val AUTO_DOWNLOAD = booleanPreferencesKey("auto_download_new_episodes")
        val UNMETERED_ONLY = booleanPreferencesKey("download_unmetered_only")
        val KEEP_LIMIT = intPreferencesKey("download_keep_limit_per_podcast")
        val DELETE_AFTER_PLAYING = booleanPreferencesKey("delete_after_playing")
        val LIBRARY_LAYOUT = stringPreferencesKey("library_layout")
        val LIBRARY_SORT = stringPreferencesKey("library_sort")
        val THEME = stringPreferencesKey("theme")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val PURE_BLACK = booleanPreferencesKey("pure_black")
        val DOWNLOAD_ORDER = stringPreferencesKey("download_order")
        val LAST_BACKUP_AT_MS = longPreferencesKey("last_backup_at_ms")
        val SHOW_SETTINGS = stringPreferencesKey("show_settings")
        val RECENT_SEARCHES = stringPreferencesKey("recent_searches")
    }

    private companion object {
        /** Anything shorter than a second is a mis-tap, not a preference. */
        const val MIN_SKIP_MS = 1_000L

        /** Separates the ids in the stored downloads order; see [setDownloadOrder]. */
        const val ID_SEPARATOR = "\n"

        /**
         * How many search terms are kept.
         *
         * Short on purpose: this is a memory aid, not a history. A list long enough to scroll would
         * be a second thing to read on a screen whose job is to get out of the way.
         */
        const val MAX_RECENT_SEARCHES = 8
    }
}
