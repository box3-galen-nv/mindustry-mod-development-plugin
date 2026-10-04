package mindustrymoddevelopmentplugin.tasks

import mindustrymoddevelopmentplugin.MindustryModPlugin
import mindustrymoddevelopmentplugin.platform.DeveloperEnvironment
import java.io.File
import org.gradle.api.Task

/**
 * Configures `mindustryModInfo`, which reports the environment this build decided it is running in.
 *
 * A plugin that behaves differently per platform — a desktop launch, a Termux staging directory, an Android
 * SDK that may or may not exist — leaves the user guessing which of those it chose. This answers it in one
 * screen, next to the paths it resolved and the command that makes sense next, instead of leaving the
 * evidence scattered across warnings that only appear once something is already wrong.
 */
internal object MindustryModInfoTask {
    /**
     * @param environment the already-resolved lines from [DeveloperEnvironment.describe]
     * @param modName the resolved mod name, or null when this project is not a mod
     * @param details paths and toolchain facts the caller resolved while configuring
     * @param libsDir where built jars land, read in the action because that is a filesystem fact
     */
    fun configure(
        task: Task,
        environment: List<String>,
        modName: String?,
        details: List<String>,
        libsDir: File,
    ) {
        task.group = MindustryModPlugin.MINDUSTRY_GROUP
        task.description = "Reports the environment this build runs in, and what the plugin detected."

        task.doLast {
            val logger = task.logger
            environment.forEach { logger.lifecycle("  $it") }
            logger.lifecycle("  Mod: ${modName ?: "this project is not a mod"}")
            details.forEach { logger.lifecycle("  $it") }
            val built = libsDir.listFiles().orEmpty().filter { it.isFile && it.extension == "jar" }
            logger.lifecycle(
                if (built.isEmpty()) {
                    "  Built jars: none yet in ${libsDir.absolutePath} — run ./gradlew deploy"
                } else {
                    "  Built jars: ${built.joinToString(", ") { it.name }} in ${libsDir.absolutePath}"
                }
            )
        }
    }
}
