package mindustrymoddevelopmentplugin.tasks

import mindustrymoddevelopmentplugin.meta.ArtifactNaming
import mindustrymoddevelopmentplugin.dsl.MindustryBuildConfig
import mindustrymoddevelopmentplugin.dsl.MindustryModExtension
import mindustrymoddevelopmentplugin.dsl.MindustryModRootExtension
import mindustrymoddevelopmentplugin.meta.ModFileReader
import java.io.File
import org.gradle.api.Project
import org.gradle.api.logging.Logger
import org.gradle.api.GradleException
import org.gradle.api.Task

/**
 * Configures `clearMods`, which removes the jars this plugin deployed earlier.
 *
 * It only ever deletes files that carry the deployment tag *and* match the name pattern derived from
 * [mindustrymoddevelopmentplugin.dsl.MindustryBuildConfig.format] — see [deployedJarPattern] — and it deletes
 * nothing at all when the mod name cannot be resolved.
 */
internal object ClearModsTask {
    /**
     * One mod's cleanup: the name to report and the pattern its own artifacts match.
     *
     * Plain values on purpose — the action must not hold a `Project`, which the configuration cache refuses
     * to serialize, so the projects are resolved by [cleanupsFor] while configuring.
     */
    class ModCleanup(val name: String, val pattern: Regex)

    /**
     * Wires removal of previously deployed jars from [modsDir].
     *
     * @param cleanups one entry per mod project; empty means nothing is deleted
     * @param cleanDeployedFiles when false the task does nothing
     */
    fun configure(
        task: Task,
        cleanups: List<ModCleanup>,
        cleanDeployedFiles: Boolean,
        modsDir: File,
    ) {
        task.doFirst {
            if (!cleanDeployedFiles) return@doFirst
            modsDir.mkdirs()
            // List the directory once instead of calling listFiles() again for every mod.
            val existing = modsDir.listFiles().orEmpty()

            cleanups.forEach { cleanup ->
                val deleted = existing.filter {
                    it.extension == "jar" && cleanup.pattern.containsMatchIn(it.name)
                }
                if (deleted.isEmpty()) return@forEach

                task.logger.lifecycle("Cleaning old ${deleted.size} mod file(s) for '${cleanup.name}':")
                deleted.forEach { file ->
                    task.logger.lifecycle("- ${file.name}")
                    if (!file.delete()) {
                        // On Windows the game may be running and holding the jar open; leaving it behind
                        // silently means the next launch loads two copies of the mod.
                        task.logger.warn("Could not delete '$file'; is the game still running?")
                    }
                }
            }
        }
    }

    /**
     * Resolves one [ModCleanup] per mod project, while configuring.
     *
     * A project whose name cannot be resolved is skipped with a warning rather than guessed at: an empty
     * name matches every marked jar, which would wipe the deployments of unrelated mods.
     */
    fun cleanupsFor(projects: List<Project>, deployTag: String, logger: Logger): List<ModCleanup> {
        val cleanups = mutableListOf<ModCleanup>()
        projects.forEach { sub ->
            val ext = sub.extensions.findByType(MindustryModExtension::class.java)
                ?: throw GradleException(
                    "Project '${sub.path}' has a 'deploy' task of type Jar but the " +
                    "mindustry-mod-development plugin is not applied to it, so clearMods cannot tell " +
                    "which of its files are mod deployments. Either apply the plugin there or rename " +
                    "that task."
                )
            val modName = resolveModName(sub, ext)
            if (modName.isNullOrBlank()) {
                logger.warn(
                    "Skipping mod cleanup for '${sub.name}': no mod name found in the mindustryMod { } DSL " +
                    "or in mod.hjson / mod.json / plugin.hjson / plugin.json."
                )
                return@forEach
            }
            val rootExt = sub.rootProject.extensions.findByType(MindustryModRootExtension::class.java)
            cleanups += ModCleanup(
                name = modName,
                pattern = deployedJarPattern(
                    deployTag = deployTag,
                    values = ArtifactNaming.values(sub, ext),
                    format = rootExt?.build?.format?.get() ?: MindustryBuildConfig.DEFAULT_FORMAT,
                    suffixes = listOf(
                        rootExt?.build?.jarSuffix?.get() ?: MindustryBuildConfig.DEFAULT_JAR_SUFFIX,
                        rootExt?.build?.androidSuffix?.get() ?: MindustryBuildConfig.DEFAULT_ANDROID_SUFFIX,
                        rootExt?.build?.deploySuffix?.get() ?: MindustryBuildConfig.DEFAULT_DEPLOY_SUFFIX,
                    ),
                ),
            )
        }
        return cleanups
    }

