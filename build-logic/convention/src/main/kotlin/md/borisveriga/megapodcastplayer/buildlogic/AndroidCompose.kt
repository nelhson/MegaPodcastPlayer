package md.borisveriga.megapodcastplayer.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

/**
 * Enables Jetpack Compose for a **mobile** module and wires the standard Compose dependency set.
 *
 * Wear modules must not use this: `androidx.wear.compose:compose-material3` replaces (rather than
 * extends) the mobile Material 3 library, and mixing the two produces broken theming.
 */
internal fun Project.configureCompose(extension: CommonExtension) {
    pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
    configureComposeMetrics()
    configureScreenshotTests()

    extension.buildFeatures.compose = true

    dependencies {
        val bom = libs.findLibrary("androidx-compose-bom").get()
        add("implementation", platform(bom))
        add("androidTestImplementation", platform(bom))
        add("testImplementation", platform(bom))

        add("implementation", libs.findLibrary("androidx-compose-foundation").get())
        add("implementation", libs.findLibrary("androidx-compose-material3").get())
        add("implementation", libs.findLibrary("androidx-compose-material-icons-extended").get())
        add("implementation", libs.findLibrary("androidx-compose-ui").get())
        add("implementation", libs.findLibrary("androidx-compose-ui-graphics").get())
        add("implementation", libs.findLibrary("androidx-compose-ui-tooling-preview").get())
        add("implementation", libs.findLibrary("androidx-lifecycle-runtime-compose").get())
        add("implementation", libs.findLibrary("androidx-lifecycle-viewmodel-compose").get())

        add("debugImplementation", libs.findLibrary("androidx-compose-ui-tooling").get())
        add("debugImplementation", libs.findLibrary("androidx-compose-ui-test-manifest").get())
        add("androidTestImplementation", libs.findLibrary("androidx-compose-ui-test-junit4").get())

        // Compose UI tests run on the JVM under Robolectric, not on a device: the repo has no
        // `androidTest` sources by design (see docs/REFACTORING_PLAN.md T-2), so without these
        // four the phone modules have no way to assert on a composable at all. `:wear` wired the
        // same set up by hand, which is why the watch had a screen test and the phone did not.
        add("testImplementation", libs.findLibrary("robolectric").get())
        add("testImplementation", libs.findLibrary("androidx-test-junit").get())
        add("testImplementation", libs.findLibrary("androidx-compose-ui-test-junit4").get())

        // Screenshot tests. Robolectric already renders the composable; Roborazzi is the part that
        // writes the PNG and, in verify mode, holds it against the golden committed beside the
        // test. Wired for every Compose module rather than only the ones that have goldens today,
        // so adding a screenshot test to a screen is writing the test and nothing else.
        add("testImplementation", libs.findLibrary("roborazzi").get())
        add("testImplementation", libs.findLibrary("roborazzi-compose").get())
    }
}

/**
 * Puts the screenshot tests in verify mode, unless asked to record.
 *
 * Verify is the default because a golden nobody compares against is a file, not a test: an
 * ordinary `testDebugUnitTest` — a developer's and CI's — has to fail when a component's rendering
 * changes. Recording is the deliberate act, and it is a whole-build one:
 *
 *     ./gradlew testDebugUnitTest -Pmegapodcastplayer.screenshots.record
 *
 * Then read the image diff before committing it, exactly as `docs/DEPENDENCY_VERIFICATION.md` asks
 * a regenerated checksum to be read. A re-recorded golden is a design change being accepted; if it
 * was not meant, the diff is the only place that says so.
 *
 * Two things here are about Gradle rather than about screenshots, and both were found by the suite
 * lying once:
 *
 *  - **The goldens are declared as inputs.** They are read by the test and written beside it, so
 *    without this Gradle cannot see that a golden edited, deleted or restored by hand is a reason
 *    to verify again — and answers an unchanged task with a cached pass.
 *  - **Recording never comes from the cache.** A re-record with inputs identical to the last one is
 *    exactly the case Gradle is entitled to skip, and skipping it leaves the images untouched while
 *    reporting success. Recording is an act, not a result.
 */
