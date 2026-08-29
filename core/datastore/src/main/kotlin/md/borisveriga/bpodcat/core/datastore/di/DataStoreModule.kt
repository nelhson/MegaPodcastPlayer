package md.borisveriga.bpodcat.core.datastore.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import md.borisveriga.bpodcat.core.common.di.ApplicationScope

/** Provides the app's single preferences [DataStore]. */
@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    /** File name of the preferences store, under the app's `datastore` directory. */
    private const val PREFERENCES_NAME = "bpodcat_preferences"

    /**
     * Builds the preferences store on the application scope so that a write started by a screen
     * that is being torn down still completes.
     */
    @Provides
    @Singleton
    fun providesPreferencesDataStore(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) {
        context.preferencesDataStoreFile(PREFERENCES_NAME)
    }
}
