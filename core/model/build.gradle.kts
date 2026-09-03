plugins {
    alias(libs.plugins.megapodcastplayer.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
}
