package md.borisveriga.megapodcastplayer.core.testing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An in-memory [DataStore] of [Preferences] for tests.
 *
 * DataStore's file storage writes to a `.tmp` sibling and atomically renames it over the target.
 * On Windows that rename fails whenever the target already exists, so the real file-backed store
 * cannot survive a second write in a JVM unit test on that platform. What the tests actually care
 * about — defaults, clamping and clearing — lives in the mapping layer above DataStore, not in
 * serialisation, so an in-memory store loses nothing.
 *
 * Writes are serialised through a [Mutex], mirroring DataStore's own guarantee that transforms run
 * one at a time.
 */
class InMemoryPreferencesDataStore : DataStore<Preferences> {

    private val state = MutableStateFlow(emptyPreferences())
    private val writeLock = Mutex()

    override val data: Flow<Preferences> = state

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences,
    ): Preferences = writeLock.withLock {
        val updated = transform(state.value)
        state.value = updated
        updated
    }
}
