package mindustrymoddevelopmentplugin.tasks

import java.io.File
import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.bundling.Jar

/**
 * Configures `deploy`, which merges the desktop and Android jars into the artifact the game loads.
 *
 * The intermediate jars are kept: deleting them would invalidate the up-to-date state of `jar` and
 * `jarAndroid` on the next build.
 */
internal object DeployTask {
    /**
     * Wires merging of the desktop ([jarName]) and Android ([androidName]) jars into [deployName].
     *
     * @param libsDir directory holding the three jars
     */
    fun configure(
        task: Jar,
        libsDir: File,
        jarName: String,
        androidName: String,
        deployName: String,
        project: Project,
        androidSdkAvailable: Boolean,
    ) {
        /*
        A desktop-only build must not be blocked by a missing Android SDK: `jarAndroid` is only wired in
        when there is an SDK to use (or auto-download is on), and otherwise the merge says so and ships
        the desktop jar alone.
        */
        task.dependsOn("jar")
        if (androidSdkAvailable) task.dependsOn("jarAndroid")
        if (!androidSdkAvailable) {
            task.doFirst {
                task.logger.warn(
                    "No usable Android SDK found, so '$deployName.jar' contains the desktop classes only. " +
                    "Set build.androidSdkDir, install one, or enable download.androidSdkAutoDownload."
                )
            }
        }
        task.duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        /*
        Declared here, not in doFirst, so the output name and inputs are known while Gradle checks
        up-to-date state; the provider keeps the jar files themselves resolved at execution time.
        */
        val jarFile = File(libsDir, "$jarName.jar")
        val androidFile = File(libsDir, "$androidName.jar")
        task.from(project.provider {
            // Resolved at execution time: the Android jar may be absent on purpose (see above).
            listOfNotNull(jarFile.takeIf { it.exists() }?.let { project.zipTree(it) },
                androidFile.takeIf { it.exists() }?.let { project.zipTree(it) })
        })
        task.archiveFileName.set("$deployName.jar")
    }
}