    /**
     * Resolve the mod's final name: the DSL first, then an existing metadata file; null if neither is
     * present. **Callers must handle null** — an empty name matches any jar file name.
     */
    private fun resolveModName(project: Project, ext: MindustryModExtension): String? {
        val dslName = ext.modMeta.name
        if (dslName.isNotBlank()) return dslName
        return ModFileReader.readMetaValue(project.projectDir, "name")
    }

    /**
     * Matches the jar names this plugin deployed for the mod described by [values].
     *
     * The pattern is translated from [format] instead of doing a substring test, because
     * `name.contains(modName)` made mod `my` delete the files of mod `my-mod`. Each placeholder becomes
     * something that cannot swallow a longer mod name:
     *
     * - `{name}` / `{author}` → the resolved value, escaped (an author is a known literal);
     * - `{version}` → digits and dots when the version starts with a digit (a longer mod name starts with
     *   a letter), otherwise the literal value;
     * - `{build_count}` / `{time}` → digit runs, since those differ between builds.
     *
     * Deliberate limitation: artifacts built from a *different* author spelling or from a non-numeric
     * version are not matched. Missing a stale file is recoverable; deleting another mod's file is not.
     */
    internal fun deployedJarPattern(
        deployTag: String,
        values: ArtifactNaming.Values,
        format: String,
        suffixes: List<String> = emptyList(),
    ): Regex {
        val builder = StringBuilder("^").append(Regex.escape("[$deployTag]"))
        var index = 0
        while (index < format.length) {
            val placeholder = FORMAT_PLACEHOLDERS.firstOrNull { format.startsWith(it, index) }
            if (placeholder == null) {
                val next = FORMAT_PLACEHOLDERS
                    .map { format.indexOf(it, index) }
                    .filter { it >= 0 }
                    .minOrNull() ?: format.length
                builder.append(Regex.escape(format.substring(index, next)))
                index = next
                continue
            }
            when (placeholder) {
                "{name}" -> builder.append(Regex.escape(values.name))
                "{author}" -> builder.append(Regex.escape(values.author))
                "{version}" -> builder.append(
                    // No `-` or `+` in the class on purpose: with them, mod `my` matched a *different*
                    // mod called `my-2nd`, and clearMods then deleted that mod's deployment.
                    if (values.version.firstOrNull()?.isDigit() == true) "[0-9][0-9A-Za-z.]*"
                    else Regex.escape(values.version)
                )
                "{build_count}" -> builder.append("\\d+")
                "{time}" -> builder.append("[0-9_]+")
            }
            index += placeholder.length
        }
        /*
        Always anchor the end. Without it, the default format (`{name}-{version}.{build_count}`) also
        matched a different mod whose name merely starts with ours, so mod `my` deleted
        `[mm-deploy]my-2nd-1.0.3.jar`. The configured suffixes are the only allowed tail.
        */
        val tails = (suffixes.filter { it.isNotEmpty() } + listOf(""))
            .joinToString("|") { Regex.escape(it) }
        builder.append("(?:$tails)?\\.jar$")
        return Regex(builder.toString(), RegexOption.IGNORE_CASE)
    }

    /** Placeholders [MindustryBuildConfig.format] understands. */
    private val FORMAT_PLACEHOLDERS = listOf("{name}", "{author}", "{version}", "{build_count}", "{time}")
}
