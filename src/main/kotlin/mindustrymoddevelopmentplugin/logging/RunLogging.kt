package mindustrymoddevelopmentplugin.logging

import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.build.event.BuildEventsListenerRegistry
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import org.gradle.tooling.events.task.TaskFailureResult
import org.gradle.tooling.events.task.TaskFinishEvent

/**
 * Log tee and the "streams must close even on failure" fallback for `runMindustry`.
 *
 * Gradle has no `doFinally`, so a failing task never reaches `doLast` and the log file handle would leak.
 * The failure path is driven by [BuildEventsListenerRegistry], which reports a task *path* rather than the
 * task object — hence the path-keyed table.
 *
 * The listener is a [BuildService] because that is the only kind of task-completion listener the
 * configuration cache accepts: a plain provider fails the build when the cache is written.
 */
internal object RunLogging {

    /**
     * Collector for the failure cleanups of one build.
     *
     * One instance per build, handed to tasks through [register], so a task's action can publish a cleanup
     * without touching `Project` — the reason this is a service and not a static table.
     */
    abstract class CleanupService :
        BuildService<BuildServiceParameters.None>, OperationCompletionListener, AutoCloseable {

        private val cleanups = ConcurrentHashMap<String, () -> Unit>()

        /**
         * Runs [cleanup] when the task at [taskPath] fails.
         *
         * Success needs no entry: the task's own `doLast` has already closed the streams by then.
         */
        fun onFailure(taskPath: String, cleanup: () -> Unit) {
            cleanups[taskPath] = cleanup
        }

        override fun onFinish(event: FinishEvent) {
            val taskEvent = event as? TaskFinishEvent ?: return
            onTaskFinished(taskEvent.descriptor.taskPath, taskEvent.result is TaskFailureResult)
        }

        /**
         * Dispatch one finished task: run its cleanup on failure, drop the entry either way.
         *
         * Separate from [onFinish] so the dispatch can be tested without fabricating a tooling event.
         */
        internal fun onTaskFinished(taskPath: String, failed: Boolean) {
            val cleanup = cleanups.remove(taskPath) ?: return
            if (failed) cleanup()
        }

        override fun close() {
            cleanups.clear()
        }
    }

    /** Name of the shared service; one per build, reused across every project the plugin is applied to. */
    private const val SERVICE_NAME = "mindustryRunLogCleanup"

    /**
     * Registers the service and, once per build, subscribes it to task-finish events.
     *
     * Safe to call from every project: [org.gradle.api.services.BuildServiceRegistry.registerIfAbsent] and
     * the service itself are shared, and the listener is only attached for the first call.
     */
    fun register(project: Project, buildEvents: BuildEventsListenerRegistry): Provider<CleanupService> {
        val provider = project.gradle.sharedServices.registerIfAbsent(SERVICE_NAME, CleanupService::class.java) { }
        if (listenerRegistered.add(project.gradle)) buildEvents.onTaskCompletion(provider)
        return provider
    }

    /** Gradle instances whose task-finish listener is already registered. */
    private val listenerRegistered = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<org.gradle.api.invocation.Gradle, Boolean>(),
    )

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
