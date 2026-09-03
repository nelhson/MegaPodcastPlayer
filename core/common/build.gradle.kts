plugins {
    alias(libs.plugins.megapodcastplayer.android.library)
    alias(libs.plugins.megapodcastplayer.android.hilt)
}

android {
    namespace = "md.borisveriga.megapodcastplayer.core.common"
}

dependencies {
    api(projects.core.model)
}
