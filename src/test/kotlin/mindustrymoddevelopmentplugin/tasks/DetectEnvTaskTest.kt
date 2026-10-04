package mindustrymoddevelopmentplugin.tasks

import java.io.File
import kotlin.io.path.createTempDirectory
import org.gradle.api.Task
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What `detectEnv` reports.
 *
 * The file it writes is the observable result on purpose: the console output cannot be captured in a
 * ProjectBuilder test, and the file is the part worth attaching to a bug report anyway.
 */
class DetectEnvTaskTest {

    private fun run(
        environment: List<String> = listOf("Host platform: desktop (detected: desktop; not Termux)"),
        modName: String? = "test-mod",
        details: List<String> = listOf("Mods directory: /tmp/mods"),
        libsDir: File = createTempDirectory("libs").toFile(),
    ): Pair<File, String> {
        val project = ProjectBuilder.builder().build()
        val report = File(project.projectDir, "build/mindustry-mod-env.txt")
        val task = project.tasks.register("detectEnv").get()
        DetectEnvTask.configure(
            task,
            environment = environment,
            modName = modName,
            details = details,
            libsDir = libsDir,
            reportFile = report,
        )
        task.actions.forEach { it.execute(task) }
        return report to (if (report.isFile) report.readText() else "")
    }

    @Test
    fun `everything it printed is in the written report`() {
        val (report, text) = run()

        assertTrue(report.isFile, "expected $report")
        assertTrue(text.contains("Host platform: desktop"), text)
        assertTrue(text.contains("Mod: test-mod"), text)
        assertTrue(text.contains("Mods directory: /tmp/mods"), text)
        assertTrue(text.contains("Built jars: none yet"), text)
        assertTrue(text.contains("./gradlew deploy"), "an empty build says what to run: $text")
    }

    @Test
    fun `built jars are named, and a project that is not a mod says so`() {
        val libs = createTempDirectory("libs").toFile()
        File(libs, "test-mod-1.0-Jar.jar").writeText("jar")
        val (_, text) = run(modName = null, libsDir = libs)

        assertTrue(text.contains("this project is not a mod"), text)
        assertTrue(text.contains("test-mod-1.0-Jar.jar"), text)
    }
}
