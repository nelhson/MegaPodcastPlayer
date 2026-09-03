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

    // The watch-face surfaces: a tile beside the face, and a complication on it. Both render the
    // same data item the app reads, so neither needs the phone to be awake to draw itself.
    implementation(libs.androidx.wear.tiles)
    implementation(libs.androidx.wear.protolayout)
    implementation(libs.androidx.wear.protolayout.expression)
    implementation(libs.androidx.wear.watchface.complications.data.source)
    implementation(libs.androidx.concurrent.futures)
    // `ListenableFuture.await()`, for connecting the media controller to the playback service.
    implementation(libs.androidx.concurrent.futures.ktx)

    // Playback of episodes carried over to the watch. The same player the phone uses, minus the
    // download machinery: the watch receives finished files rather than fetching anything.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)
    // Unused by the watch: it draws no remote imagery at all — the now-playing header is a generated
    // waveform in the show's own colour. Kept because dropping it shifts the resolved Kotlin stdlib
    // version and would require regenerating gradle/verification-metadata.xml; that cleanup belongs
    // in its own commit, where the diff to that file can be reviewed on its own terms.
    implementation(libs.coil.compose)

    // The screen is rendered in JVM tests through Robolectric rather than on a device: the layout
    // bugs worth catching here are about what a round screen clips, and Robolectric renders that
    // without an emulator in the loop.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(platform(libs.androidx.compose.bom))
}
