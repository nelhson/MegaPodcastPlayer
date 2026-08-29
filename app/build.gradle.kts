plugins {
    alias(libs.plugins.bpodcat.android.application)
    alias(libs.plugins.bpodcat.android.application.compose)
    alias(libs.plugins.bpodcat.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "md.borisveriga.bpodcat"

    defaultConfig {
        applicationId = "md.borisveriga.bpodcat"
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
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp.core)

    // Wearable Data Layer: receives commands from the watch, publishes playback state to it.
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
