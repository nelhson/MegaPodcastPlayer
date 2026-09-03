import md.borisveriga.megapodcastplayer.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.project

/**
 * Baseline for a `:feature:*` module: an Android library with Compose, Hilt, navigation and the
 * shared design system already wired up.
 *
 * Registered as `megapodcastplayer.android.feature`.
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("megapodcastplayer.android.library")
        pluginManager.apply("megapodcastplayer.android.library.compose")
        pluginManager.apply("megapodcastplayer.android.hilt")

        dependencies {
            add("implementation", project(":core:designsystem"))
            add("implementation", project(":core:model"))
            add("implementation", project(":core:data"))
            add("implementation", project(":core:common"))

            add("implementation", libs.findLibrary("androidx-hilt-navigation-compose").get())
            add("implementation", libs.findLibrary("androidx-navigation-compose").get())
            add("implementation", libs.findLibrary("androidx-lifecycle-runtime-ktx").get())
            add("implementation", libs.findLibrary("coil-compose").get())
        }
    }
}
