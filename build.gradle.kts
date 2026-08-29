// Top-level build file. Plugins are declared here (without applying them) so that their
// implementations land on the shared buildscript classpath; the convention plugins in
// `build-logic` then apply them by id in the modules that need them.
buildscript {
    dependencies {
        // AGP 9 bundles KGP 2.2.10 for its built-in Kotlin support. These classpath entries pull
        // the toolchain up to the versions pinned in `gradle/libs.versions.toml`.
        // See https://developer.android.com/build/releases/agp-9-0-0-release-notes
        classpath(libs.kotlin.gradlePlugin)
        classpath(libs.ksp.gradlePlugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.detekt) apply false
}
