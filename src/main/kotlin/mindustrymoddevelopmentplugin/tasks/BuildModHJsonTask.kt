package mindustrymoddevelopmentplugin.tasks

import mindustrymoddevelopmentplugin.dsl.MindustryModExtension
import mindustrymoddevelopmentplugin.meta.ModFileReader
import org.gradle.api.Project
import org.gradle.api.Task

/**
 * Configures `buildModHJson`, which writes `mod.json` / `mod.hjson` from the DSL.
 *
 * Fields still at their default are back-filled from an existing metadata file in `doLast`, and the
 * task is skipped entirely when `generateModMeta` is off.
 */
internal object BuildModHJsonTask {
    /**
     * Wires generation of `mod.json` / `mod.hjson` from [ext].
     *
     * @param generateMeta when false the task is skipped by `onlyIf`
     * @param useHJson HJSON when true, strict JSON otherwise
     * @param modFileName name to write, e.g. `mod.hjson`
     */
    fun configure(
        task: Task,
        ext: MindustryModExtension,
        generateMeta: Boolean,
        useHJson: Boolean,
        project: Project,
        modFileName: String,
    ) {
        val buildModFile = project.layout.buildDirectory.file(modFileName)

        // With generateModMeta = false there is nothing to generate, so skip the task
        //     entirely instead of running an empty action on every build.
        task.onlyIf { generateMeta }

        // Declare inputs and outputs so the task is UP-TO-DATE when nothing changed.
        //     Inputs = DSL field snapshot + existing metadata files (the back-fill source).
        // Evaluated here: a provider that captures the project cannot be serialized by the
        //     configuration cache, and the snapshot is a plain string anyway.
        task.inputs.property("modMeta", ext.modMeta.inputSnapshot())
        task.inputs.property("useHJson", useHJson)
        task.inputs.files(ModFileReader.existingFiles(project.projectDir))
        task.outputs.file(buildModFile)

        task.doLast { _: Task ->
            // Back-fill fields that are still at their defaults from an existing
            //     metadata file in the project root (mod.json / mod.hjson / plugin.json /
            //     plugin.hjson, engine priority), so enabling generateModMeta does not
            //     silently drop metadata that is only present in the file.
            //     DSL values always win (see ModMeta.fillMissingFrom).
            // Captured as a plain File: an action may not reach back into the project.
            val projectDir = project.projectDir
            ModFileReader.existingFiles(projectDir).firstOrNull()?.let { file ->
                runCatching { file.readText() }
                    .onSuccess { ext.modMeta.fillMissingFrom(it) }
                    .onFailure { task.logger.warn("Could not read ${file.name} for metadata back-fill: $it") }
            }
            val content = if (useHJson) ext.modMeta.toHJson() else ext.modMeta.toJson()
            val file = buildModFile.get().asFile
            file.parentFile.mkdirs()
            file.writeText(content)
        }
    }
}
