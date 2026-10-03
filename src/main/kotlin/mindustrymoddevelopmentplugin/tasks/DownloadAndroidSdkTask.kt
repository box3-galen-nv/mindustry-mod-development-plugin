package mindustrymoddevelopmentplugin.tasks

import mindustrymoddevelopmentplugin.sdk.AndroidSdkInstaller
import mindustrymoddevelopmentplugin.sdk.AndroidSdkOptions
import org.gradle.api.Task

/**
 * Configures `downloadAndroidSdk`, the root task that installs the SDK `jarAndroid` dexes with.
 *
 * Installing used to happen inside `jarAndroid` itself, so a build that merely wanted to inspect the task
 * graph could start a download. As its own task it can be run on its own, it is UP-TO-DATE while the
 * packages it must provide are present, and its place in the graph shows up in `--dry-run`.
 */
internal object DownloadAndroidSdkTask {
    /**
     * The packages this run has to provide.
     *
     * With a d8 already resolved the SDK is only wanted for `android.jar`, so the platform packages are
     * enough and build-tools — which that d8 replaces — are left alone. Without one, everything the project
     * asked for gets installed.
     */
    fun effectivePackages(options: AndroidSdkOptions): List<String> =
        if (options.platformOnly && options.d8Command != null) {
            options.sdkDownloadPackages.filter { it.startsWith(PLATFORM_SPEC_PREFIX) }
        } else {
            options.sdkDownloadPackages
        }

    /** Whether this run has anything to install at all. */
    fun shouldRun(options: AndroidSdkOptions): Boolean = options.autoDownloadSdk &&
        (options.d8Command == null || (options.platformOnly && effectivePackages(options).isNotEmpty()))

    /** @param options SDK location, packages and download settings, and the d8 command already resolved */
    fun configure(task: Task, options: AndroidSdkOptions) {
        val packages = effectivePackages(options)
        /*
        Skipped when installing would be wrong rather than merely unnecessary. A d8 that already resolved —
        build.d8Executable, one on the PATH, or one inside an installed SDK — means the toolchain works,
        which is what keeps Termux, where `pkg install d8` is the whole setup, from ever touching an SDK.
        */
        task.onlyIf { shouldRun(options) }

        /*
        The SDK directory is the output, so deleting it re-installs instead of reporting UP-TO-DATE, and the
        packages are the input that decides whether an install is attempted at all. The SDK is never hashed:
        that would cost far more than the check it replaces.
        */
        task.outputs.dir(options.androidSdkDir?.orNull?.asFile ?: options.sdkInstallDir)
        task.inputs.property("androidSdkDir", options.androidSdkDir?.orNull?.asFile?.absolutePath ?: "")
        task.inputs.property("androidSdkDownloadPackages", packages)
        task.inputs.property("androidSdkDownloadPlatformOnly", options.platformOnly)
        task.inputs.property("androidSdkDownloadUrl", options.sdkDownloadUrl)
        task.inputs.property("androidSdkExtraArgs", options.sdkExtraArgs)
        task.inputs.property("androidSdkDownloadTimeoutMinutes", options.sdkDownloadTimeoutMinutes)

        task.doLast { _: Task ->
            if (options.platformOnly && options.d8Command != null) {
                task.logger.lifecycle(
                    "A d8 already resolved, so only the platform packages are installed " +
                    "(${packages.joinToString(", ")}). android.jar is architecture independent and is all " +
                    "the SDK is wanted for here."
                )
            }
            // Re-checks what is on disk first, so an SDK that already satisfies the packages costs no
            // network at all.
            AndroidSdkInstaller.resolveOrInstall(options, task.logger, packages)
        }
    }
}

/** Prefix of a platform package spec, e.g. `platforms;android-34`. */
private const val PLATFORM_SPEC_PREFIX = "platforms;"
