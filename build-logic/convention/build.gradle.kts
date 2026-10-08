/**
 * Hosts every MegaPodcastPlayer convention plugin.
 *
 * The Android/Kotlin Gradle plugins are `compileOnly` here: they are only needed to compile against
 * their APIs. At runtime they are supplied by the consuming build, which declares them in its root
 * `plugins { ... apply false }` block.
 */
plugins {
    `kotlin-dsl`
}

group = "md.borisveriga.megapodcastplayer.buildlogic"

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.detekt.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "megapodcastplayer.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidApplicationCompose") {
            id = "megapodcastplayer.android.application.compose"
            implementationClass = "AndroidApplicationComposeConventionPlugin"
        }
        register("androidApplicationWear") {
            id = "megapodcastplayer.android.application.wear"
            implementationClass = "AndroidApplicationWearConventionPlugin"
        }
        register("androidLibrary") {
            id = "megapodcastplayer.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidLibraryCompose") {
            id = "megapodcastplayer.android.library.compose"
            implementationClass = "AndroidLibraryComposeConventionPlugin"
        }
        register("androidFeature") {
            id = "megapodcastplayer.android.feature"
            implementationClass = "AndroidFeatureConventionPlugin"
        }
        register("androidHilt") {
            id = "megapodcastplayer.android.hilt"
            implementationClass = "AndroidHiltConventionPlugin"
        }
        register("androidRoom") {
            id = "megapodcastplayer.android.room"
            implementationClass = "AndroidRoomConventionPlugin"
        }
        register("jvmLibrary") {
            id = "megapodcastplayer.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
        register("detekt") {
            id = "megapodcastplayer.detekt"
            implementationClass = "DetektConventionPlugin"
        }
    }
}
