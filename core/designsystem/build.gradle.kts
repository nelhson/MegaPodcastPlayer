plugins {
    alias(libs.plugins.bpodcat.android.library)
    alias(libs.plugins.bpodcat.android.library.compose)
}

android {
    namespace = "md.borisveriga.bpodcat.core.designsystem"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // SourceBadge takes a PodcastSource. A shared badge beats three call sites each
    // configuring an icon, a label and a tint and slowly drifting apart.
    api(projects.core.model)
    api(libs.coil.compose)
    // Polygon morphing for the play/pause button and the artwork mask. material3 1.4.0 keeps
    // MaterialShapes internal, so the design system drives graphics-shapes itself.
    api(libs.androidx.graphics.shapes)
    api(libs.androidx.adaptive)
    api(libs.androidx.adaptive.layout)
    api(libs.androidx.adaptive.navigation)
    api(libs.androidx.adaptive.navigation.suite)
}
