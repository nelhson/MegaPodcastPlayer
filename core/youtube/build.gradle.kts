plugins {
    alias(libs.plugins.bpodcat.android.library)
    alias(libs.plugins.bpodcat.android.hilt)
}

android {
    namespace = "md.borisveriga.bpodcat.core.youtube"
}

dependencies {
    // The youtube:// sentinel helpers live in :core:model, and callers of this module need them.
    api(projects.core.model)
    implementation(projects.core.common)
    // For the shared OkHttpClient: extraction shares the connection pool with feeds and artwork.
    implementation(projects.core.network)

    // Deliberately `implementation`, not `api`: no org.schabi.* type may reach :core:media or :app.
    implementation(libs.newpipe.extractor)
    implementation(libs.okhttp.core)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.mockk)
}
