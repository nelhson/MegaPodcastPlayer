package md.borisveriga.megapodcastplayer.core.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import md.borisveriga.megapodcastplayer.core.datastore.UserPreferencesDataSource
import md.borisveriga.megapodcastplayer.core.model.ShowSettings

/**
 * What the user has decided about one show, as opposed to about the app.
 *
 * Its own repository rather than a corner of [UiPreferencesRepository] because the decisions here
 * are about a *thing in the library*, not about a screen: they follow the show, they are what makes
 * one podcast a serial read from the beginning and another a news feed skimmed from the top, and
 * the list of them is expected to grow.
 *
 * A show that has never been configured has no stored entry at all; every read falls back to
 * [ShowSettings.DEFAULT], so nothing has to be written when a show is added.
 */
interface ShowSettingsRepository {

    /**
     * Observes one show's settings.
     *
     * @param podcastId the show.
     * @return its settings, or the defaults while it has none.
     */
    fun observeSettings(podcastId: String): Flow<ShowSettings>

    /**
     * Changes one show's settings.
     *
     * @param podcastId the show.
     * @param transform receives the current settings and returns the new ones.
     */
    suspend fun update(podcastId: String, transform: (ShowSettings) -> ShowSettings)
}

/**
 * DataStore-backed [ShowSettingsRepository].
 *
 * The whole map is stored under one key and the flow narrows it to the show being asked about, so a
 * screen observing one show is not woken by a change to another. `distinctUntilChanged` is not
 * needed for that: `dataStore.data` already only emits on a write, and the map for an untouched
 * show returns the same default instance.
 *
 * @property userPreferences the preferences file the map lives in.
 */
@Singleton
class DefaultShowSettingsRepository @Inject constructor(
    private val userPreferences: UserPreferencesDataSource,
) : ShowSettingsRepository {

    override fun observeSettings(podcastId: String): Flow<ShowSettings> =
        userPreferences.showSettings.map { it[podcastId] ?: ShowSettings.DEFAULT }

    override suspend fun update(podcastId: String, transform: (ShowSettings) -> ShowSettings) {
        userPreferences.updateShowSettings(podcastId, transform)
    }
}
