import com.android.build.api.dsl.LibraryExtension
import md.borisveriga.megapodcastplayer.buildlogic.addSharedTestingModule
import md.borisveriga.megapodcastplayer.buildlogic.configureAndroidCommon
import md.borisveriga.megapodcastplayer.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Applies MegaPodcastPlayer's baseline configuration to an Android library module (`:core:*`).
 *
 * Registered as `megapodcastplayer.android.library`.
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("megapodcastplayer.detekt")

        extensions.configure<LibraryExtension> {
            configureAndroidCommon(this)

            defaultConfig.apply {
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
        }

        addSharedTestingModule()

        dependencies {
            add("androidTestImplementation", libs.findLibrary("androidx-test-junit").get())
        }
    }
}
