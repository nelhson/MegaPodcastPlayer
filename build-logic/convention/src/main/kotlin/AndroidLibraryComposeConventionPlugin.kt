import com.android.build.api.dsl.LibraryExtension
import md.borisveriga.megapodcastplayer.buildlogic.configureCompose
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Adds Jetpack Compose (mobile Material 3) to an Android library module.
 *
 * Registered as `megapodcastplayer.android.library.compose`.
 */
class AndroidLibraryComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        extensions.configure<LibraryExtension> {
            configureCompose(this)
        }
    }
}
