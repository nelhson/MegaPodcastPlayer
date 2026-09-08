plugins {
    alias(libs.plugins.megapodcastplayer.android.feature)
}

android {
    namespace = "md.borisveriga.megapodcastplayer.feature.settings"
}

dependencies {
    // The backup section launches the Storage Access Framework picker, and
    // rememberLauncherForActivityResult is not on the feature convention's classpath.
    implementation(libs.androidx.activity.compose)
}
