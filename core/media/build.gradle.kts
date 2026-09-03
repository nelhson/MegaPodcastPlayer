plugins {
    alias(libs.plugins.megapodcastplayer.android.library)
    alias(libs.plugins.megapodcastplayer.android.hilt)
}

android {
    namespace = "md.borisveriga.megapodcastplayer.core.media"
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    api(projects.core.datastore)
    // For the shared OkHttpClient: audio streams over the same connection pool as feeds and artwork.
    implementation(projects.core.network)
    // Resolves youtube:// sentinels to real audio URLs inside the data source chain.
    implementation(projects.core.youtube)

    api(libs.androidx.media3.common)
    api(libs.androidx.media3.exoplayer)
    api(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource.okhttp)
    // Download cache and the standalone index Media3 keeps of what is on disk.
    api(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.database)
    implementation(libs.okhttp.core)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.mockk)
    // Serves the audio client a cacheable response, to prove it stores nothing.
    testImplementation(libs.okhttp.mockwebserver)
}
