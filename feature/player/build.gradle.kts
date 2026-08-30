plugins {
    alias(libs.plugins.bpodcat.android.feature)
}

android {
    namespace = "md.borisveriga.bpodcat.feature.player"
}

dependencies {
    implementation(projects.core.media)
    implementation(libs.androidx.media3.session)

    // PredictiveBackHandler: the expanding player maps the back gesture onto its collapse
    // fraction, so back drags the sheet down rather than dismissing it.
    implementation(libs.androidx.activity.compose)
}
