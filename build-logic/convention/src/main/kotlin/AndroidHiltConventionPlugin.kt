import md.borisveriga.megapodcastplayer.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Wires Hilt (via KSP — kapt is incompatible with AGP 9's built-in Kotlin) into a module.
 *
 * Registered as `megapodcastplayer.android.hilt`.
 */
class AndroidHiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.google.devtools.ksp")
        pluginManager.apply("com.google.dagger.hilt.android")

        dependencies {
            add("implementation", libs.findLibrary("hilt-android").get())
            add("ksp", libs.findLibrary("hilt-compiler").get())
            add("kspTest", libs.findLibrary("hilt-compiler").get())
        }
    }
}
