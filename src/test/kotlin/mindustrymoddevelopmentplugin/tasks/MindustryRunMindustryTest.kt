package mindustrymoddevelopmentplugin.tasks

import mindustrymoddevelopmentplugin.logging.RunLogging
import java.io.File
import java.io.OutputStream
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies the logging switch of `runMindustry` (run.enableRunLogging).
 * Drives the configureRunMindustry logic by invoking the task's doFirst action manually;
 * the game is never actually started.
 */
class MindustryRunMindustryTest {

    private fun createTask(
        enableRunLogging: Boolean,
        dataDir: File? = null,
        debug: RunMindustryTask.DebugOptions = RunMindustryTask.DebugOptions(),
    ): Pair<JavaExec, Project> {
        val project = ProjectBuilder.builder().build()
        val task = project.tasks.register("runMindustry", JavaExec::class.java).get()
        RunMindustryTask.configure(
            task = task,
            downloadPath = File(project.projectDir, "game.jar"),
            modsDir = File(project.projectDir, "mods"),
            project = project,
            maxLogFiles = 25,
            enableRunLogging = enableRunLogging,
            dataDir = dataDir,
            debug = debug,
        )
        return task to project
    }

    private fun runDoFirst(task: JavaExec) {
        task.actions.first().execute(task)
    }

    private fun runDoLast(task: JavaExec) {
        task.actions.last().execute(task)
    }

    /**
     * Gradle's `ExecSpec.getStandardOutput()` is declared non-null in Kotlin's view but actually
     * returns null until it is set. Cast to nullable so the tests can check that.
     */
    private fun JavaExec.standardOutputOrNull(): OutputStream? = standardOutput as OutputStream?

    private fun JavaExec.errorOutputOrNull(): OutputStream? = errorOutput as OutputStream?

    private fun logFiles(project: Project): List<File> {
        val logDir = project.layout.buildDirectory.dir("logger").get().asFile
        if (!logDir.exists()) return emptyList()
        return logDir.listFiles()?.filter { it.name.startsWith("log_") }.orEmpty()
    }

    @Test
    fun `logging enabled writes log file and tees output`() {
        val (task, project) = createTask(enableRunLogging = true)
        runDoFirst(task)

        val logs = logFiles(project)
        assertTrue(logs.size == 1, "Expected 1 log file, got ${logs.size}")
        assertTrue(task.standardOutputOrNull() != null, "standardOutput should be a tee stream")
        assertTrue(task.errorOutputOrNull() != null, "errorOutput should be a tee stream")

        // The doLast action must close the streams without throwing.
        runDoLast(task)
    }

    @Test
    fun `logging disabled writes no log file`() {
        val (task, project) = createTask(enableRunLogging = false)
        runDoFirst(task)

        assertTrue(logFiles(project).isEmpty(), "No log file should be written")
        assertTrue(task.standardOutputOrNull() == null, "standardOutput should stay unset")
        assertTrue(task.errorOutputOrNull() == null, "errorOutput should stay unset")

        // The doLast action must not throw even though nothing was opened.
        runDoLast(task)
    }

    @Test
    fun `tee stream flushes buffered output to the log file`() {
        val (task, project) = createTask(enableRunLogging = true)
        runDoFirst(task)

        // The log stream is buffered now: flush() must really write to disk, no log may be lost
        val out = requireNotNull(task.standardOutputOrNull())
        out.write("hello-log\n".toByteArray())
        out.flush()

        val logs = logFiles(project)
        assertTrue(logs.size == 1, "Expected 1 log file, got ${logs.size}")
        assertTrue(logs[0].readText().contains("hello-log"), "flush() 必须把缓冲内容写入日志文件")

        runDoLast(task)
        assertTrue(logs[0].readText().contains("hello-log"), "关闭后内容仍应在文件里")
    }

    // ---- JDWP (enabled by -PmindustryDebug, port from run.debugPort) ----

    @Test
    fun `debug disabled adds no jdwp argument`() {
        val (task, _) = createTask(enableRunLogging = false)
        assertTrue(task.jvmArgs.orEmpty().none { it.contains("jdwp") }, "got ${task.jvmArgs}")
    }

    @Test
    fun `debug enabled opens the configured port and can suspend`() {
        val (task, _) = createTask(
            enableRunLogging = false,
            debug = RunMindustryTask.DebugOptions(enabled = true, port = 5012, suspend = true),
        )

        // Resolved through error() so the value is non-null from here on: a preceding assertTrue(jdwp != null)
        // is understood by the IDE but not by the compiler, which left a `!!` that the IDE called redundant.
        val jdwp = task.jvmArgs.orEmpty().singleOrNull { it.contains("jdwp") }
            ?: error("expected a jdwp argument, got ${task.jvmArgs}")
        assertTrue(jdwp.contains("transport=dt_socket"), jdwp)
        assertTrue(jdwp.contains("server=y"), jdwp)
        assertTrue(jdwp.contains("suspend=y"), jdwp)
        assertTrue(jdwp.contains("5012"), jdwp)
    }

    @Test
    fun `debug without suspend does not block the game on startup`() {
        val (task, _) = createTask(
            enableRunLogging = false,
            debug = RunMindustryTask.DebugOptions(enabled = true, port = 5013, suspend = false),
        )
        val jdwp = task.jvmArgs.orEmpty().single { it.contains("jdwp") }
        assertTrue(jdwp.contains("suspend=n"), jdwp)
        // Loopback only: `*:5013` would expose the debug port to the whole network.
        assertTrue(jdwp.contains("address=localhost:5013"), jdwp)
        assertTrue(!jdwp.contains("address=*:"), "the debug socket must not bind every interface: $jdwp")
    }

