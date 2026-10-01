package mindustrymoddevelopmentplugin.tasks

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider

/**
 * Configures `generateIdeaRunConfigs`, which writes the two IDEA run configurations in `.run/`.
 *
 * The inputs are [Provider]s on purpose: the port is read when the task runs, so a `debugPort` set
 * anywhere in the build script ends up in the files, and the task stays UP-TO-DATE while nothing
 * changed.
 */
internal object GenerateIdeaRunConfigsTask {
    /**
     * Wires `generateIdeaRunConfigs`, which writes the two IDEA run configurations.
     *
     * It is a task rather than a configuration-time side effect so that:
     * - nothing touches the project directory while the build is being configured (configuration
     *   cache friendly);
     * - the files are declared outputs, so the task is UP-TO-DATE and does not rewrite them on every
     *   build — which would otherwise fight with IDEA's own sync;
     * - [debugPort] is only read at execution time, so the DSL value is the one that ends up in the
     *   files.
     *
     * The task has no dependencies and nothing depends on it: run it explicitly
     * (`./gradlew generateIdeaRunConfigs`) once, and again after changing the port.
     *
     * Nothing depends on it and it depends on nothing, so it only runs when asked for; a build script
     * that never wants it can say `tasks.named("generateIdeaRunConfigs") { enabled = false }`.
     */
    fun configure(
        task: Task,
        project: Project,
        debugPort: Provider<Int>,
        taskName: String = "runMindustry",
        packagingTask: String = "deploy",
    ) {
        task.group = "mindustry"
        task.description = "Writes the .run/ IDEA run configurations (game + attach debugger)."

        // Inputs: the port, the two task names and the XML template revision. Outputs: the generated files.
        task.inputs.property("taskName", taskName)
        task.inputs.property("packagingTask", packagingTask)
        task.inputs.property("debugPort", debugPort)
        task.inputs.property("templateRevision", IdeaRunConfigs.TEMPLATE_REVISION)
        task.outputs.files(IdeaRunConfigs.files(project))

        task.doLast {
            val written = IdeaRunConfigs.write(
                project,
                taskName = taskName,
                debugPort = debugPort.get(),
                packagingTask = packagingTask,
            )
            written.forEach { project.logger.lifecycle("Wrote IDEA run configuration ${it.absolutePath}") }
        }
    }
}
