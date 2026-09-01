package md.borisveriga.bpodcat.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/**
 * The `libs` version catalog declared in `gradle/libs.versions.toml`.
 *
 * Convention plugins cannot use the generated type-safe `libs` accessor (that only exists inside
 * build scripts), so they go through the catalog API instead.
 */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/**
 * Reads an integer version (SDK levels) from the catalog.
 *
 * @param alias catalog version alias, e.g. `compileSdk`.
 * @return the version as an [Int].
 * @throws NoSuchElementException if the alias is missing from the catalog.
 */
internal fun VersionCatalog.int(alias: String): Int =
    findVersion(alias).get().requiredVersion.toInt()

/**
 * Reads a string version (the application `versionName`) from the catalog.
 *
 * @param alias catalog version alias, e.g. `versionName`.
 * @return the version exactly as written in the catalog.
 * @throws NoSuchElementException if the alias is missing from the catalog.
 */
internal fun VersionCatalog.string(alias: String): String =
    findVersion(alias).get().requiredVersion
