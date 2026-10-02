package mindustrymoddevelopmentplugin.idea

import java.io.File
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Covers the IDEA run configurations written into `.run/`.
 *
 * The generated XML is parsed back, so a typo in a template fails here instead of silently
 * producing a file IDEA refuses to load.
 */
class IdeaRunConfigsTest {

    @field:TempDir
    lateinit var tempDir: Path

    private fun project(): Project = ProjectBuilder.builder().withProjectDir(tempDir.toFile()).build()

    @Test
    fun `files names the declared outputs and write creates them`() {
        val project = project()
        val files = IdeaRunConfigs.files(project.rootProject.projectDir)
        assertTrue(files.size == 2, "expected two outputs, got $files")
        assertTrue(files.none { it.exists() }, "declaring outputs must not create them")
        assertTrue(files.all { it.parentFile.name == ".run" }, "outputs belong in .run/")
        assertTrue(IdeaRunConfigs.write(project.rootProject.projectDir, "runMindustry", 5005) == files, "write must produce the declared outputs")
    }

    @Test
    fun `writes a gradle configuration and a remote debug configuration`() {
        val files = IdeaRunConfigs.write(project().rootProject.projectDir, taskName = "runMindustry", debugPort = 5010)

        assertTrue(files.size == 2, "expected exactly two configurations, got $files")
        files.forEach { assertTrue(it.isFile, "$it should exist") }
        assertTrue(files.all { it.parentFile.name == ".run" }, "configurations belong in .run/")
        assertTrue(files.all { it.name.endsWith(".run.xml") }, "IDEA only picks up *.run.xml")

        val gradle = files.first { it.name.contains("runMindustry") }.readText()
        assertTrue(gradle.contains("GradleRunConfiguration"), gradle)
        assertTrue(gradle.contains("<option value=\"runMindustry\" />"), gradle)
        // The generated configuration builds first: the task itself has no packaging dependency.
        assertTrue(
            gradle.indexOf("<option value=\"deploy\" />") in 0..<gradle.indexOf("<option value=\"runMindustry\" />"),
            "the packaging task must be listed first:\n$gradle",
        )
        assertTrue(
            gradle.contains("-P${IdeaRunConfigs.DEBUG_PROPERTY}=true"),
            "the Gradle configuration must open the debug port: $gradle"
        )

        val remote = files.first { it.name.contains("attach") }.readText()
        assertTrue(remote.contains("type=\"Remote\""), remote)
        assertTrue(remote.contains("value=\"5010\""), "the port must be the configured one: $remote")

        // Well-formed XML, otherwise IDEA silently ignores the file.
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        files.forEach { factory.newDocumentBuilder().parse(it) }
    }

    @Test
    fun `uses stable file names so a port change leaves no stale configuration`() {
        val project = project()
        IdeaRunConfigs.write(project.rootProject.projectDir, taskName = "runMindustry", debugPort = 5005)
        val files = IdeaRunConfigs.write(project.rootProject.projectDir, taskName = "runMindustry", debugPort = 5011)

        val remote = files.first { it.name.contains("attach") }
        assertTrue(remote.readText().contains("5011"), remote.readText())
        assertTrue(!remote.readText().contains("5005"), "the old port must be gone")
        assertTrue(File(tempDir.toFile(), ".run").listFiles()!!.size == 2, "no orphan file for the old port")
    }
}
