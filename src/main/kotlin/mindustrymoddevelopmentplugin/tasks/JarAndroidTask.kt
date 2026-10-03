package mindustrymoddevelopmentplugin.tasks

import mindustrymoddevelopmentplugin.sdk.AndroidSdk
import mindustrymoddevelopmentplugin.sdk.AndroidSdkInstaller
import mindustrymoddevelopmentplugin.sdk.AndroidSdkOptions
import java.io.File
import java.util.concurrent.TimeUnit
import org.gradle.api.file.FileCollection
import org.gradle.api.GradleException
import org.gradle.api.Task

/**
 * Configures `jarAndroid`, which turns the desktop jar into an Android dex with `d8`.
 *
 * The SDK is resolved (and optionally installed), the selected `d8` is run with a timeout, and its
 * merged output is drained while it runs — see [AndroidSdkOptions] for the settings and [AndroidSdkInstaller.resolveOrInstall]
 * for the SDK policy.
 */
internal object JarAndroidTask {
    /**
     * Wires `jarAndroid`, which dexes `build/libs/<jarName>.jar` into `build/libs/<androidName>.jar`.
     *
     * @param libsDir directory holding both jars and passed to `d8` as its working directory
     * @param options SDK, `d8` arguments and timeout; see [Options]
     */
    fun configure(
        task: Task,
        libsDir: File,
        jarName: String,
        androidName: String,
        deps: FileCollection,
        options: AndroidSdkOptions = AndroidSdkOptions(),
    ) {
        task.dependsOn("jar")

        // Declare the output at configuration time so Gradle can do up-to-date
        // checks and deploy can reference it safely.
        val androidOutput = File(libsDir, "$androidName.jar")
        val jarInput = File(libsDir, "$jarName.jar")
        task.outputs.file(androidOutput)

        /*
        Declare inputs too, otherwise a stale dex survives a code change (a task
        with outputs but no inputs reports UP-TO-DATE no matter what it consumes).
        The SDK is only fingerprinted by path: hashing a whole SDK costs far more
        than re-running d8.
        */
        task.inputs.files(jarInput)
        task.inputs.property("d8Args", options.d8Args)
        task.inputs.property("d8TimeoutMinutes", options.d8TimeoutMinutes)
        task.inputs.property("d8DrainJoinMillis", options.d8DrainJoinMillis)
        task.inputs.property("androidSdkDir", options.androidSdkDir?.orNull?.asFile?.absolutePath ?: "")
        task.inputs.property("androidSdkAutoDownload", options.autoDownloadSdk)
        task.inputs.property("androidSdkDownloadUrl", options.sdkDownloadUrl)
        task.inputs.property("androidSdkDownloadPackages", options.sdkDownloadPackages)
        task.inputs.property("androidSdkExtraArgs", options.sdkExtraArgs)
        // Part of the command line, so a change to it must re-run d8 rather than report UP-TO-DATE.
        task.inputs.property("d8Command", options.d8Command ?: emptyList<String>())

        task.doLast { _: Task ->
            /*
            A d8 that already exists — configured, on the PATH, or inside an installed SDK — means this
            task must not touch the SDK at all. Only when there is none anywhere does the SDK get
            installed, which is what keeps dexing possible on Termux (no SDK, `pkg install d8` instead).
            */
            val configuredSdkDir = options.androidSdkDir?.orNull?.asFile
            val sdkRoot = if (options.d8Command != null) {
                // Standalone d8: still look for an installed SDK, but only to borrow its android.jar, never
                // to install one, which is what makes this usable on Termux.
                AndroidSdk.findAndroidSdkDir(configuredSdkDir)
            } else {
                /*
                Discovery only. Installing is downloadAndroidSdk's job and this task depends on it, so anything
                the installer could have provided is already on disk; reaching the fallback means no SDK was
                found anywhere the lookup looks.
                */
                AndroidSdk.findAndroidSdkDir(configuredSdkDir) ?: AndroidSdk.resolveAndroidSdkDir(configuredSdkDir)
            }
            val d8Command = options.d8Command ?: AndroidSdk.resolveD8(null, null, sdkRoot)
                ?: throw GradleException(
                    "No d8 command found. Install one (Termux: 'pkg install d8'), point " +
                    "build.d8Executable at it, install an Android SDK, or enable " +
                    "download.androidSdkAutoDownload."
                )

            /*
            android.jar is optional on purpose. It is only the desugaring classpath, so without it d8 still
            produces valid dex for a mod that avoids default interface methods and the other desugared
            constructs. Making a missing platform fatal would break the Termux setup, where `pkg install d8`
            is the whole toolchain and no SDK exists, for a build that would otherwise succeed.
            */
            val androidJar = sdkRoot?.let { AndroidSdk.findAndroidJar(it) }
            if (androidJar == null) {
                task.logger.warn(
                    "No android.jar found, so d8 runs without the Android platform on its classpath and " +
                    "desugaring may be incomplete. Point build.androidSdkDir at an SDK with a 'platforms' " +
                    "directory if the dex fails to load in the game."
                )
            }

            // Passed in as a FileCollection: it is a type the configuration cache supports, unlike the
            // project that used to be read here.
            val classpath = buildList {
                addAll(deps.files)
                if (androidJar != null) add(androidJar)
            }

            val args = AndroidSdk.buildD8Command(
                d8Command = d8Command,
                deps = classpath,
                extraArgs = options.d8Args,
                output = androidOutput,
                input = jarInput,
            )

            val pb = ProcessBuilder(args).directory(libsDir).redirectErrorStream(true)
            val proc = pb.start()

            /*
            Drain the pipe while d8 runs. redirectErrorStream(true) merges stderr into stdout, and a
            child that fills the pipe buffer (~32-64 KiB) blocks on write while this thread blocks in
            waitFor — a deadlock that only ends with the timeout, reported as "d8 timed out". The same
            pattern is used by AndroidSdkInstaller.runSdkManager.
            */
            val output = StringBuilder()
            val drain = Thread {
                runCatching { proc.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }
            }
            drain.isDaemon = true
            drain.start()

            // Wait with a timeout so a hung d8 process cannot hang the build forever.
            // Configurable via build.d8TimeoutMinutes (minutes).
            val finished = proc.waitFor(options.d8TimeoutMinutes, TimeUnit.MINUTES)
            drain.join(options.d8DrainJoinMillis)

            if (!finished) {
                proc.destroyForcibly()
                // The captured output is the only diagnostic there is; d8's reason is usually in its last
                // lines, so never drop it here.
                androidOutput.delete()
                throw GradleException(
                    "d8 timed out after ${options.d8TimeoutMinutes} minutes. Output so far:\n$output"
                )
            }
            val exit = proc.exitValue()
            if (exit != 0) {
                // A half-written jar would otherwise count as this task's declared output and could make
                // the next run look up to date.
                androidOutput.delete()
                throw GradleException("d8 failed (exit=$exit):\n$output${AndroidSdk.d8FailureHint(output.toString())}")
            }
        }
    }
}
