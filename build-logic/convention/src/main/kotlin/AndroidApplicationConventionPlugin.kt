import com.android.build.api.dsl.ApplicationExtension
import md.borisveriga.megapodcastplayer.buildlogic.addSharedTestingModule
import md.borisveriga.megapodcastplayer.buildlogic.configureAndroidCommon
import md.borisveriga.megapodcastplayer.buildlogic.configureSharedSigning
import md.borisveriga.megapodcastplayer.buildlogic.int
import md.borisveriga.megapodcastplayer.buildlogic.libs
import md.borisveriga.megapodcastplayer.buildlogic.string
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Applies MegaPodcastPlayer's baseline configuration to an Android application module (`:app`).
 *
 * Registered as `megapodcastplayer.android.application`.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("megapodcastplayer.detekt")
        addSharedTestingModule()

        extensions.configure<ApplicationExtension> {
            configureAndroidCommon(this)
            configureSharedSigning(this)

            defaultConfig.apply {
                targetSdk {
                    version = release(libs.int("targetSdk"))
                }
                versionCode = libs.int("versionCode")
                versionName = libs.string("versionName")
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }

            buildTypes {
                named("release") {
                    isMinifyEnabled = true
                    isShrinkResources = true
                    proguardFiles(
                        getDefaultProguardFile("proguard-android-optimize.txt"),
                        "proguard-rules.pro",
                    )
                }
                // No `applicationIdSuffix` on debug, deliberately: the Wearable Data Layer
                // routes messages on package name plus signing certificate, so a suffixed
                // debug phone build could never talk to the watch build sitting beside it.
            }
        }
    }
}
