import com.android.build.api.dsl.ApplicationExtension
import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension
import md.borisveriga.megapodcastplayer.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Turns on Firebase Crashlytics for an Android application module (`:app`, `:wear`).
 *
 * Registered as `megapodcastplayer.android.crashlytics`. Apply it *after* the application
 * convention plugin — it configures build types that plugin has already created.
 *
 * ## Why the whole thing is conditional on a file
 *
 * `com.google.gms.google-services` fails the build if it cannot find `google-services.json` in the
 * module directory, and it looks nowhere else. That file is the Firebase project's identity, so a
 * fork, a fresh clone that has not been given one, or a CI job on a repository without it would
 * otherwise be unable to compile the app at all — for a feature that only reports crashes.
 *
 * So the plugins are applied only when the file is there. The cost of that leniency is a build that
 * could silently ship with no crash reporting, which is precisely the situation crash reporting
 * exists to rule out, so a *release* build without the file fails at execution time with an
 * explanation. Debug builds, `detekt`, `lint` and IDE sync keep working either way. This is the
 * same shape as `configureSharedSigning`, and for the same reason.
 *
 * ## What is deliberately not here
 *
 * Collection is left **on for debug builds**. The `install_on_devices` skill sideloads debug APKs
 * onto real hardware, which is where this app actually runs; switching reporting off for debug
 * would leave the only builds anyone uses unreported. `FirebaseCrashReporter` tags every report
 * with the build type instead, so the dashboard can tell them apart.
 */
class AndroidCrashlyticsConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        if (!googleServicesConfig().exists()) {
            failReleasePackagingWithoutFirebaseConfig()
            return@with
        }

        pluginManager.apply("com.google.gms.google-services")
        pluginManager.apply("com.google.firebase.crashlytics")

        // Read before the configuration block below so the provider is not queried from inside it.
        val uploadMappings = !isDebugSignedRelease()

        // The Crashlytics plugin registers `firebaseCrashlytics` as a DSL extension on each *build
        // type* rather than on the project, so it has to be reached through the build type that
        // owns it. Only `release` is minified, so only `release` has a mapping file at all.
        extensions.configure<ApplicationExtension> {
            buildTypes.named("release") {
                val crashlytics = (this as ExtensionAware)
                    .extensions
                    .getByType(CrashlyticsExtension::class.java)
                // Uploading the R8 mapping file is what makes a release stack trace readable, and
                // it needs the network at build time. CI's release smoke build is debug-signed,
                // never installed and immediately discarded, so its mapping file describes nothing
                // anyone will ever look up — and making every CI run depend on a Firebase upload
                // succeeding would trade a real signal for an unrelated flake. The flag that
                // already marks a build as "not for anyone" turns the upload off.
                crashlytics.mappingFileUploadEnabled = uploadMappings
            }
        }

        dependencies {
            // The SDK itself lives in :core:common, which is where `CrashReporter` is implemented;
            // an application module only needs the resources google-services generates. The BOM is
            // still added here so that `:app`/`:wear` resolve the same SDK version :core:common
            // compiled against rather than whatever the dependency graph settles on.
            add("implementation", dependencies.platform(libs.findLibrary("firebase-bom").get()))
        }
    }
}

/**
 * The Firebase configuration this module would use.
 *
 * `com.google.gms.google-services` searches variant source directories before the module root; only
 * the module root is used here, because the phone and the watch share one application ID and
 * therefore one Firebase app, and no build type wants a different project.
 */
private fun Project.googleServicesConfig() = file("google-services.json")

/**
 * Whether this build was told to sign a release with the debug key.
 *
 * Set by CI's release smoke build and by a local sideload of a release variant. Both produce an
 * artifact that must never reach anyone, so neither has a reason to upload a mapping file.
 */
private fun Project.isDebugSignedRelease() =
    providers.gradleProperty("allowDebugSigningForRelease").map { it.toBoolean() }.getOrElse(false)

/**
 * Makes every release packaging task fail, explaining that Firebase is not configured.
 *
 * Matched by task name for the same reason [configureSharedSigning][AndroidApplicationConventionPlugin]
 * does: AGP's packaging task classes are internal. The check is in a `doFirst` so that configuring
 * these tasks — which `./gradlew tasks`, IDE sync and the configuration cache all do — stays free.
 */
private fun Project.failReleasePackagingWithoutFirebaseConfig() {
    tasks.configureEach {
        val isReleasePackaging = RELEASE_PACKAGING_PREFIXES.any { name.startsWith(it) } &&
            name.endsWith("Release")
        if (!isReleasePackaging) return@configureEach

        doFirst {
            error(
                """
                No Firebase configuration for the release build.

                ${project.path} has no google-services.json, so this APK would ship with no crash
                reporting and nothing would say so afterwards.

                Download it from the Firebase console for the Android app
                md.borisveriga.megapodcastplayer and save it at:
                    ${project.projectDir.name}/google-services.json

                See docs/CRASH_REPORTING.md. Debug builds do not need it.
                """.trimIndent(),
            )
        }
    }
}

/** Task name prefixes that produce an installable or distributable release artifact. */
private val RELEASE_PACKAGING_PREFIXES = listOf("package", "assemble", "bundle")
