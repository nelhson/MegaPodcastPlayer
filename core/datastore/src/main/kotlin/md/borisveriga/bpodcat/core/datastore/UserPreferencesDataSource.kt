package md.borisveriga.bpodcat.core.datastore

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
import md.borisveriga.bpodcat.core.model.DownloadSettings
import md.borisveriga.bpodcat.core.model.LibraryLayout
import md.borisveriga.bpodcat.core.model.PlaybackSettings

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
            // Clamped on read as well as on write: a value written by an older build (or a corrupt
            // file) must never be handed to ExoPlayer, which throws on a non-positive speed.
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
     * The keep-limit is clamped to the offered steps on read as well as on write: a value from a
     * future build that this one does not offer would otherwise be invisible in the settings screen
     * while still quietly deleting episodes.
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

    /**
     * Observes how the library screen draws its shows.
     *
     * An unrecognised value falls back to the default rather than throwing: the stored string is
     * an enum name, and a build that offered a third layout would otherwise crash this one on
     * start-up.
     */
    val libraryLayout: Flow<LibraryLayout> = dataStore.data.map { preferences ->
        preferences[Keys.LIBRARY_LAYOUT]
            ?.let { name -> LibraryLayout.entries.firstOrNull { it.name == name } }
            ?: LibraryLayout.DEFAULT
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
     * Observes the episode the player was last given, or null if nothing has been played.
     *
     * This is what lets a cold start — or a system-initiated playback resumption from the
     * Bluetooth headset button — restore the right episode without waiting on a feed refresh.
     */
    val lastPlayedEpisodeId: Flow<String?> =
        dataStore.data.map { preferences -> preferences[Keys.LAST_PLAYED_EPISODE_ID] }

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
        val DOWNLOAD_ORDER = stringPreferencesKey("download_order")
    }

    private companion object {
        /** Anything shorter than a second is a mis-tap, not a preference. */
        const val MIN_SKIP_MS = 1_000L

        /** Separates the ids in the stored downloads order; see [setDownloadOrder]. */
        const val ID_SEPARATOR = "\n"
    }
}
