import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.DetektExtension
import md.borisveriga.megapodcastplayer.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

/**
 * Applies detekt to a module using the one shared configuration at `config/detekt/detekt.yml`.
 *
 * Every module gets this through its base convention plugin, so `./gradlew detekt` from the root
 * covers the whole project and a new module is analysed without opting in.
 *
 * A per-module `detekt-baseline.xml` is honoured when present. Generate one with
 * `./gradlew detektBaseline` after adding a rule that the existing code violates, so the rule
 * starts guarding new code immediately instead of waiting for a cleanup.
 *
 * Registered as `megapodcastplayer.detekt`.
 */
class DetektConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("dev.detekt")

        extensions.configure<DetektExtension> {
            // Deltas only: the shipped defaults stay in force for everything the file omits.
            buildUponDefaultConfig.set(true)
            config.setFrom(rootProject.file("config/detekt/detekt.yml"))
            // Generated sources (Room DAOs, Hilt components) are not ours to style, and analysing
            // them only produces noise, so only hand-written directories are listed.
            source.setFrom("src/main/kotlin", "src/test/kotlin", "src/androidTest/kotlin")
            parallel.set(true)

            // A baseline is an opt-in per module: pointing at a file that does not exist makes the
            // task fail its input snapshot, so only wire it up once someone has generated one.
            val moduleBaseline = file("detekt-baseline.xml")
            if (moduleBaseline.exists()) {
                baseline.set(moduleBaseline)
            }
        }

        tasks.withType<Detekt>().configureEach {
            reports {
                html.required.set(true)
                sarif.required.set(false)
            }
        }

        dependencies {
            // Adds the ktlint-derived formatting rules (import order, indentation, trailing commas)
            // on top of detekt's own rule sets.
            add("detektPlugins", libs.findLibrary("detekt-formatting").get())
        }
    }
}
