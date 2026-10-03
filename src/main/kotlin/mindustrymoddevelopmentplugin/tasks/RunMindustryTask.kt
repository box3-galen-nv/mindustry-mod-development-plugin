package mindustrymoddevelopmentplugin.tasks

import mindustrymoddevelopmentplugin.logging.RunLogging
import mindustrymoddevelopmentplugin.meta.ModArtifact
import org.gradle.api.provider.Provider
import java.io.BufferedOutputStream
import java.io.File
import mindustrymoddevelopmentplugin.dsl.MindustryDebugConfig
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.JavaExec

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
     * Runs the game's headless server instead of the desktop client.
     *
     * `server-release.jar` is self-contained, sets its data directory to `<workingDir>/config`, and is an
     * ordinary JVM — which is why this is the only Android path where JDWP (and therefore `jdb` or a DAP
     * client) works at all.
     */
    class HeadlessOptions(
        /** The server jar, downloaded by [downloadTaskName]. */
        val jar: File,
        /** Working directory; the server's data directory is `<workingDir>/config`. */
        val workingDir: File,
        /** The task that provides [jar]. */
        val downloadTaskName: String = "downloadHeadlessServer",
    )

    /**
     * What running means on Android: stage the built jar and let the game import it.
     *
     * There is no JVM to launch (the APK's launcher takes no JVM arguments), no debugger to attach to, and
     * since Android 11 no other app may write into the game's own `Android/data` directory. Copying the
     * artifact somewhere the user can reach and importing it in the game is the only path that works.
     */
    class AndroidOptions(
        /** The Android application id, used for the `am` calls and in the log message. */
        val appId: String,
        /** Where the artifact is staged for the game's import dialog. */
        val stagingDir: File,
        /** Also ask Android to launch the game, i.e. `am start`. */
        val launchApk: Boolean,
        /** The `am` executable; injectable so a desktop machine can exercise the failure path. */
        val amExecutable: String = "am",
        /** True when staging lands in the Termux-private fallback, which the game's picker cannot see. */
        val privateStagingFallback: Boolean = false,
    )

    /**
     * Wires `runMindustry` on Android: stage every mod's artifact, tell the user to import it, and
     * optionally ask Android to bring the game up.
     *
     * No packaging dependency, exactly like the desktop task: `gradle deploy runMindustry` builds first.
     */
    fun configureAndroid(
        task: Task,
        mods: List<ModArtifact>,
        deployTag: String,
        options: AndroidOptions,
    ) {
        val prefix = "[$deployTag]"

        task.group = "mindustry"
        task.description = "Stages the built mods for Android; import them in the game to load them."

        task.doLast {
            val logger = task.logger
            options.stagingDir.mkdirs()
            /*
            The game's picker only shows shared storage, so a run that stages into the private fallback has
            to say why the import dialog will not list the file, and what to do instead.
            */
            if (options.privateStagingFallback) {
                logger.warn(
                    "Staging in '${options.stagingDir}', which the game's file picker cannot see: it only " +
                    "shows shared storage. Run termux-setup-storage so the shared Download directory can be " +
                    "used, set run.androidStagingDir to a shared location, or copy the staged jar there " +
                    "yourself."
                )
            }
            val staged = mutableListOf<File>()

            mods.forEach { mod ->
                if (!mod.jar.exists()) {
                    logger.warn(
                        "Skipping mod '${mod.name}': ${mod.jar.path} does not exist yet. Build it first " +
                        "(./gradlew ${mod.packagingCommand})."
                    )
                    return@forEach
                }
                val target = File(options.stagingDir, "$prefix${mod.jar.name}")
                mod.jar.copyTo(target, overwrite = true)
                staged.add(target)
            }

            if (staged.isEmpty()) {
                logger.warn(
                    "Nothing was staged, so there is nothing to import. Build a mod first, for example with " +
                    "./gradlew deploy."
                )
                return@doLast
            }

            logger.lifecycle(
                "Staged ${staged.size} jar(s) in ${options.stagingDir.absolutePath}: " +
                staged.joinToString(", ") { it.name } +
                ". On the phone, open the game and use Mods -> Import mod to pick the file; this build " +
                "cannot write the game's own mods folder on Android 11 or later. Set " +
                "run.androidLaunchApk = true to have this step start the game for you."
            )

            // Best effort: without `am` (a desktop machine, a locked-down ROM) the staging above is still
            // everything the user needs, so a failure here is a warning rather than the end of the task.
            runAm(task, options, "force-stop", options.appId)
            if (options.launchApk) {
                runAm(task, options, "start", "-n", "${options.appId}/mindustry.android.AndroidLauncher")
            }
        }
    }

    /** Runs `am` and reports what it said; a missing or refusing `am` never fails the build. */
    private fun runAm(task: Task, options: AndroidOptions, vararg args: String) {
        val command = listOf(options.amExecutable) + args
        runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            process.exitValue() to output
        }.onSuccess { (exit, output) ->
            if (exit == 0) {
                task.logger.lifecycle("Ran ${command.joinToString(" ")}")
            } else {
                task.logger.warn("${command.joinToString(" ")} exited with $exit: ${output.trim()}")
            }
        }.onFailure {
            task.logger.warn("Could not run '${command.joinToString(" ")}': ${it.message}")
        }
    }

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
        downloadPath: File,
        modsDir: File,
        project: Project,
        maxLogFiles: Int = MindustryDebugConfig.DEFAULT_MAX_LOG_FILES,
        enableRunLogging: Boolean = MindustryDebugConfig.DEFAULT_ENABLE_RUN_LOGGING,
        dataDir: File? = null,
        debug: DebugOptions = DebugOptions(),
        headless: HeadlessOptions? = null,
        logCleanup: Provider<RunLogging.CleanupService>? = null,
    ) {
        // Resolved while configuring: an action may not reach into the project for it.
        val logDir = project.layout.buildDirectory.dir("logger").get().asFile

        // The data directory the *game* will use: the server ignores -Dmindustry.data.dir and reads
        // <workingDir>/config instead, so the mods have to be staged there.
        val gameDataDir = headless?.let { File(it.workingDir, "config") } ?: dataDir

        task.dependsOn(headless?.downloadTaskName ?: "downloadMindustry")
        task.dependsOn("clearMods")

        /*
        Packaging is deliberately *not* a dependency: `gradle <packaging> runMindustry` builds first
        (Gradle executes command-line tasks in the given order), and a build script that wants the
        coupling writes `tasks.named("runMindustry") { dependsOn(":sub:deploy") }` itself. The copy
        below only reads the packaging task's archive path, so it works either way.
        */
        if (headless == null) {
            task.classpath = project.files(downloadPath)
            task.mainClass.set("mindustry.desktop.DesktopLauncher")
        } else {
            task.classpath = project.files(headless.jar)
            task.mainClass.set("mindustry.server.ServerLauncher")
            task.workingDir = headless.workingDir
        }

        if (dataDir != null && headless == null) {
            /*
            Absolute path on purpose: the game runs `files.absolute(...)` on the value, so a
            relative path would resolve against the process working directory, while Gradle's
            default workingDir is the project directory. JavaExec treats every jvmArgs element
            as one complete argument, so a path containing spaces is safe.
            Do not add MINDUSTRY_DATA_DIR here: the property wins over the environment variable,
            and exporting one would make the run depend on the child process environment.
            */
            task.jvmArgs("-Dmindustry.data.dir=${dataDir.absolutePath}")
        }

        if (debug.enabled) {
            /*
            `localhost:` keeps the socket on the loopback interface. The `*:` form binds every
            interface, and an unauthenticated JDWP port is remote code execution for anyone on
            the network — IDEA's Remote JVM Debug configuration also targets localhost.
            Verified on JDK 17+: `address=localhost:PORT` listens on 127.0.0.1 only.
            */
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
            if (gameDataDir != null) {
                if (gameDataDir.exists() && !gameDataDir.isDirectory) {
                    throw GradleException(
                        "The game data directory '$dataDir' exists and is not a directory. " +
                        "Point run.gameDataDir at a directory (or delete the file)."
                    )
                }
                gameDataDir.mkdirs()
                task.logger.lifecycle("Game data directory: ${gameDataDir.absolutePath}")
            }

            /*
            Usually <dataDir>/mods now: MindustryModPlugin derives that path from the data directory
            unless the build script configured one itself.
            */
            modsDir.mkdirs()

            /*
            A port that is already taken makes the game JVM abort before it starts, with a
            JDWP error that is easy to misread as a game crash — warn first.
            */
            if (debug.enabled && isPortInUse(debug.port)) {
                task.logger.warn(
                    "Debug port ${debug.port} is already in use, so the game JVM cannot open it. " +
                    "Change run.debugPort or stop whatever holds the port."
                )
            }


            /*
            Write the game output to a log file in build/logger/ (tee to console).
            Can be turned off via run.enableRunLogging = false.
            */
            if (enableRunLogging) {
                logDir.mkdirs()
                val logFile = File(logDir, "log_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS").format(Date())}.log")
                /*
                Buffered — the game can emit tens of MB, and every unbuffered write
                is a syscall (a 10 MB benchmark is ~25x slower without the buffer).
                */
                val fos = BufferedOutputStream(FileOutputStream(logFile))

                // Delete old logs, keeping only the newest maxLogFiles entries. A value below one would
                // make drop() throw, and zero would delete the log this run is writing, so clamp it.
                if (maxLogFiles < 1) {
                    task.logger.warn(
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

        /*
        Close the log file on both paths: doLast for success, a task-finish event for failure (Gradle
        has no doFinally). The flag makes "exactly once" a property of the code rather than of the
        event ordering.
        */
        var logStreamsClosed = false
        fun closeLogStreams() {
            if (!enableRunLogging || logStreamsClosed) return
            logStreamsClosed = true
            // Kotlin sees Gradle's getStandardOutput()/getErrorOutput() as non-null, but they are null
            // until the task sets them — the nullable locals say so without a redundant cast.
            val standardOut: OutputStream = task.standardOutput
            val standardErr: OutputStream = task.errorOutput
            standardOut.close()
            standardErr.close()
        }
        task.doLast { closeLogStreams() }
        // The path is read here, at configuration time: reaching for `task.path` inside the action would
        // capture the Task, which the configuration cache cannot serialize.
        val taskPath = task.path
        logCleanup?.get()?.onFailure(taskPath) { closeLogStreams() }
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
        } catch (_: java.net.BindException) {
            true
        } catch (e: IllegalArgumentException) {
            // Not "in use" but "not a port": saying the port is taken sent people hunting for a
            // process that does not exist.
            throw GradleException("run.debugPort must be a port number between 1 and 65535 (got $port).", e)
        } catch (e: java.io.IOException) {
            throw GradleException("Cannot check whether debug port $port is free: ${e.message}", e)
        }
}
