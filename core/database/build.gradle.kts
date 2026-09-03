plugins {
    alias(libs.plugins.megapodcastplayer.android.library)
    alias(libs.plugins.megapodcastplayer.android.hilt)
    alias(libs.plugins.megapodcastplayer.android.room)
}

android {
    namespace = "md.borisveriga.megapodcastplayer.core.database"
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
}
