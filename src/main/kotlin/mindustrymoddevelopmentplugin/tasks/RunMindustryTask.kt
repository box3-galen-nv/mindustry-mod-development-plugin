package mindustrymoddevelopmentplugin.tasks

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.bundling.Jar

/**
 * Configures `runMindustry`, which deploys the built mods and launches the game.
 *
 * The task declares no inputs/outputs on purpose: it launches the game, so Gradle skipping it as
 * UP-TO-DATE would defeat its purpose. See [DebugOptions] for the JDWP settings and
 * [configure]'s `dataDir` for the project-local game data directory.
 */
internal object RunMindustryTask {
    /**
     * JDWP settings for the launched game JVM.
     *
     * When [enabled], the game JVM opens a debug socket so an IDE can attach and stop on
     * breakpoints in the mod sources. [suspend] keeps the JVM waiting for the debugger before it
     * runs any code, which is the only way to break into content registration at startup.
     */
    class DebugOptions(
        val enabled: Boolean = false,
        val port: Int = 5005,
        val suspend: Boolean = false,
    )

    /**
     * Wires `runMindustry`.
     *
     * Packaging is not a dependency of this task. `gradle <packaging> runMindustry` builds first because
     * Gradle runs command-line tasks in the order given, and a build script that prefers the coupling
     * writes `tasks.named("runMindustry") { dependsOn(":sub:deploy") }`; a jar that is missing is reported
     * with the command that would build it.
     *
     * The task deliberately declares **no inputs/outputs**: it launches the game, so Gradle skipping it as
     * UP-TO-DATE would defeat its purpose (and an up-to-date check on "the game was launched" is
     * meaningless). Everything it writes — the deployed jars under `<gameDataDir>/mods` and the log file — is
     * redone on each run, which is also why `clearMods` runs first.
     *
     * @param dataDir when set, the game stores its data there (`settings.bin`, `saves/`, `mods/`, …)
     *   instead of in the directory it would pick by itself. This is the only place the plugin sets the
     *   `mindustry.data.dir` JVM property — never the `MINDUSTRY_DATA_DIR` environment variable, which
     *   the game reads on its own. The caller warns when the configured game version cannot read the
     *   property (it exists from v147), so such a run still works with the game's own directory. The
     *   path is absolute because the game resolves a relative one against the process working directory
     *   rather than against the project the plugin configured. The directory is created when missing,
     *   and an existing *file* at that path fails the run.
     */
    fun configure(
        task: JavaExec,
        modProjects: List<Project>,
        downloadPath: File,
        modsDir: File,
        project: Project,
        useDeployRun: Boolean,
        deployTag: String,
        maxLogFiles: Int = 25,
        enableRunLogging: Boolean = true,
        dataDir: File? = null,
        debug: DebugOptions = DebugOptions(),
    ) {
        val deployTaskName = if (useDeployRun) "deploy" else "jar"
        val prefix = "[$deployTag]"

        task.dependsOn("downloadMindustry")
        task.dependsOn("clearMods")
        // Packaging is deliberately *not* a dependency: `gradle <packaging> runMindustry` builds first
        //     (Gradle executes command-line tasks in the given order), and a build script that wants the
        //     coupling writes `tasks.named("runMindustry") { dependsOn(":sub:deploy") }` itself. The copy
        //     below only reads the packaging task's archive path, so it works either way.
        task.classpath = project.files(downloadPath)
        task.mainClass.set("mindustry.desktop.DesktopLauncher")

        if (dataDir != null) {
            // Absolute path on purpose: the game runs `files.absolute(...)` on the value, so a
            //     relative path would resolve against the process working directory, while Gradle's
            //     default workingDir is the project directory. JavaExec treats every jvmArgs element
            //     as one complete argument, so a path containing spaces is safe.
            //     Do not add MINDUSTRY_DATA_DIR here: the property wins over the environment variable,
            //     and exporting one would make the run depend on the child process environment.
            task.jvmArgs("-Dmindustry.data.dir=${dataDir.absolutePath}")
        }

        if (debug.enabled) {
            // `localhost:` keeps the socket on the loopback interface. The `*:` form binds every
            //     interface, and an unauthenticated JDWP port is remote code execution for anyone on
            //     the network — IDEA's Remote JVM Debug configuration also targets localhost.
            //     Verified on JDK 17+: `address=localhost:PORT` listens on 127.0.0.1 only.
            task.jvmArgs(
                "-agentlib:jdwp=transport=dt_socket,server=y," +
                "suspend=${if (debug.suspend) "y" else "n"},address=localhost:${debug.port}"
            )
            project.logger.lifecycle(
                "Debugger socket on port ${debug.port}" +
                (if (debug.suspend) " (the game waits for a debugger to attach)" else "")
            )
        }

        task.doFirst { _: Task ->
            if (dataDir != null) {
                if (dataDir.exists() && !dataDir.isDirectory) {
                    throw GradleException(
                        "The game data directory '$dataDir' exists and is not a directory. " +
                        "Point run.gameDataDir at a directory (or delete the file)."
                    )
                }
                dataDir.mkdirs()
                project.logger.lifecycle("Game data directory: ${dataDir.absolutePath}")
            }

            // Usually <dataDir>/mods now: MindustryModPlugin derives that path from the data directory
            //     unless the build script configured one itself.
            modsDir.mkdirs()

            // A port that is already taken makes the game JVM abort before it starts, with a
            //     JDWP error that is easy to misread as a game crash — warn first.
            if (debug.enabled && isPortInUse(debug.port)) {
                project.logger.warn(
                    "Debug port ${debug.port} is already in use, so the game JVM cannot open it. " +
                    "Change run.debugPort or stop whatever holds the port."
                )
            }

            modProjects.forEach { sub ->
                val jarTask = sub.tasks.named(deployTaskName, Jar::class.java)
                // The task's own path, not "<project.path>:<name>": the root project's path is ":", which
                // would print "::deploy" and be copy-pasted as a broken command.
                val packagingCommand = jarTask.get().path
                val jarFile = jarTask.get().archiveFile.get().asFile
                if (!jarFile.exists()) {
                    project.logger.warn(
                        "Skipping mod '${sub.name}': ${jarFile.path} does not exist yet. Build it first " +
                        "(./gradlew $packagingCommand), pass it on the command line " +
                        "(./gradlew $packagingCommand runMindustry), or add " +
                        "tasks.named(\"runMindustry\") { dependsOn(\"$packagingCommand\") }."
                    )
                    return@forEach
                }

                jarFile.copyTo(File(modsDir, "$prefix${jarFile.name}"), overwrite = true)
            }

            // Write the game output to a log file in build/logger/ (tee to console).
            //     Can be turned off via run.enableRunLogging = false.
            if (enableRunLogging) {
                val logDir = project.layout.buildDirectory.dir("logger").get().asFile
                logDir.mkdirs()
                val logFile = File(logDir, "log_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS").format(Date())}.log")
                // Buffered — the game can emit tens of MB, and every unbuffered write
                //     is a syscall (a 10 MB benchmark is ~25x slower without the buffer).
                val fos = BufferedOutputStream(FileOutputStream(logFile))

                // Delete old logs, keeping only the newest maxLogFiles entries. A value below one would
                //     make drop() throw, and zero would delete the log this run is writing, so clamp it.
                if (maxLogFiles < 1) {
                    project.logger.warn(
                        "debug.maxLogFiles is $maxLogFiles; keeping one log file instead."
                    )
                }
                val keepLogs = maxLogFiles.coerceAtLeast(1)
                val logs = logDir.listFiles()
                    ?.filter { it.name.startsWith("log_") }
                    ?.sortedDescending()
                    .orEmpty()
                if (logs.size > keepLogs) {
                    logs.drop(keepLogs).forEach { it.delete() }
                }

                task.standardOutput = RunLogging.teeStream(System.out, fos)
                task.errorOutput = RunLogging.teeStream(System.err, fos)
            }
        }

