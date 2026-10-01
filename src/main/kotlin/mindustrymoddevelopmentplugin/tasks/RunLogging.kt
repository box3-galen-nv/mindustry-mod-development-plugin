package mindustrymoddevelopmentplugin.tasks

import java.io.OutputStream
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.invocation.Gradle
import org.gradle.build.event.BuildEventsListenerRegistry
import org.gradle.tooling.events.OperationCompletionListener
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.task.TaskFailureResult
import org.gradle.tooling.events.task.TaskFinishEvent

/**
 * Log tee and the "streams must close even on failure" fallback for `runMindustry`.
 *
 * Gradle has no `doFinally`, so a failing task never reaches `doLast` and the log file handle
 * would leak. The failure path is driven by [BuildEventsListenerRegistry] — the replacement for
 * the `TaskExecutionListener` deprecated in Gradle 9 — which reports a task *path* rather than the
 * task object, hence the path-keyed [cleanups] table.
 */
internal object RunLogging {

    /** Cleanup action per task path, dropped as soon as that task finishes (see [onTaskFinished]). */
    private val cleanups = ConcurrentHashMap<String, FailureCleanup>()

    /** Gradle instances whose task-finish listener is already registered. Weak keys, constant value. */
    private val listenerRegistered = Collections.newSetFromMap(WeakHashMap<Gradle, Boolean>())

    /** Wrapper so the table holds a concrete type instead of a bare function type. */
    private class FailureCleanup(val action: () -> Unit)

    /**
     * Run [cleanup] when [task] fails.
     *
     * Success is handled by the task's own `doLast`; this only covers the failure path.
     */
    fun registerFailureCleanup(task: Task, cleanup: () -> Unit) {
        cleanups[task.path] = FailureCleanup(cleanup)
    }

    /**
     * Subscribe to task-finished events, once per Gradle instance.
     *
     * Called from the plugin's `apply()`, so it is in place before any task runs. Registering it
     * per project would add one listener per project for the whole build.
     */
    fun registerTaskFinishListener(project: Project, buildEvents: BuildEventsListenerRegistry) {
        if (!listenerRegistered.add(project.gradle)) return

        val listener = object : OperationCompletionListener {
            override fun onFinish(event: FinishEvent) {
                val taskEvent = event as? TaskFinishEvent ?: return
                onTaskFinished(taskEvent.descriptor.taskPath, taskEvent.result is TaskFailureResult)
            }
        }
        buildEvents.onTaskCompletion(project.providers.provider { listener })
    }

    /**
     * Dispatch one finished task: run its cleanup on failure, drop the entry either way.
     *
     * Whatever the outcome the action has served its purpose, so nothing accumulates across tasks
     * or builds. Visible for testing.
     */
    fun onTaskFinished(taskPath: String, failed: Boolean) {
        val cleanup = cleanups.remove(taskPath) ?: return
        if (failed) cleanup.action()
    }

    /** Write output to both the console and the log file; close closes only the file, never System.out/err. */
    fun teeStream(out: OutputStream, fileOut: OutputStream): OutputStream {
        return object : OutputStream() {
            override fun write(b: Int) { out.write(b); fileOut.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); fileOut.write(b, off, len) }
            override fun flush() { out.flush(); fileOut.flush() }
            override fun close() { fileOut.close() }
        }
    }
}
