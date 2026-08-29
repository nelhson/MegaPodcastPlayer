plugins {
    alias(libs.plugins.bpodcat.android.library)
    alias(libs.plugins.bpodcat.android.hilt)
    alias(libs.plugins.bpodcat.android.room)
}

android {
    namespace = "md.borisveriga.bpodcat.core.database"
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
}
