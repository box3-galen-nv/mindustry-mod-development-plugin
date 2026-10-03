package mindustrymoddevelopmentplugin.tasks

import java.io.File
import mindustrymoddevelopmentplugin.meta.ModArtifact
import org.gradle.api.Task
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What `copyMods` writes into the game's mods directory.
 *
 * It is the only place this plugin writes into the user's game directory, so the two things worth pinning
 * are the tag prefix that keeps those files findable by `clearMods`, and that a mod which has not been
 * packaged yet is skipped instead of invented.
 */
class CopyModsTaskTest {

    private fun runAction(task: Task) = task.actions.forEach { it.execute(task) }

    private fun configure(modsDir: File, vararg mods: ModArtifact): Task =
        ProjectBuilder.builder().build().let { project ->
            val task = project.tasks.register("copyMods").get()
            CopyModsTask.configure(task, mods.toList(), modsDir, "mm-deploy")
            task
        }

    @Test
    fun `an existing jar is copied with the tag prefix and declared as an output`() {
        val jar = File(createTempDir(), "mod.jar").apply { writeText("jar") }
        val modsDir = File(createTempDir(), "mods")

        val task = configure(modsDir, ModArtifact("mod", jar, ":deploy"))
        runAction(task)

        val copied = File(modsDir, "[mm-deploy]mod.jar")
        assertTrue(copied.isFile, "expected $copied, found ${modsDir.listFiles()?.toList()}")
        assertEquals("jar", copied.readText())
        // Declared so the write is UP-TO-DATE while the jars and the tag are unchanged.
        assertEquals(setOf(jar), task.inputs.files.files)
        assertEquals(setOf(copied), task.outputs.files.files)
    }

    @Test
    fun `a jar that was never built is skipped rather than invented`() {
        val modsDir = File(createTempDir(), "mods")
        val missing = File(createTempDir(), "missing.jar")

        val task = configure(modsDir, ModArtifact("mod", missing, ":sub:deploy"))
        runAction(task)

        assertTrue(
            modsDir.listFiles().orEmpty().isEmpty(),
            "nothing may be written for a mod that is not packaged: ${modsDir.listFiles()?.toList()}",
        )
    }

    @Test
    fun `every mod gets its own tagged file`() {
        val first = File(createTempDir(), "a.jar").apply { writeText("a") }
        val second = File(createTempDir(), "b.jar").apply { writeText("b") }
        val modsDir = File(createTempDir(), "mods")

        val task = configure(modsDir, ModArtifact("a", first, ":a:deploy"), ModArtifact("b", second, ":b:deploy"))
        runAction(task)

        assertEquals(
            setOf("[mm-deploy]a.jar", "[mm-deploy]b.jar"),
            modsDir.listFiles().orEmpty().map { it.name }.toSet(),
        )
    }

    private fun createTempDir(): File = kotlin.io.path.createTempDirectory("copymods").toFile()
}
