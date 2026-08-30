import com.android.build.api.dsl.ApplicationExtension
import md.borisveriga.bpodcat.buildlogic.addSharedTestingModule
import md.borisveriga.bpodcat.buildlogic.configureAndroidCommon
import md.borisveriga.bpodcat.buildlogic.configureSharedSigning
import md.borisveriga.bpodcat.buildlogic.int
import md.borisveriga.bpodcat.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Applies BPodcat's baseline configuration to an Android application module (`:app`).
 *
 * Registered as `bpodcat.android.application`.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("bpodcat.detekt")
        addSharedTestingModule()

        extensions.configure<ApplicationExtension> {
            configureAndroidCommon(this)
            configureSharedSigning(this)

            defaultConfig.apply {
                targetSdk {
                    version = release(libs.int("targetSdk"))
                }
                versionCode = 1
                versionName = "1.0"
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
