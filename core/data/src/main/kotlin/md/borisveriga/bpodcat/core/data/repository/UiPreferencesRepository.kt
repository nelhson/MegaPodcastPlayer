package md.borisveriga.bpodcat.core.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import md.borisveriga.bpodcat.core.datastore.UserPreferencesDataSource
import md.borisveriga.bpodcat.core.model.LibraryLayout

/**
 * The handful of choices a screen makes about how it draws itself.
 *
 * Separate from [PlaybackRepository] and [DownloadRepository] because it is a different kind of
 * thing: nothing here changes what the app *does*, only what it looks like, and nothing here is
 * worth a row in the database. It exists as a repository at all — rather than the view model
 * reaching into `:core:datastore` — because feature modules deliberately do not depend on the
 * storage layer.
 */
interface UiPreferencesRepository {

    /** How the library screen draws its shows. */
    fun observeLibraryLayout(): Flow<LibraryLayout>

    /**
     * Records the library's layout so it survives process death.
     *
     * @param layout the layout to use from now on.
     */
    suspend fun setLibraryLayout(layout: LibraryLayout)
}

/**
 * DataStore-backed [UiPreferencesRepository].
 *
 * A thin pass-through by design: there is no merging, no caching and no derived state to add, and
 * a repository that invents work here would only be in the way.
 *
 * @property userPreferences the preferences file every scalar setting lives in.
 */
@Singleton
class DefaultUiPreferencesRepository @Inject constructor(
    private val userPreferences: UserPreferencesDataSource,
) : UiPreferencesRepository {

    override fun observeLibraryLayout(): Flow<LibraryLayout> = userPreferences.libraryLayout

    override suspend fun setLibraryLayout(layout: LibraryLayout) {
        userPreferences.setLibraryLayout(layout)
    }
}
