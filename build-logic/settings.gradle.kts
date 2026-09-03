// Settings for the `build-logic` composite build, which hosts MegaPodcastPlayer's convention plugins.
// It is included by the root `settings.gradle.kts` via `pluginManagement { includeBuild(...) }`.
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    // Reuse the main build's version catalog so there is exactly one place to bump versions.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
include(":convention")
