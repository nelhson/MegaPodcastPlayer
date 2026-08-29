package md.borisveriga.bpodcat.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

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
 * Signing shared by `:app` and `:wear`.
 *
 * The Wearable Data Layer only delivers messages between apps that share an application ID **and**
 * a signing certificate, so both APKs must be signed with the same key. If `keystore.properties`
 * exists at the repository root it is used for release builds; otherwise release builds fall back to
 * the debug key so that a sideload-only workflow keeps working out of the box.
 */
internal fun Project.configureSharedSigning(extension: ApplicationExtension) {
    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val keystoreProperties = java.util.Properties().apply {
        if (keystorePropertiesFile.exists()) {
            keystorePropertiesFile.inputStream().use { load(it) }
        }
    }
    val hasReleaseKeystore = keystoreProperties.getProperty("storeFile") != null

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
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
}
