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
 *
 * It is a Compose module for one reason: `captureScreenshot`, the shared half of the screenshot
 * suite. That constraint above is also why the harness takes the theme as a lambda rather than
 * applying it — `:core:designsystem`, where the theme lives, is on the other side of the cycle.
 */
plugins {
    alias(libs.plugins.megapodcastplayer.android.library)
    alias(libs.plugins.megapodcastplayer.android.library.compose)
}

android {
    namespace = "md.borisveriga.megapodcastplayer.core.testing"
}

dependencies {
    // `api`, not `implementation`: consumers write `MainDispatcherRule()` and `assertEquals`
    // against these types directly, so they must be on the consuming compile classpath.
    api(projects.core.model)
    api(libs.junit)
    api(libs.kotlinx.coroutines.test)
    api(libs.androidx.datastore.preferences)

    // The screenshot harness. `api` and not `implementation`: a consuming test writes
    // `captureScreenshot { … }` around its own composables, so the Compose test types and
    // Roborazzi's capture have to be on the consuming compile classpath. The BOM comes with them
    // for the same reason — these are versioned by it, and a consumer resolving them without it
    // would be resolving a different Compose than the one it is testing.
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui.test.junit4)
    api(libs.robolectric)
    api(libs.roborazzi)
    api(libs.roborazzi.compose)
}
