package md.borisveriga.bpodcat.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.project

/**
 * Settings shared by every Android module in BPodcat (application, library, feature and Wear).
 *
 * AGP 9 notes that shape this code:
 *  - `CommonExtension` lost its lambda-accepting block methods (they now live on the concrete
 *    `ApplicationExtension`/`LibraryExtension` types), so shared configuration goes through the
 *    property getters instead.
 *  - `compileSdk`/`minSdk` are configuration blocks, not plain integer properties.
 *  - Kotlin is compiled by AGP's built-in Kotlin support, so no `kotlin-android` plugin is applied
 *    and `jvmTarget` is inherited from `compileOptions.targetCompatibility`.
 */
internal fun Project.configureAndroidCommon(extension: CommonExtension) {
    extension.apply {
        compileSdk {
            version = release(libs.int("compileSdk"))
        }
        defaultConfig.apply {
            minSdk {
                version = release(libs.int("minSdk"))
            }
        }
        compileOptions.apply {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        testOptions.unitTests.apply {
            // Robolectric needs real resources; the DAO and parser tests rely on them.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
        lint.apply {
            // A warning nobody has to act on is a warning nobody reads. Existing warnings are
            // parked in each module's `lint-baseline.xml` (regenerate with `-Dlint.baselines=...`
            // or by deleting the file), so this only gates *new* ones.
            warningsAsErrors = true
            abortOnError = true
            // Left off (the default): with it on, every consumer re-reports its dependencies'
            // findings, so one `@UnstableApi` call in :core:media lands in a dozen baselines and
            // no module's baseline is about its own code.
            checkDependencies = false

            // A baseline is opt-in per module. Pointing `baseline` at a file that does not exist
            // makes lint write one and then abort the build, so a module with nothing to park
            // carries no file at all. To create or refresh one:
            //
            //     ./gradlew :core:media:updateLintBaseline -Pbpodcat.lint.createBaseline
            //
            // Delete the file afterwards if it came out empty.
            val moduleBaseline = file("lint-baseline.xml")
            if (moduleBaseline.exists() || providers.gradleProperty(LINT_BASELINE_FLAG).isPresent) {
                baseline = moduleBaseline
            }
            // The version catalog is the single source of truth for versions; the IDE's
            // "newer version available" nag is not a build failure.
            disable += "GradleDependency"
            disable += "AndroidGradlePluginVersion"
        }
        packaging.resources.apply {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }

    // AGP always registers unit-test source directories and generates classes into them, so
    // Gradle 9's "test sources present but nothing discovered" guard fires on modules that
    // genuinely have no tests yet. Modules that do have tests still fail loudly on real failures.
    tasks.withType(org.gradle.api.tasks.testing.Test::class.java).configureEach {
        failOnNoDiscoveredTests.set(false)
    }

    dependencies {
        add("implementation", libs.findLibrary("kotlinx-coroutines-android").get())
        add("testImplementation", libs.findLibrary("junit").get())
        add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
        add("testImplementation", libs.findLibrary("turbine").get())
        add("testImplementation", libs.findLibrary("mockk").get())
    }
}

/**
 * Puts `:core:testing` on the module's unit-test compile classpath.
 *
 * Shared test utilities (`MainDispatcherRule`, `InMemoryPreferencesDataStore`, the model fixture
 * builders) live in one module rather than being copy-pasted per feature. Wiring it here means a
 * new module gets them without remembering to ask.
 *
 * `:core:testing` itself is skipped — a module cannot depend on its own output. `:core:model` is
 * skipped because `:core:testing` depends on *it*, and the reverse edge would be a cycle; it is a
 * JVM module anyway, so this function never runs for it.
 */
internal fun Project.addSharedTestingModule() {
    if (path in SELF_EXCLUDED_FROM_SHARED_TESTING) return
    dependencies {
        add("testImplementation", project(":core:testing"))
    }
}

/** Gradle property that opts a module into writing a lint baseline; see `configureAndroidCommon`. */
private const val LINT_BASELINE_FLAG = "bpodcat.lint.createBaseline"

/** Modules that must not depend on `:core:testing` because `:core:testing` depends on them. */
private val SELF_EXCLUDED_FROM_SHARED_TESTING = setOf(":core:testing", ":core:model")

/**
 * Signing shared by `:app` and `:wear`.
 *
 * The Wearable Data Layer only delivers messages between apps that share an application ID **and**
 * a signing certificate, so both APKs must be signed with the same key. If `keystore.properties`
 * exists at the repository root it is used for release builds.
 *
 * ## Why a missing keystore fails the build
 *
 * This used to fall back to the debug key, which was convenient and unsafe. The debug key is a
 * world-known Android SDK artifact, so a release APK signed with it is trivially re-signable by
 * anyone — and, because the Data Layer routes purely on *package name plus certificate*, any app
 * anyone builds with the debug key and the `md.borisveriga.bpodcat` application ID could send
 * `WearCommand`s to a real installation and read back its `NowPlayingSnapshot`, which carries
 * episode titles, show titles and the whole queue.
 *
 * So the fallback is now opt-in and loud: pass `-PallowDebugSigningForRelease=true` to get the old
 * behaviour for a local sideload. Without it, a release build with no keystore fails at
 * *execution* time with an actionable message rather than at configuration time, so that
 * `./gradlew build`, `lint` and IDE sync keep working on a machine that has no signing material —
 * which is every CI machine and every fresh clone.
 *
 * @see ALLOW_DEBUG_SIGNING_FLAG
 */
internal fun Project.configureSharedSigning(extension: ApplicationExtension) {
    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val keystoreProperties = java.util.Properties().apply {
        if (keystorePropertiesFile.exists()) {
            keystorePropertiesFile.inputStream().use { load(it) }
        }
    }
    val hasReleaseKeystore = keystoreProperties.getProperty("storeFile") != null
    val allowDebugSigning = providers.gradleProperty(ALLOW_DEBUG_SIGNING_FLAG)
        .map { it.toBoolean() }
        .getOrElse(false)

    extension.apply {
        if (hasReleaseKeystore) {
            signingConfigs.create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
        buildTypes.named("release") {
            signingConfig = when {
                hasReleaseKeystore -> signingConfigs.getByName("release")
                allowDebugSigning -> signingConfigs.getByName("debug")
                // Left null on purpose: AGP then produces an *unsigned* release APK rather than a
                // debug-signed one, so even if the guard below were somehow bypassed the output
                // could not be installed as if it were genuine.
                else -> null
            }
        }
    }

    if (!hasReleaseKeystore && !allowDebugSigning) {
        failReleasePackagingWithoutAKeystore()
    }
}

/**
 * Makes every release packaging task in this project fail with an explanation.
 *
 * Matched by name (`package…Release`, `assemble…Release`, `bundle…Release`) rather than by task
 * type, because AGP's packaging task classes are internal. The check runs in a `doFirst` so that
 * merely *configuring* these tasks — which `./gradlew tasks`, IDE sync and the configuration cache
 * all do — stays free of it.
 */
private fun Project.failReleasePackagingWithoutAKeystore() {
    tasks.configureEach {
        val isReleasePackaging = RELEASE_PACKAGING_PREFIXES.any { name.startsWith(it) } &&
            name.endsWith("Release")
        if (!isReleasePackaging) return@configureEach

        doFirst {
            error(
                """
                No signing key for the release build.

                Create keystore.properties at the repository root with storeFile, storePassword,
                keyAlias and keyPassword (it is git-ignored, along with *.jks and *.keystore).

                To sideload a debug-signed release build instead, pass:
                    -P$ALLOW_DEBUG_SIGNING_FLAG=true

                Never do that for anything you distribute: the debug key is public, and the watch
                pairing trusts any app that shares this application ID and certificate.
                """.trimIndent(),
            )
        }
    }
}

/** Task name prefixes that produce an installable or distributable release artifact. */
private val RELEASE_PACKAGING_PREFIXES = listOf("package", "assemble", "bundle")

/**
 * Gradle property that re-enables signing release builds with the debug key.
 *
 * For local sideloading only; see [configureSharedSigning] for why it is not the default.
 */
private const val ALLOW_DEBUG_SIGNING_FLAG = "allowDebugSigningForRelease"
