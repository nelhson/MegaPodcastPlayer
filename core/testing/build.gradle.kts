/**
 * Shared unit-test utilities.
 *
 * Every Android module gets this on its `testImplementation` classpath automatically (see
 * `AndroidLibraryConventionPlugin` / `AndroidApplicationConventionPlugin`), so nothing here should
 * be duplicated in a module's own `src/test`.
 *
 * This is an Android library rather than a JVM one because `InMemoryPreferencesDataStore`
 * implements an androidx DataStore type. Its dependencies are deliberately limited to `:core:model`
 * and external artifacts: depending on any module whose own tests consume `:core:testing` would
 * create a project dependency cycle.
 */
plugins {
    alias(libs.plugins.bpodcat.android.library)
}

android {
    namespace = "md.borisveriga.bpodcat.core.testing"
}

dependencies {
    // `api`, not `implementation`: consumers write `MainDispatcherRule()` and `assertEquals`
    // against these types directly, so they must be on the consuming compile classpath.
    api(projects.core.model)
    api(libs.junit)
    api(libs.kotlinx.coroutines.test)
    api(libs.androidx.datastore.preferences)
}
