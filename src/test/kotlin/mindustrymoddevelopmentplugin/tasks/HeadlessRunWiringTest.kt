package mindustrymoddevelopmentplugin.tasks

import java.io.File
import org.gradle.api.tasks.JavaExec
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The headless server is wired like any other JVM, with two differences that matter.
 *
 * It runs `mindustry.server.ServerLauncher` in a working directory of its own, and it does **not** get
 * `-Dmindustry.data.dir`: the server sets its data directory to `<workingDir>/config` and ignores the
 * property, so passing it would only look like it did something.
 */
class HeadlessRunWiringTest {

    @Test
    fun `headless runs the server launcher in its own working directory`() {
        val project = ProjectBuilder.builder().build()
        val task = project.tasks.register("runMindustry", JavaExec::class.java).get()
        val work = File(project.projectDir, "build/headless").also { it.mkdirs() }
        val jar = File(project.projectDir, "server-release.jar").also { it.writeText("x") }

        RunMindustryTask.configure(
            task,
            modProjects = emptyList(),
            downloadPath = File(project.projectDir, "game.jar"),
            modsDir = File(work, "config/mods"),
            project = project,
            useDeployRun = true,
            deployTag = "mm-deploy",
            dataDir = File(project.projectDir, "desktop-data"),
            headless = RunMindustryTask.HeadlessOptions(jar = jar, workingDir = work),
        )

        assertTrue(task.mainClass.get() == "mindustry.server.ServerLauncher", task.mainClass.get())
        assertTrue(task.workingDir == work, task.workingDir.absolutePath)
        assertTrue(task.classpath.files.contains(jar), task.classpath.files.toString())
        assertTrue(
            task.jvmArgs.orEmpty().none { it.startsWith("-Dmindustry.data.dir") },
            "the server reads <workingDir>/config and ignores the property: ${task.jvmArgs}",
        )
        assertTrue(
            task.dependsOn.map { it.toString() }.any { it.contains("downloadHeadlessServer") },
            "it must depend on the server download, not the desktop one: ${task.dependsOn}",
        )
    }

    @Test
    fun `the desktop path is untouched by the headless options`() {
        val project = ProjectBuilder.builder().build()
        val task = project.tasks.register("runMindustry", JavaExec::class.java).get()
        val dataDir = File(project.projectDir, "data").also { it.mkdirs() }

        RunMindustryTask.configure(
            task,
            modProjects = emptyList(),
            downloadPath = File(project.projectDir, "game.jar"),
            modsDir = File(dataDir, "mods"),
            project = project,
            useDeployRun = true,
            deployTag = "mm-deploy",
            dataDir = dataDir,
        )

        assertTrue(task.mainClass.get() == "mindustry.desktop.DesktopLauncher", task.mainClass.get())
        assertTrue(
            task.jvmArgs.orEmpty().any { it == "-Dmindustry.data.dir=${dataDir.absolutePath}" },
            "the desktop run still passes the data directory: ${task.jvmArgs}",
        )
    }
}
