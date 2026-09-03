import com.google.devtools.ksp.gradle.KspExtension
import md.borisveriga.megapodcastplayer.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Wires Room + KSP into a module and exports the generated schemas to `schemas/` so that
 * migrations can be diffed and tested.
 *
 * Registered as `megapodcastplayer.android.room`.
 */
class AndroidRoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.google.devtools.ksp")

        extensions.configure<KspExtension> {
            arg("room.generateKotlin", "true")
            arg("room.schemaLocation", "${projectDir}/schemas")
        }

        dependencies {
            add("implementation", libs.findLibrary("androidx-room-runtime").get())
            add("implementation", libs.findLibrary("androidx-room-ktx").get())
            add("ksp", libs.findLibrary("androidx-room-compiler").get())
            add("testImplementation", libs.findLibrary("androidx-room-testing").get())
        }
    }
}
