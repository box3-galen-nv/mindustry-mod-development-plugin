package mindustrymoddevelopmentplugin.tasks

import java.io.File
import java.util.Properties
import java.util.zip.ZipFile
import org.gradle.api.Task

/**
 * Configures `checkAndroidApkVersion`, which compares the APK's own version with the one this build uses.
 *
 * It reads `assets/version.properties` out of the APK — the file the official builds carry, with `build`,
 * `type` and `androidBuildCode` — and never uses the network. The itch.io endpoint that answers with the
 * latest version number is deliberately not consulted: a build should not need the network to warn about a
 * version it already has on disk, and that endpoint cannot be exercised by any offline test.
 *
 * Nothing here fails a build. A mismatched APK is still worth importing; the point is that the user knows
 * why the mod may not load.
 */
internal object CheckAndroidApkVersionTask {
    /** The APK entry the official builds carry, holding `build`, `type` and `androidBuildCode`. */
    const val VERSION_ENTRY = "assets/version.properties"

    /**
     * The warnings this APK deserves, given what the build compiles against.
     *
     * Returns messages instead of logging them so the rules are unit-tested rather than observed.
     */
    fun problems(build: String?, type: String?, expectedBuild: String, appId: String): List<String> {
        val warnings = mutableListOf<String>()
        if (!build.isNullOrBlank() && expectedBuild.isNotBlank() &&
            normaliseVersion(build) != normaliseVersion(expectedBuild)
        ) {
            warnings += "The APK is Mindustry $build, but this build compiles against $expectedBuild, so the " +
                "mod may not load in it. Set download.mindustryDownloadVersion to the APK's version, or import " +
                "an APK of $expectedBuild."
        }
        if (!type.isNullOrBlank() && !type.equals("official", ignoreCase = true)) {
            warnings += "The APK's type is '$type', not 'official'. The BE build uses the application id " +
                "io.anuke.mindustry.be, so run.androidAppId (currently $appId) has to match it or am start will " +
                "launch the other app."
        }
        return warnings
    }

    /** The APK's own version properties, or null when it has none — a garbage file lands here too. */
    fun readVersionProperties(apk: File): Properties? = runCatching {
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry(VERSION_ENTRY) ?: return null
            Properties().apply { zip.getInputStream(entry).use { load(it) } }
        }
    }.getOrNull()

    /** `v146` and `146` are the same release, which is also how the downloader treats them. */
    private fun normaliseVersion(version: String): String = version.trim().removePrefix("v")

    /**
     * @param apkPath the APK to inspect; a missing one is reported and skipped, never fetched
     * @param expectedBuild `download.mindustryDownloadVersion`, what the mod is compiled against
     * @param appId `run.androidAppId`, named in the BE warning because it decides which app `am start` opens
     * @param enabled `run.androidApkVersionCheck`; false makes the task SKIPPED
     */
    fun configure(task: Task, apkPath: File, expectedBuild: String, appId: String, enabled: Boolean) {
        task.group = "mindustry"
        task.description = "Warns when the APK's version differs from the one this build compiles against."
        task.onlyIf { enabled }
        task.inputs.property("expectedBuild", expectedBuild)
        task.inputs.property("androidAppId", appId)

        task.doLast {
            val logger = task.logger
            if (!apkPath.isFile) {
                logger.lifecycle("No APK at '$apkPath', so there is no version to check.")
                return@doLast
            }
            val properties = readVersionProperties(apkPath)
            if (properties == null) {
                logger.lifecycle(
                    "'${apkPath.name}' has no $VERSION_ENTRY, so its version cannot be checked."
                )
                return@doLast
            }
            logger.lifecycle(
                "APK '${apkPath.name}': build=${properties.getProperty("build") ?: "?"}, " +
                "type=${properties.getProperty("type") ?: "?"}, " +
                "androidBuildCode=${properties.getProperty("androidBuildCode") ?: "?"}"
            )
            problems(
                build = properties.getProperty("build"),
                type = properties.getProperty("type"),
                expectedBuild = expectedBuild,
                appId = appId,
            ).forEach { logger.warn(it) }
        }
    }
}
