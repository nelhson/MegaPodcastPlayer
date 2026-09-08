plugins {
    alias(libs.plugins.megapodcastplayer.android.feature)
}

android {
    namespace = "md.borisveriga.megapodcastplayer.feature.listen"
}

dependencies {
    // The episode sheet is the show page's, and it is the same sheet: an episode opened from a
    // shelf and one opened from its show have to be the same thing, or the app has two answers to
    // "what is this episode".
    implementation(projects.feature.podcast)
}
