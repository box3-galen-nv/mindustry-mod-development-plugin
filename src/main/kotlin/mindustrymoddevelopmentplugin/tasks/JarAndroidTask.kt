package mindustrymoddevelopmentplugin.tasks

import mindustrymoddevelopmentplugin.dsl.MindustryBuildConfig
import mindustrymoddevelopmentplugin.dsl.MindustryDownloadConfig
import java.io.File
import java.util.concurrent.TimeUnit
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.DirectoryProperty

/**
 * Configures `jarAndroid`, which turns the desktop jar into an Android dex with `d8`.
 *
 * The SDK is resolved (and optionally installed), the selected `d8` is run with a timeout, and its
 * merged output is drained while it runs — see [Options] for the settings and [resolveOrInstallSdk]
 * for the SDK policy.
 */
internal object JarAndroidTask {
    /**
     * Everything `jarAndroid` needs from `mindustryModRoot { build { } }`.
     *
     * Grouped into one object because the flat parameter list stopped being readable; the values
     * are resolved at configuration time exactly like the DSL properties they come from.
     */
    class Options(
        val androidSdkDir: DirectoryProperty? = null,
        val d8Args: List<String> = emptyList(),
        val d8TimeoutMinutes: Long = MindustryBuildConfig.DEFAULT_D8_TIMEOUT_MINUTES,
        val d8DrainJoinMillis: Long = MindustryBuildConfig.DEFAULT_D8_DRAIN_JOIN_MILLIS,
        val autoDownloadSdk: Boolean = true,
        val sdkDownloadUrl: String = "",
        val sdkDownloadPackages: List<String> = emptyList(),
        val sdkExtraArgs: List<String> = emptyList(),
        val sdkInstallDir: File = File("."),
        val sdkDownloadTimeoutMinutes: Long =
            MindustryDownloadConfig.DEFAULT_ANDROID_SDK_DOWNLOAD_TIMEOUT_MINUTES,
    )

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
        project: Project,
        options: Options = Options(),
    ) {
        task.dependsOn("jar")

        // Declare the output at configuration time so Gradle can do up-to-date
        //     checks and deploy can reference it safely.
        val androidOutput = File(libsDir, "$androidName.jar")
        val jarInput = File(libsDir, "$jarName.jar")
        task.outputs.file(androidOutput)

        // Declare inputs too, otherwise a stale dex survives a code change (a task
        //     with outputs but no inputs reports UP-TO-DATE no matter what it consumes).
        //     The SDK is only fingerprinted by path: hashing a whole SDK costs far more
        //     than re-running d8.
        task.inputs.files(jarInput)
        task.inputs.property("d8Args", options.d8Args)
        task.inputs.property("d8TimeoutMinutes", options.d8TimeoutMinutes)
        task.inputs.property("d8DrainJoinMillis", options.d8DrainJoinMillis)
        task.inputs.property("androidSdkDir", options.androidSdkDir?.orNull?.asFile?.absolutePath ?: "")
        task.inputs.property("androidSdkAutoDownload", options.autoDownloadSdk)
        task.inputs.property("androidSdkDownloadUrl", options.sdkDownloadUrl)
        task.inputs.property("androidSdkDownloadPackages", options.sdkDownloadPackages)
        task.inputs.property("androidSdkExtraArgs", options.sdkExtraArgs)

        task.doLast { _: Task ->
            val sdkRoot = resolveOrInstallSdk(project, options)

            // Pick the newest platform / build-tools by VERSION NUMBER, not by
            //     string sort (string sort wrongly prefers "android-9" over "android-30").
            val platformRoot = File(sdkRoot, "platforms")
                .listFiles()
                ?.filter { File(it, "android.jar").exists() }
                ?.maxWithOrNull(Comparator(AndroidSdk::compareSdkVersions))
                ?: throw GradleException("No android.jar found in '$sdkRoot/platforms'")
            val androidJar = File(platformRoot, "android.jar")

            val buildToolsRoot = File(sdkRoot, "build-tools")
                .listFiles()
                ?.filter { File(it, "d8").exists() || File(it, "d8.jar").exists() }
                ?.maxWithOrNull(Comparator(AndroidSdk::compareSdkVersions))
                ?: throw GradleException("No build-tools found in '$sdkRoot/build-tools'")

            val d8Binary = when {
                File(buildToolsRoot, "d8").exists() -> File(buildToolsRoot, "d8").absolutePath
                else -> File(buildToolsRoot, "lib/d8.jar").absolutePath
            }

            val deps = buildSet {
                addAll(project.configurations.getByName("compileClasspath").files)
                addAll(project.configurations.getByName("runtimeClasspath").files)
                add(androidJar)
            }

            val args = AndroidSdk.buildD8Command(
                d8Binary = d8Binary,
                deps = deps,
                extraArgs = options.d8Args,
                output = androidOutput,
                input = jarInput,
            )

            val pb = ProcessBuilder(args).directory(libsDir).redirectErrorStream(true)
            val proc = pb.start()

            // Drain the pipe while d8 runs. redirectErrorStream(true) merges stderr into stdout, and a
            // child that fills the pipe buffer (~32-64 KiB) blocks on write while this thread blocks in
            // waitFor — a deadlock that only ends with the timeout, reported as "d8 timed out". The same
            // pattern is used by AndroidSdkInstaller.runSdkManager.
            val output = StringBuilder()
            val drain = Thread {
                runCatching { proc.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }
            }
            drain.isDaemon = true
            drain.start()

            // Wait with a timeout so a hung d8 process cannot hang the build forever.
            //     Configurable via build.d8TimeoutMinutes (minutes).
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

    /**
     * Returns a usable SDK directory, installing one when [Options.autoDownloadSdk] is on
     * and the resolved SDK cannot satisfy [Options.sdkDownloadPackages].
     *
     * A configured `build.androidSdkDir` that does not exist is *not* fatal: the lookup falls back to
     * `ANDROID_HOME` / `ANDROID_SDK_ROOT` / the usual user locations first, and only when none of
     * them satisfies the requested packages does the installer run.
     *
     * The installer only ever writes to a directory the plugin owns or was pointed at: the configured
     * `androidSdkDir` (an empty or absent one is created), otherwise
     * [Options.sdkInstallDir]. An SDK that was *found* elsewhere — a real Android Studio
     * SDK behind `ANDROID_HOME`, say — is used as-is when it satisfies the packages and otherwise left
     * untouched, so a build never silently mutates someone else's SDK.
     *
     * With auto-download off this falls back to [AndroidSdk.resolveAndroidSdkDir], which fails loudly
     * with the list of locations it probed — the behavior before auto-download existed.
     */
    internal fun resolveOrInstallSdk(project: Project, options: Options): File {
        val configuredDir = options.androidSdkDir?.orNull?.asFile
        val existing = AndroidSdk.findAndroidSdkDir(configuredDir)

        if (!options.autoDownloadSdk) {
            return existing ?: AndroidSdk.resolveAndroidSdkDir(configuredDir)
        }

        if (!AndroidSdkInstaller.needsInstall(existing, options.sdkDownloadPackages)) return existing!!

        val target = configuredDir ?: options.sdkInstallDir
        project.logger.lifecycle(
            "Android SDK missing or incomplete in '$target' — downloading the command-line tools " +
            "and installing: ${options.sdkDownloadPackages.joinToString(", ")}"
        )
        AndroidSdkInstaller.install(
            sdkRoot = target,
            toolsUrl = options.sdkDownloadUrl,
            packages = options.sdkDownloadPackages,
            extraArgs = options.sdkExtraArgs,
            timeoutMinutes = options.sdkDownloadTimeoutMinutes,
            log = { project.logger.lifecycle("[android-sdk] $it") },
        )
        return target
    }
}
