package mindustrymoddevelopmentplugin.tasks

import java.io.File
import java.util.zip.ZipFile
import org.gradle.api.GradleException
import org.gradle.api.Task

/**
 * Configures `downloadAndroidApk`, which fetches the Mindustry APK the Android run can stage and import.
 *
 * It cannot fetch the APK on its own: itch.io serves no direct file URL, only a session-backed handshake, so
 * a project either puts the file at `run.androidApkPath` itself, or names a URL it is
 * allowed to use. Nothing here fails a build for a missing APK, because staging the mod jars works
 * without one.
 */
internal object DownloadAndroidApkTask {
    /**
     * Null when [apk] looks like an installable Mindustry APK, otherwise the reason it does not.
     *
     * A zip signature is not enough: a valid *other* archive would pass it. A real APK carries
     * `AndroidManifest.xml` — without it Android refuses the package — and `classes.dex`, which is what the
     * engine looks for inside a mod.
     */
    fun apkProblem(apk: File): String? {
        if (!apk.isFile) return "it does not exist"
        val entries = runCatching {
            ZipFile(apk).use { zip -> zip.entries().asSequence().map { it.name }.toSet() }
        }.getOrElse { return "it is not a zip archive (${apk.length()} bytes)" }
        if ("AndroidManifest.xml" !in entries) return "it has no AndroidManifest.xml, so Android cannot install it"
        if ("classes.dex" !in entries) return "it has no classes.dex, so the game has no code to load"
        return null
    }

    /** What to tell a user who has no APK and no URL to fetch one from. */
    fun guidance(apkPath: File): String =
        "No APK at '$apkPath' and run.androidApkUrl is not set, so nothing was downloaded. itch.io serves no " +
        "direct file URL — its downloads go through a session-backed handshake — so download the APK once " +
        "from https://anuke.itch.io/mindustry into that path, or set run.androidApkUrl to a link this build " +
        "is allowed to fetch (your own itch.io key link, or a mirror). Staging the mod jars works anyway."

    /**
     * @param apkPath where the APK is expected; an existing file is used as-is and never replaced
     * @param apkUrl the URL to fetch from; blank means report [guidance] instead of downloading
     * @param offline the build's `--offline` flag, which a missing APK must not quietly ignore
     */
    fun configure(task: Task, apkPath: File, apkUrl: String, offline: Boolean) {
        task.group = "mindustry"
        task.description = "Downloads the Mindustry APK the Android run can import."
        // The URL is the input, so changing it re-runs the task even for the same file name.
        task.inputs.property("url", apkUrl)

        task.doLast {
            val logger = task.logger
            if (apkUrl.isBlank()) {
                /**
                 * No URL is not a failure: the APK is optional, and saying where to get one is more useful
                 * than stopping a build that can stage its jars perfectly well.
                 */
                if (apkPath.isFile) {
                    logger.lifecycle(
                        "Using the existing '${apkPath.name}' (${apkPath.length() / (1024 * 1024)} MB)"
                    )
                } else {
                    logger.warn(guidance(apkPath))
                }
                val problem = apkProblem(apkPath)
                if (problem != null) logger.warn("'$apkPath' is not usable as a Mindustry APK: $problem.")
                return@doLast
            }

            if (offline && !apkPath.isFile) {
                throw GradleException(
                    "Cannot download $apkUrl in offline mode and '$apkPath' does not exist. Run without " +
                    "--offline, or put the APK there yourself."
                )
            }
            if (!apkPath.isFile) {
                val parent = apkPath.parentFile
                if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                    throw GradleException("Cannot create the directory for '$apkPath'.")
                }
                DownloadMindustryTask.fetch(
                    apkUrl, apkPath, logger::lifecycle,
                    expected = "Mindustry APK",
                    sourceHint = "run.androidApkUrl",
                )
            }
            // A URL was given, so a file that is not an APK is wrong rather than merely absent.
            apkProblem(apkPath)?.let {
                throw GradleException("'$apkPath' is not usable as a Mindustry APK: $it.")
            }
        }
    }
}
