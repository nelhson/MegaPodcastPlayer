import com.android.build.api.dsl.ApplicationExtension
import md.borisveriga.megapodcastplayer.buildlogic.configureCompose
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Adds Jetpack Compose (mobile Material 3) to an Android application module.
 *
 * Registered as `megapodcastplayer.android.application.compose`.
 */
class AndroidApplicationComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        extensions.configure<ApplicationExtension> {
            configureCompose(this)
        }
    }
}
