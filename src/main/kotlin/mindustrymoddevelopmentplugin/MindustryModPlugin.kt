package mindustrymoddevelopmentplugin

import mindustrymoddevelopmentplugin.dsl.MindustryModExtension
import mindustrymoddevelopmentplugin.meta.ModFileReader
import mindustrymoddevelopmentplugin.meta.ModMeta
import mindustrymoddevelopmentplugin.wiring.RootWiring
import mindustrymoddevelopmentplugin.wiring.ModWiring
import mindustrymoddevelopmentplugin.logging.RunLogging
import javax.inject.Inject
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.build.event.BuildEventsListenerRegistry

/**
 * Gradle plugin for building Mindustry mods.
 *
 * The plugin registers two extensions on every project — `mindustryModRoot { }` (download, run,
 * build settings) and `mindustryMod { }` plus the top-level `modMeta { }` (mod metadata) — and
 * wires up the tasks whose bodies live in one file per task under `tasks/`
 * (`<TaskName>Task.kt`).
 *
 * It works in two phases, and the split matters:
 * - [apply] registers the extensions and the download/run tasks right away, and adds the
 *   `afterEvaluate` hook that decides whether a project is a mod.
 * - The mod tasks (`jar`, `jarAndroid`, `deploy`, `buildModHJson`) are registered from that hook,
 *   because "is this a mod project?" can only be answered once the build script has run: it depends
 *   on `modMeta.name` and on whether a metadata file exists.
 *
 * Values are read through `Provider`s or inside task configuration actions, never while the build
 * script is still executing, so a DSL value always wins over its convention default.
 */
class MindustryModPlugin @Inject constructor(
    /** Needed to close a leaked `runMindustry` log file when the task fails — Gradle has no `doFinally`. */
    private val buildEvents: BuildEventsListenerRegistry,
) : Plugin<Project> {
    override fun apply(project: Project) {
        // Once per Gradle instance, before any task can run.
        RunLogging.register(project, buildEvents)

        registerExtensions(project)

        /*
        `java` is applied here rather than in afterEvaluate so that `java { }`, `sourceSets { }` and the
        `implementation` accessor all exist while the build script is still being evaluated. Whether a
        project is a mod is not known yet, and applying `java` to a non-mod project is harmless.
        */
        if (!project.plugins.hasPlugin("java")) {
            project.pluginManager.apply("java")
        }

        project.afterEvaluate { configureModuleIfMod(project) }

        RootWiring.configureRoot(project, buildEvents)
    }

    /**
     * Registers `modMeta { }` and `mindustryMod { }`.
     *
     * Both reach the same [ModMeta] instance (see [MindustryModExtension.modMeta]), so the metadata
     * can be configured either as a top-level block or inside `mindustryMod { }`.
     */
    private fun registerExtensions(project: Project) {
        project.extensions.create("modMeta", ModMeta::class.java)
        project.extensions.create("mindustryMod", MindustryModExtension::class.java, project)
    }

    /**
     * Adds the mod build tasks to every project that is a mod project.
     *
     * The only project that is skipped is a root project without metadata: in a multi-project build
     * the root orchestrates the build (`downloadMindustry` / `runMindustry`) and the mods live in
     * subprojects, so the root should not package itself.
     */
    private fun configureModuleIfMod(project: Project) {
        val ext = project.extensions.findByType(MindustryModExtension::class.java) ?: return
        // Any of the engine's four metadata file names counts as metadata:
        // mod.json / mod.hjson / plugin.json / plugin.hjson.
        val hasModFile = ModFileReader.existingFiles(project.projectDir).isNotEmpty()
        val isRootWithoutModConfig = project == project.rootProject &&
            ext.modMeta.name.isBlank() && !hasModFile
        if (isRootWithoutModConfig) return

        ModWiring.configureModule(project, ext, hasModFile)
    }

    companion object {
        /** Task group every task this plugin registers belongs to. */
        internal const val MINDUSTRY_GROUP = "mindustry"

        /** Directory under the Gradle user home that holds the auto-installed Android SDK. */
        internal const val PLUGIN_DIR_NAME = "mindustry-mod-development-plugin"

    }
}
