package mindustrymoddevelopmentplugin.tasks

import java.io.File
import mindustrymoddevelopmentplugin.meta.ModFileReader
import mindustrymoddevelopmentplugin.meta.ModMeta
import org.gradle.api.Task

/**
 * Configures `buildModHJson`, which writes `mod.json` / `mod.hjson` from the DSL.
 *
 * Fields still at their default are back-filled from an existing metadata file in `doLast`, and the task is
 * skipped entirely when `generateModMeta` is off.
 *
 * Everything the action needs is passed in as a plain value — the [ModMeta] bean, the project directory and
 * the output file. Nothing here may hold a `Project` or the extension that owns one, or the configuration
 * cache refuses to serialize the task.
 */
internal object BuildModHJsonTask {
    /**
     * Wires generation of `mod.json` / `mod.hjson` from [meta].
     *
     * @param generateMeta when false the task is skipped by `onlyIf`
     * @param useHJson HJSON when true, strict JSON otherwise
     * @param projectDir where the back-fill source lives
     * @param buildModFile the file to write
     */
    fun configure(
        task: Task,
        meta: ModMeta,
        generateMeta: Boolean,
        useHJson: Boolean,
        projectDir: File,
        buildModFile: File,
    ) {
        // With generateModMeta = false there is nothing to generate, so skip the task
        // entirely instead of running an empty action on every build.
        task.onlyIf { generateMeta }

        // Declare inputs and outputs so the task is UP-TO-DATE when nothing changed.
        // Inputs = DSL field snapshot + existing metadata files (the back-fill source).
        task.inputs.property("modMeta", meta.inputSnapshot())
        task.inputs.property("useHJson", useHJson)
        task.inputs.files(ModFileReader.existingFiles(projectDir))
        task.outputs.file(buildModFile)

        task.doLast { _: Task ->
            /*
            Back-fill fields that are still at their defaults from an existing metadata file in the
            project root (the engine's four names, in its own priority), so enabling generateModMeta
            does not silently drop metadata that is only present in the file. DSL values always win
            (see ModMeta.fillMissingFrom).
            */
            ModFileReader.existingFiles(projectDir).firstOrNull()?.let { file ->
                runCatching { file.readText() }
                    .onSuccess { meta.fillMissingFrom(it) }
                    .onFailure { task.logger.warn("Could not read ${file.name} for metadata back-fill: $it") }
            }
            val content = if (useHJson) meta.toHJson() else meta.toJson()
            buildModFile.parentFile.mkdirs()
            buildModFile.writeText(content)
        }
    }
}
