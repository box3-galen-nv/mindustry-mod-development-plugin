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
    ) {
        task.dependsOn("jar", "jarAndroid")
        task.duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        // Merge desktop + Android jars. Sources are declared lazily so the actual
        //     files (produced by the jar/jarAndroid tasks) are read at execution time.
        //     Declared here (configuration time), not in doFirst, so the output name
        //     and inputs are known for up-to-date checks.
        val jarFile = File(libsDir, "$jarName.jar")
        val androidFile = File(libsDir, "$androidName.jar")
        task.from(project.provider {
            listOf(project.zipTree(jarFile), project.zipTree(androidFile))
        })
        task.archiveFileName.set("$deployName.jar")

        // The intermediate desktop / Android jars are kept on purpose: deleting them
        //     (the declared outputs of jar / jarAndroid) forces a full re-package on every
        //     deploy, which defeats up-to-date checks for both tasks.
    }
}
