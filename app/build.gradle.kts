plugins {
    alias(libs.plugins.megapodcastplayer.android.application)
    alias(libs.plugins.megapodcastplayer.android.application.compose)
    alias(libs.plugins.megapodcastplayer.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "md.borisveriga.megapodcastplayer"

    defaultConfig {
        applicationId = "md.borisveriga.megapodcastplayer"
    }
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.data)
    implementation(projects.core.media)
    implementation(projects.core.designsystem)
    // The composition root wires Coil onto the shared OkHttp client from :core:network.
    implementation(projects.core.network)
    // Shared phone <-> watch message contract; the phone side of the Data Layer lives here.
    implementation(projects.core.wearprotocol)

    implementation(projects.feature.library)
    implementation(projects.feature.downloads)
    implementation(projects.feature.search)
    implementation(projects.feature.podcast)
    implementation(projects.feature.player)
    implementation(projects.feature.settings)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    // Periodic feed refresh: WorkManager plus the Hilt worker factory that injects into it.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp.core)

    // Wearable Data Layer: receives commands from the watch, publishes playback state to it.
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
}
