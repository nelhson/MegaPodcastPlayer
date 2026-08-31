pluginManagement {
    // BPodcat's convention plugins live in a composite build so every module can share them.
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // NewPipeExtractor is not published to Maven Central. The content filter keeps every
        // other dependency resolving from google()/mavenCentral(); only this one group is
        // allowed to come from JitPack.
        maven("https://jitpack.io") {
            // Exact group, so nothing else can silently start resolving from JitPack.
            content { includeGroup("com.github.TeamNewPipe") }
        }
    }
}

rootProject.name = "BPodcat"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// Applications
include(":app")
include(":wear")

// Core: pure-Kotlin contracts shared with the watch
include(":core:model")
include(":core:wearprotocol")

// Core: Android infrastructure
include(":core:common")
include(":core:database")
include(":core:datastore")
include(":core:network")
include(":core:youtube")
include(":core:media")
include(":core:data")
include(":core:designsystem")

// Core: shared unit-test utilities, on every Android module's test classpath.
include(":core:testing")

// Features
include(":feature:library")
include(":feature:downloads")
include(":feature:search")
include(":feature:podcast")
include(":feature:player")
include(":feature:settings")
