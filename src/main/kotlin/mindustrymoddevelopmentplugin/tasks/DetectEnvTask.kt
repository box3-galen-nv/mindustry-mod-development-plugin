package mindustrymoddevelopmentplugin.tasks

import mindustrymoddevelopmentplugin.MindustryModPlugin
import mindustrymoddevelopmentplugin.platform.DeveloperEnvironment
import java.io.File
import org.gradle.api.Task

/**
 * Configures `detectEnv`, which reports the environment this build decided it is running in.
 *
 * A plugin that behaves differently per platform — a desktop launch, a Termux staging directory, an Android
 * SDK that may or may not exist — leaves the user guessing which of those it chose. This answers it in one
 * screen, next to the paths it resolved and the command that makes sense next, instead of leaving the
 * evidence scattered across warnings that only appear once something is already wrong.
 */
internal object DetectEnvTask {
    /**
     * @param environment the already-resolved lines from [DeveloperEnvironment.describe]
     * @param modName the resolved mod name, or null when this project is not a mod
     * @param details paths and toolchain facts the caller resolved while configuring
     * @param libsDir where built jars land, read in the action because that is a filesystem fact
     * @param reportFile where the same report is written, so it can be attached to a bug report
     */
    fun configure(
        task: Task,
        environment: List<String>,
        modName: String?,
        details: List<String>,
        libsDir: File,
        reportFile: File,
    ) {
        task.group = MindustryModPlugin.MINDUSTRY_GROUP
        task.description = "Reports the environment this build runs in, and what the plugin detected."

        task.doLast {
            val logger = task.logger
            val built = libsDir.listFiles().orEmpty().filter { it.isFile && it.extension == "jar" }
            val lines = buildList {
                addAll(environment)
                add("Mod: ${modName ?: "this project is not a mod"}")
                addAll(details)
                add(
                    if (built.isEmpty()) {
                        "Built jars: none yet in ${libsDir.absolutePath} — run ./gradlew deploy"
                    } else {
                        "Built jars: ${built.joinToString(", ") { it.name }} in ${libsDir.absolutePath}"
                    }
                )
            }
            lines.forEach { logger.lifecycle("  $it") }
            /*
            The same report goes to a file as well: it is the one thing worth attaching to a bug report, and a
            task that only ever prints it loses it the moment the console scrolls. Written without being
            declared as an output on purpose — an info task that reports UP-TO-DATE instead of printing would
            be worse than one that writes the same file again.
            */
            runCatching {
                reportFile.parentFile?.mkdirs()
                reportFile.writeText(lines.joinToString("\n") + "\n")
            }.onSuccess {
                logger.lifecycle("  Written to ${reportFile.absolutePath}")
            }.onFailure {
                logger.warn("Could not write the environment report to '$reportFile': ${it.message}")
            }
        }
    }
}
