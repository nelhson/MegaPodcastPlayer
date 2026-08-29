/**
 * Hosts every BPodcat convention plugin.
 *
 * The Android/Kotlin Gradle plugins are `compileOnly` here: they are only needed to compile against
 * their APIs. At runtime they are supplied by the consuming build, which declares them in its root
 * `plugins { ... apply false }` block.
 */
plugins {
    `kotlin-dsl`
}

group = "md.borisveriga.bpodcat.buildlogic"

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "bpodcat.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidApplicationCompose") {
            id = "bpodcat.android.application.compose"
            implementationClass = "AndroidApplicationComposeConventionPlugin"
        }
        register("androidApplicationWear") {
            id = "bpodcat.android.application.wear"
            implementationClass = "AndroidApplicationWearConventionPlugin"
        }
        register("androidLibrary") {
            id = "bpodcat.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidLibraryCompose") {
            id = "bpodcat.android.library.compose"
            implementationClass = "AndroidLibraryComposeConventionPlugin"
        }
        register("androidFeature") {
            id = "bpodcat.android.feature"
            implementationClass = "AndroidFeatureConventionPlugin"
        }
        register("androidHilt") {
            id = "bpodcat.android.hilt"
            implementationClass = "AndroidHiltConventionPlugin"
        }
        register("androidRoom") {
            id = "bpodcat.android.room"
            implementationClass = "AndroidRoomConventionPlugin"
        }
        register("jvmLibrary") {
            id = "bpodcat.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
    }
}
