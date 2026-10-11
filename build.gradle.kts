// Top-level build file. Plugins are declared here (without applying them) so that their
// implementations land on the shared buildscript classpath; the convention plugins in
// `build-logic` then apply them by id in the modules that need them.
//
// Kover is the exception: it is *applied* here, because coverage is the one thing that only makes
// sense as a whole-project number. See the `kover { }` block below.
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
    alias(libs.plugins.kover)
}

/**
 * Whole-project test coverage.
 *
 * Reporting only, deliberately: no verification rule and no minimum threshold. A threshold picked
 * before anyone has looked at the numbers either passes trivially or blocks unrelated work, so the
 * plan (T-3 in `docs/REFACTORING_PLAN.md`) is to measure for a few weeks and then set one from
 * evidence.
 *
 * `merge { subprojects() }` applies Kover to every module for us, so there is no per-module
 * convention plugin and a new module is covered the moment `settings.gradle.kts` includes it.
 *
 * Kover 0.9.4 was the first line to understand AGP 9's built-in Kotlin. On 0.9.2 every Android
 * module reported "No sources" and the headline number silently described only the two JVM
 * modules. After a Kover or AGP bump, check that `koverLogUnit` still lists the Android packages
 * before trusting the figure.
 */
kover {
    merge {
        subprojects()

        // One variant rather than Kover's default "everything", because the default would pull in
        // the `release` variants: R8-shrunk bytecode, whose coverage describes the shrinker rather
        // than the source, built by tasks CI does not otherwise run.
        //
        // Named "unit" rather than "debug": since Kover learned AGP 9 it provides the merged Android
        // `debug` variant itself and rejects a custom variant of that name. This one is `debug`
        // plus the JVM modules, which is what the unit-test tasks CI runs actually cover.
        //
        // `optional = true` on both names because no module has both: the Android modules have
        // `debug`, the pure-Kotlin ones (`:core:model`, `:core:wearprotocol`) have `jvm`.
        createVariant("unit") {
            add("debug", optional = true)
            add("jvm", optional = true)
        }
    }

    reports {
        filters {
            excludes {
                classes(
                    // Hilt, Dagger and Room write these; they are generated, not authored.
                    "*_Factory",
                    "*_Factory\$*",
                    "*_MembersInjector",
                    "*_HiltModules*",
                    "*_GeneratedInjector",
                    "*_Impl",
                    "*_Impl\$*",
                    "dagger.hilt.internal.**",
                    "hilt_aggregated_deps.**",
                    "*.Hilt_*",
                    // Compose lambda holders and the generated build config.
                    "*ComposableSingletons*",
                    "*.BuildConfig",
                )
            }
        }

        variant("unit") {
            // Both formats: HTML for a person, XML for whatever eventually reads a trend.
            html {
                onCheck.set(false)
            }
            xml {
                onCheck.set(false)
            }
        }
    }
}