        // Close the log streams when the task succeeds (doLast) and when it fails.
        //     Gradle has no doFinally, so a task-finish event covers the failure path (see
        //     RunLogging.registerFailureCleanup) — this prevents a crashed game or failed task
        //     from leaking the file handle.
        // The success path closes in doLast and the failure path through a task-finish event; the flag
        // makes the "exactly once" a property of the code rather than of the event ordering.
        var logStreamsClosed = false
        fun closeLogStreams() {
            if (!enableRunLogging || logStreamsClosed) return
            logStreamsClosed = true
            // Kotlin sees Gradle's getStandardOutput()/getErrorOutput() as non-null, but they are null
            // until the task sets them — the nullable locals say so without a redundant cast.
            val standardOut: OutputStream? = task.standardOutput
            val standardErr: OutputStream? = task.errorOutput
            standardOut?.close()
            standardErr?.close()
        }
        task.doLast { closeLogStreams() }
        RunLogging.registerFailureCleanup(task) { closeLogStreams() }
    }

    /**
     * True when nothing can listen on [port] right now.
     *
     * Binds and immediately releases the port, which is only used to warn the user before the game
     * JVM fails with a JDWP bind error. The tiny race between this check and the real bind is
     * acceptable for a warning.
     */
    private fun isPortInUse(port: Int): Boolean =
        try {
            java.net.ServerSocket(port).close()
            false
        } catch (e: java.net.BindException) {
            true
        } catch (e: IllegalArgumentException) {
            // Not "in use" but "not a port": saying the port is taken sent people hunting for a
            // process that does not exist.
            throw GradleException("run.debugPort must be a port number between 1 and 65535 (got $port).", e)
        } catch (e: java.io.IOException) {
            throw GradleException("Cannot check whether debug port $port is free: ${e.message}", e)
        }
}
