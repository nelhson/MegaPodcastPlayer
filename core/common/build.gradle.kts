plugins {
    alias(libs.plugins.megapodcastplayer.android.library)
    alias(libs.plugins.megapodcastplayer.android.hilt)
}

android {
    namespace = "md.borisveriga.megapodcastplayer.core.common"
}

dependencies {
    api(projects.core.model)

    // Crash reporting. This is the only module that compiles against Firebase: everything else
    // injects `CrashReporter`. `implementation`, not `api`, so that stays true.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)

    // The formatters say string resources now, so their tests need real ones; see FormattersTest.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
}
