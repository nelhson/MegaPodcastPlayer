plugins {
    alias(libs.plugins.bpodcat.android.library)
    alias(libs.plugins.bpodcat.android.hilt)
}

android {
    namespace = "md.borisveriga.bpodcat.core.data"
}

dependencies {
    api(projects.core.model)
    api(projects.core.database)
    api(projects.core.datastore)
    api(projects.core.media)
    implementation(projects.core.common)
    implementation(projects.core.network)

    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
}
