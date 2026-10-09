package mindustrymoddevelopmentplugin.tasks

import java.io.File
import org.gradle.api.Task

/**
 * Configures `downloadHeadlessServer`, which fetches the headless server jar for the current release.
 *
 * It is its own object rather than a call to `DownloadMindustryTask` so that the rule "calling
 * downloadMindustry on Android warns" can be unconditional and still true. The two tasks share the
 * download itself — [DownloadMindustryTask.fetch] — but not the verdict: the desktop client this task does
 * *not* fetch is unlaunchable on Android, while `server-release.jar` is self-contained with `linux/aarch64`
 * natives and is the one route that runs there.
 */
internal object DownloadHeadlessServerTask {
    /** @param offline the build's `--offline` flag; a missing jar then fails instead of using the network */
    fun configure(task: Task, target: File, baseUrl: String, version: String, offline: Boolean = false) {
        DownloadMindustryTask.configure(
            task = task,
            target = target,
            baseUrl = baseUrl,
            version = version,
            assetName = "server-release.jar",
            pathPropertyName = "download.headlessJarPath",
            offline = offline,
            // The whole point: this jar is the Android route, so it must not carry the desktop's warning.
            warnOnAndroid = false,
        )
    }
}
