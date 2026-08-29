import md.borisveriga.bpodcat.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.project

/**
 * Baseline for a `:feature:*` module: an Android library with Compose, Hilt, navigation and the
 * shared design system already wired up.
 *
 * Registered as `bpodcat.android.feature`.
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("bpodcat.android.library")
        pluginManager.apply("bpodcat.android.library.compose")
        pluginManager.apply("bpodcat.android.hilt")

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
