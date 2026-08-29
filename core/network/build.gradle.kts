plugins {
    alias(libs.plugins.bpodcat.android.library)
    alias(libs.plugins.bpodcat.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "md.borisveriga.bpodcat.core.network"

    // NetworkModule only enables HTTP logging in debug builds.
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)

    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
}
