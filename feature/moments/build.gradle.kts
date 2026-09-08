plugins {
    alias(libs.plugins.megapodcastplayer.android.feature)
}

android {
    namespace = "md.borisveriga.megapodcastplayer.feature.moments"
}

dependencies {
    // Exporting every moment writes a document through the Storage Access Framework, and
    // rememberLauncherForActivityResult is not on the feature convention's classpath.
    implementation(libs.androidx.activity.compose)
}
