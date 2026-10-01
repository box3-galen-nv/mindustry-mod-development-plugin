package mindustrymoddevelopmentplugin.dsl

import org.gradle.api.Action
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Nested

/**
 * Root project extension (`mindustryModRoot { ... }`).
 *
 * Usage example:
 * ```kotlin
 * mindustryModRoot {
 *     mindustryApiVersion = "159"
 *
 *     download {
 *         mindustryDownloadVersion = "159"
 *         mindustryGamePath = file("build/game")
 *     }
 *     run {
 *         deployTag = "mm-deploy"
 *     }
 *     debug {
 *         debugPort = 5005
 *     }
 * }
 * ```
 */
abstract class MindustryModRootExtension {

    // -- Compile API version -----------------------------------------------------

    /**
     * Mindustry version to compile against: `"159"`, `"v159"`, `"latest"` or `"be"`.
     *
     * **Optional** — without it the plugin adds no API dependency at all. With it, the
     * `arc-core` + `core` classes come from the GitHub **release assets** (artifact-only metadata, so
     * no POM and no transitive dependencies), which needs no repository from the project:
     *
     * - `be` → `MindustryBuilds` `master/latest.jar`
     * - `latest` / `>= 155.4` → `dependencies.jar` (13–15 MB)
     * - `97 … 155.3` → `Mindustry.jar` (73–89 MB, also bundles Arc)
     *
     * v97 is the floor: it is the first release with a mod system. Below that the build fails with an
     * explanation instead of an unresolvable dependency.
     *
     * The project still needs `mavenCentral()` for `kotlin-stdlib` when it uses Kotlin.
     */
    abstract val mindustryApiVersion: Property<String>

    // -- Download config (child) -------------------------------------------------

    /** Download config (created automatically by Gradle). */
    @get:Nested
    abstract val download: MindustryDownloadConfig

    /** Configures downloading the game. */
    fun download(action: Action<in MindustryDownloadConfig>) {
        action.execute(download)
    }

    // -- Build config (child) ----------------------------------------------------

    /** Build config (created automatically by Gradle). */
    @get:Nested
    abstract val build: MindustryBuildConfig

    /** Configures the build. */
    fun build(action: Action<in MindustryBuildConfig>) {
        action.execute(build)
    }

    // -- Run config (child) ------------------------------------------------------

    /** Run config (created automatically by Gradle). */
    @get:Nested
    abstract val run: MindustryRunConfig

    /** Configures running the game. */
    fun run(action: Action<in MindustryRunConfig>) {
        action.execute(run)
    }

    // -- Debug config (child) ----------------------------------------------------

    /** Logging and debugging config (created automatically by Gradle). */
    @get:Nested
    abstract val debug: MindustryDebugConfig

    /** Configures logging and debugging of the launched game. */
    fun debug(action: Action<in MindustryDebugConfig>) {
        action.execute(debug)
    }
}