private fun Project.configureScreenshotTests() {
    val recording = providers.gradleProperty(SCREENSHOT_RECORD_FLAG).isPresent
    val goldens = layout.projectDirectory.dir("src/test/screenshots").asFileTree
    tasks.withType(Test::class.java).configureEach {
        systemProperty("roborazzi.test.record", recording)
        systemProperty("roborazzi.test.verify", !recording)
        inputs.files(goldens)
            .withPropertyName("screenshotGoldens")
            .withPathSensitivity(PathSensitivity.RELATIVE)
            .optional()
        if (recording) outputs.upToDateWhen { false }
    }
}

/**
 * Enables Compose for a **Wear OS** module, pulling in Wear Compose Material 3 instead of the
 * mobile Material 3 artifacts.
 */
internal fun Project.configureWearCompose(extension: CommonExtension) {
    pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
    configureComposeMetrics()

    extension.buildFeatures.compose = true

    dependencies {
        val bom = libs.findLibrary("androidx-compose-bom").get()
        add("implementation", platform(bom))
        add("androidTestImplementation", platform(bom))

        add("implementation", libs.findLibrary("androidx-compose-ui").get())
        add("implementation", libs.findLibrary("androidx-compose-ui-graphics").get())
        add("implementation", libs.findLibrary("androidx-compose-ui-tooling-preview").get())
        add("implementation", libs.findLibrary("androidx-wear-compose-material3").get())
        add("implementation", libs.findLibrary("androidx-wear-compose-foundation").get())
        add("implementation", libs.findLibrary("androidx-wear-compose-navigation").get())
        add("implementation", libs.findLibrary("androidx-wear-tooling-preview").get())
        add("implementation", libs.findLibrary("androidx-lifecycle-runtime-compose").get())
        add("implementation", libs.findLibrary("androidx-lifecycle-viewmodel-compose").get())

        add("debugImplementation", libs.findLibrary("androidx-compose-ui-tooling").get())
        add("debugImplementation", libs.findLibrary("androidx-wear-compose-ui-tooling").get())
        add("debugImplementation", libs.findLibrary("androidx-compose-ui-test-manifest").get())
        add("androidTestImplementation", libs.findLibrary("androidx-compose-ui-test-junit4").get())
    }
}

/**
 * Opt-in Compose compiler metrics and stability reports.
 *
 * Off by default: the reports cost a full non-incremental Kotlin compile and are only useful when
 * someone is actually reading them. Turn them on for one run with
 *
 *     ./gradlew assembleDebug -Pmegapodcastplayer.compose.metrics=true
 *
 * and read the `-composables.txt` file under a module’s `build/compose-metrics` directory: it
 * lists every composable with its `restartable` and `skippable` verdict. The `-classes.txt` file
 * beside it says which types the compiler inferred as stable. That is the measurement Q-4 in
 * `docs/REFACTORING_PLAN.md` asks for before anyone reaches for `kotlinx-collections-immutable`.
 */
private fun Project.configureComposeMetrics() {
    if (!providers.gradleProperty(COMPOSE_METRICS_FLAG).isPresent) return

    extensions.configure<ComposeCompilerGradlePluginExtension> {
        val destination = layout.buildDirectory.dir("compose-metrics")
        metricsDestination.set(destination)
        reportsDestination.set(destination)
    }
}

/** Gradle property that turns on the Compose compiler reports; see [configureComposeMetrics]. */
private const val COMPOSE_METRICS_FLAG = "megapodcastplayer.compose.metrics"

/** Gradle property that re-records the screenshot goldens; see [configureScreenshotTests]. */
private const val SCREENSHOT_RECORD_FLAG = "megapodcastplayer.screenshots.record"
