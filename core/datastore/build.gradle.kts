plugins {
    alias(libs.plugins.megapodcastplayer.android.library)
    alias(libs.plugins.megapodcastplayer.android.hilt)
}

android {
    namespace = "md.borisveriga.megapodcastplayer.core.datastore"
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    api(libs.androidx.datastore.preferences)
}
