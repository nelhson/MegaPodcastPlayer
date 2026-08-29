plugins {
    alias(libs.plugins.bpodcat.android.feature)
}

android {
    namespace = "md.borisveriga.bpodcat.feature.player"
}

dependencies {
    implementation(projects.core.media)
    implementation(libs.androidx.media3.session)
}
