import com.android.build.api.dsl.LibraryExtension
import md.borisveriga.bpodcat.buildlogic.configureAndroidCommon
import md.borisveriga.bpodcat.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Applies BPodcat's baseline configuration to an Android library module (`:core:*`).
 *
 * Registered as `bpodcat.android.library`.
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")

        extensions.configure<LibraryExtension> {
            configureAndroidCommon(this)

            defaultConfig.apply {
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
        }

        dependencies {
            add("androidTestImplementation", libs.findLibrary("androidx-test-junit").get())
        }
    }
}
