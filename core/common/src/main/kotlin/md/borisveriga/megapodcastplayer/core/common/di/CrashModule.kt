package md.borisveriga.megapodcastplayer.core.common.di

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import md.borisveriga.megapodcastplayer.core.common.crash.CrashReporter
import md.borisveriga.megapodcastplayer.core.common.crash.FirebaseCrashReporter
import md.borisveriga.megapodcastplayer.core.common.crash.NoOpCrashReporter

/**
 * Supplies the one [CrashReporter] every module injects.
 *
 * `@Provides` rather than `@Binds`, because which implementation is right is a runtime question:
 * Crashlytics needs a [FirebaseApp], and there is one only if the APK carries the resources that
 * `com.google.gms.google-services` generates from `google-services.json`. Builds without that file
 * are supported on purpose (see `AndroidCrashlyticsConventionPlugin`), and asking
 * [FirebaseCrashlytics.getInstance] for a reporter in one of them throws.
 *
 * This lives in `:core:common` rather than in `:app` and `:wear` so that the phone and the watch
 * report the same way without the wiring existing twice, and so that Firebase stays on exactly one
 * module's compile classpath.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object CrashModule {

    /**
     * @param context the application context, used to read whether this APK is debuggable.
     * @return a [FirebaseCrashReporter] when Firebase initialised, [NoOpCrashReporter] otherwise.
     */
    @Provides
    @Singleton
    fun provideCrashReporter(@ApplicationContext context: Context): CrashReporter =
        // FirebaseApp is initialised by a content provider before any of this runs, so an empty
        // list here means the configuration was absent rather than that it is not ready yet.
        if (FirebaseApp.getApps(context).isEmpty()) {
            NoOpCrashReporter
        } else {
            FirebaseCrashReporter(FirebaseCrashlytics.getInstance(), context)
        }
}
