plugins {
    alias(libs.plugins.bpodcat.android.application.wear)
    alias(libs.plugins.bpodcat.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    // The namespace differs from the application ID so the two APKs' R classes don't collide in the
    // IDE, but the application ID and signing key MUST match `:app` — the Wearable Data Layer only
    // routes messages between apps with an identical package name and certificate.
    namespace = "md.borisveriga.bpodcat.wear"

    defaultConfig {
        applicationId = "md.borisveriga.bpodcat"
    }
}

dependencies {
    // Only the pure-Kotlin contract is shared: no Room, no Retrofit, no phone-side code.
    implementation(projects.core.wearprotocol)
    // For `suspendRunCatching` and the dispatcher qualifiers. Brings `:core:model` with it, which
    // is plain Kotlin data classes; still no storage, no networking and no phone-side code.
    implementation(projects.core.common)

    implementation(libs.androidx.core.ktx)
    // The transport controls reuse the same numbered skip glyphs as the phone's player, so the two
    // agree on what "skip ahead" looks like. R8 strips every icon the watch does not draw.
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)

    // The screen is rendered in JVM tests through Robolectric rather than on a device: the layout
    // bugs worth catching here are about what a round screen clips, and Robolectric renders that
    // without an emulator in the loop.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(platform(libs.androidx.compose.bom))
}