    // ---- RunLogging failure cleanup (TaskExecutionListener replacement) ----

    @Test
    fun `failure cleanup runs once when the task fails and is dropped afterwards`() {
        val project = ProjectBuilder.builder().build()
        val service = project.gradle.sharedServices
            .registerIfAbsent("test-cleanup", RunLogging.CleanupService::class.java) { }
            .get()
        val task = project.tasks.register("logged").get()
        var closed = 0
        service.onFailure(task.path) { closed++ }

        // A successful task must not run the cleanup (doLast already closed the file) …
        service.onTaskFinished(task.path, failed = false)
        assertTrue(closed == 0, "cleanup must not run on success, ran $closed time(s)")

        // … and its entry is gone, so nothing accumulates across tasks or builds.
        service.onTaskFinished(task.path, failed = true)
        assertTrue(closed == 0, "the entry must be consumed by the first finish event")
    }

    @Test
    fun `failure cleanup runs when the task fails`() {
        val project = ProjectBuilder.builder().build()
        val service = project.gradle.sharedServices
            .registerIfAbsent("test-cleanup", RunLogging.CleanupService::class.java) { }
            .get()
        val task = project.tasks.register("logged-failing").get()
        var closed = 0
        service.onFailure(task.path) { closed++ }

        service.onTaskFinished(task.path, failed = true)
        assertTrue(closed == 1, "cleanup must run exactly once, ran $closed time(s)")

        // An unrelated task path is a no-op.
        service.onTaskFinished(":other", failed = true)
        assertTrue(closed == 1)
    }

    // ---- project-local game data dir ----

    @Test
    fun `data dir disabled adds no data dir argument`() {
        val (task, _) = createTask(enableRunLogging = false)

        assertTrue(
            task.jvmArgs.orEmpty().none { it.contains("mindustry.data.dir") },
            "with the feature off the JVM arguments must not change: ${task.jvmArgs}",
        )
    }

    @Test
    fun `data dir enabled passes an absolute data dir argument`() {
        val dataDir = File("/tmp/mindustry-data-dir-probe")
        val (task, _) = createTask(enableRunLogging = false, dataDir = dataDir)

        val matching = task.jvmArgs.orEmpty().filter { it.contains("mindustry.data.dir") }
        assertTrue(matching.size == 1, "exactly one argument expected: ${task.jvmArgs}")
        assertTrue(
            matching.single() == "-Dmindustry.data.dir=${dataDir.absolutePath}",
            "the game resolves relative paths against the process working directory, so it must be " +
            "absolute: ${matching.single()}",
        )
    }

    @Test
    fun `data dir is created by doFirst`() {
        val project = ProjectBuilder.builder().build()
        val dataDir = File(project.projectDir, "data")
        val task = project.tasks.register("runMindustry", JavaExec::class.java).get()
        RunMindustryTask.configure(
            task = task,
            downloadPath = File(project.projectDir, "game.jar"),
            modsDir = File(dataDir, "mods"),
            project = project,
            enableRunLogging = false,
            dataDir = dataDir,
        )

        runDoFirst(task)

        assertTrue(dataDir.isDirectory, "the data directory must exist after doFirst")
        assertTrue(File(dataDir, "mods").isDirectory, "the mods path follows the data directory")
    }

    @Test
    fun `an existing file at the data dir path fails the run`() {
        val project = ProjectBuilder.builder().build()
        val dataDir = File(project.projectDir, "data")
        dataDir.writeText("this is a file, not a directory")
        val task = project.tasks.register("runMindustry", JavaExec::class.java).get()
        RunMindustryTask.configure(
            task = task,
            downloadPath = File(project.projectDir, "game.jar"),
            modsDir = File(project.projectDir, "mods"),
            project = project,
            enableRunLogging = false,
            dataDir = dataDir,
        )

        val error = assertThrows<GradleException> { runDoFirst(task) }

        val message = error.message.orEmpty()
        assertTrue(message.contains(dataDir.toString()), message)
        assertTrue(message.contains("not a directory"), message)
    }

    // ---- packaging is not a dependency ----

    @Test
    fun `packaging is not wired as a dependency`() {
        val project = ProjectBuilder.builder().build()
        project.tasks.register("downloadMindustry")
        project.tasks.register("clearMods")
        val sub = ProjectBuilder.builder().withParent(project).withName("sub").build()
        sub.tasks.register("deploy")
        val task = project.tasks.register("runMindustry", JavaExec::class.java).get()

        RunMindustryTask.configure(
            task = task,
            downloadPath = File(project.projectDir, "game.jar"),
            modsDir = File(project.projectDir, "mods"),
            project = project,
            enableRunLogging = false,
        )

        val dependencies = task.taskDependencies.getDependencies(task).map { it.path }.toSet()
        // `gradle deploy runMindustry` builds first, and a build script that wants the coupling wires it
        // itself; the plugin must not add it behind the user's back.
        assertTrue(":sub:deploy" !in dependencies, "packaging must not be wired: $dependencies")
        assertTrue(":clearMods" in dependencies, "the mods path is still cleaned first: $dependencies")
        assertTrue(":downloadMindustry" in dependencies, "the game is still fetched first: $dependencies")
    }
}
