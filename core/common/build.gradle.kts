plugins {
    alias(libs.plugins.megapodcastplayer.android.library)
    alias(libs.plugins.megapodcastplayer.android.hilt)
}

android {
    namespace = "md.borisveriga.megapodcastplayer.core.common"
}

dependencies {
    api(projects.core.model)

    // The formatters say string resources now, so their tests need real ones; see FormattersTest.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
}
