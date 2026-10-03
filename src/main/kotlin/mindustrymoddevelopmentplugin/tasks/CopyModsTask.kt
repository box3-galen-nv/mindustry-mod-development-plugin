package mindustrymoddevelopmentplugin.tasks

import mindustrymoddevelopmentplugin.MindustryModPlugin
import mindustrymoddevelopmentplugin.meta.ModArtifact
import java.io.File
import org.gradle.api.Task

/**
 * Configures `copyMods`, which copies the built mod jars into the game's own mods directory.
 *
 * This used to happen inside `runMindustry`'s action: it could not be run on its own, it was never
 * UP-TO-DATE, and a write into the user's game directory hid inside a task whose stated job is to launch the
 * game. The deploy tag stays in the file name so these are distinguishable from mods the user put there,
 * and `clearMods` removes exactly the files carrying it.
 */
internal object CopyModsTask {
    /** @param mods one entry per mod project, resolved while configuring */
    fun configure(task: Task, mods: List<ModArtifact>, modsDir: File, deployTag: String) {
        val prefix = "[$deployTag]"
        val targets = mods.map { File(modsDir, "$prefix${it.jar.name}") }

        task.group = MindustryModPlugin.MINDUSTRY_GROUP
        task.description = "Copies the built mod jars into the game's mods directory."
        // Declared so the copy is UP-TO-DATE while the jars and the tag are unchanged: unlike launching the
        // game, repeating this write has no point. A jar that does not exist yet is simply an input that is
        // not there, which keeps the task out of date until it is built.
        task.inputs.files(mods.map { it.jar })
        task.inputs.property("prefix", prefix)
        task.outputs.files(targets)

        task.doLast {
            val logger = task.logger
            modsDir.mkdirs()
            val copied = mutableListOf<String>()
            mods.forEachIndexed { index, mod ->
                if (!mod.jar.isFile) {
                    logger.warn(
                        "Skipping mod '${mod.name}': ${mod.jar.path} does not exist yet. Build it first " +
                        "(./gradlew ${mod.packagingCommand}), pass it on the command line " +
                        "(./gradlew ${mod.packagingCommand} runMindustry), or add " +
                        "tasks.named(\"runMindustry\") { dependsOn(\"${mod.packagingCommand}\") }."
                    )
                    return@forEachIndexed
                }
                mod.jar.copyTo(targets[index], overwrite = true)
                copied += targets[index].name
            }
            if (copied.isEmpty()) {
                logger.warn(
                    "Nothing was copied, because no mod jar exists yet. Build one first, for example with " +
                    "./gradlew deploy."
                )
            } else {
                logger.lifecycle(
                    "Copied ${copied.size} mod jar(s) into ${modsDir.absolutePath}: ${copied.joinToString(", ")}"
                )
            }
        }
    }
}
