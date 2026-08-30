package md.borisveriga.bpodcat.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
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
 *     ./gradlew assembleDebug -Pbpodcat.compose.metrics=true
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
private const val COMPOSE_METRICS_FLAG = "bpodcat.compose.metrics"
