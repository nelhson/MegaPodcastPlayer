plugins {
    alias(libs.plugins.megapodcastplayer.android.library)
    alias(libs.plugins.megapodcastplayer.android.hilt)
}

android {
    namespace = "md.borisveriga.megapodcastplayer.core.data"
}

dependencies {
    api(projects.core.model)
    api(projects.core.database)
    api(projects.core.datastore)
    api(projects.core.media)
    implementation(projects.core.common)
    implementation(projects.core.network)
    // Playlists are read with the extractor rather than fetched as a feed, so the repository picks
    // between the two. The dependency has to be here because :core:network cannot depend on
    // :core:youtube — :core:youtube already depends on it.
    implementation(projects.core.youtube)

    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
}
