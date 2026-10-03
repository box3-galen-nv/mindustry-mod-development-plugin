package mindustrymoddevelopmentplugin.tasks

import java.io.File
import mindustrymoddevelopmentplugin.meta.ModArtifact
import org.gradle.api.tasks.bundling.Jar
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Android `runMindustry` stages the artifact instead of launching anything.
 *
 * The game is started by the user from its own icon, so the task's whole job is to put the jar somewhere
 * the import dialog can reach it, tagged so `clearMods` and the user can tell whose file it is.
 */
class AndroidStagingTest {

    @Test
    fun `staging copies the built artifact under the deploy tag`() {
        val project = ProjectBuilder.builder().build()
        val libs = File(project.projectDir, "libs").also { it.mkdirs() }
        File(libs, "mod.jar").writeText("jar")
        project.tasks.register("deploy", Jar::class.java) {
            it.archiveFileName.set("mod.jar")
            it.destinationDirectory.set(libs)
        }
        val staging = File(project.projectDir, "staging")
        val task = project.tasks.register("runMindustry").get()

        RunMindustryTask.configureAndroid(
            task,
            mods = listOf(ModArtifact("mod", File(libs, "mod.jar"), ":deploy")),
            deployTag = "mm-deploy",
            options = RunMindustryTask.AndroidOptions(
                appId = "io.anuke.mindustry",
                stagingDir = staging,
                launchApk = false,
                // No `am` on a desktop machine: the staging must still succeed, with a warning.
                amExecutable = File(project.projectDir, "no-such-am").absolutePath,
            ),
        )

        task.actions.forEach { it.execute(task) }

        val staged = File(staging, "[mm-deploy]mod.jar")
        assertTrue(staged.isFile, "expected $staged, got ${staging.listFiles()?.toList()}")
    }
}
