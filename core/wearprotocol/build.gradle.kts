plugins {
    alias(libs.plugins.megapodcastplayer.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.core.model)
    implementation(libs.kotlinx.serialization.json)
}
