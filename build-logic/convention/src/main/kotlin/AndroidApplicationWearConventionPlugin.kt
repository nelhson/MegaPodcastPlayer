import com.android.build.api.dsl.ApplicationExtension
import md.borisveriga.bpodcat.buildlogic.configureAndroidCommon
import md.borisveriga.bpodcat.buildlogic.configureSharedSigning
import md.borisveriga.bpodcat.buildlogic.configureWearCompose
import md.borisveriga.bpodcat.buildlogic.int
import md.borisveriga.bpodcat.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Baseline for the Wear OS application module (`:wear`).
 *
 * The Wear APK deliberately shares [ApplicationExtension.namespace]'s application ID and the signing
 * key with `:app` — the Wearable Data Layer only routes messages between apps whose package name
 * *and* signing certificate match on both devices.
 *
 * Registered as `bpodcat.android.application.wear`.
 */
class AndroidApplicationWearConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")

        extensions.configure<ApplicationExtension> {
            configureAndroidCommon(this)
            configureSharedSigning(this)
            configureWearCompose(this)

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
                    // The watch APK is small; keep R8 on to strip unused Compose/Play Services code.
                    isMinifyEnabled = true
                    isShrinkResources = true
                    proguardFiles(
                        getDefaultProguardFile("proguard-android-optimize.txt"),
                        "proguard-rules.pro",
                    )
                }
            }
        }

        dependencies {
            add("implementation", libs.findLibrary("androidx-wear").get())
            add("implementation", libs.findLibrary("androidx-wear-ongoing").get())
            add("implementation", libs.findLibrary("play-services-wearable").get())
            add("implementation", libs.findLibrary("kotlinx-coroutines-play-services").get())
        }
    }
}
