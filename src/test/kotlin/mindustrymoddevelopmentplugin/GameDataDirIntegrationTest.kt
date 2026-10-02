package mindustrymoddevelopmentplugin

import java.io.File
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Integration tests for the run.gameDataDir resolution and the mods path.
 */
internal class GameDataDirIntegrationTest : TestKitFixture() {


    @Test
    fun `a project-local gameDataDir puts the mods path there`() {
        writeDataDirProject("147")

        val result = runner().withArguments("clearMods").build()

        assertTrue(result.task(":clearMods")?.outcome == TaskOutcome.SUCCESS, result.output)
        assertTrue(rootDir.resolve("data/mods").isDirectory, "the game's mods path follows the data dir")
        assertTrue(!rootDir.resolve("mods").exists(), "the old default must not be used any more")
    }


    @Test
    fun `gameDataDir decides where the mods are deployed`() {
        // The mindustryModsDir setting is gone: the path is always <gameDataDir>/mods.
        writeDataDirProject("147", rootRun = "gameDataDir = file(\"custom-data\")")

        val result = runner().withArguments("clearMods").build()

        assertTrue(result.task(":clearMods")?.outcome == TaskOutcome.SUCCESS, result.output)
        assertTrue(rootDir.resolve("custom-data/mods").isDirectory, "the mods path follows gameDataDir")
        assertTrue(!rootDir.resolve("data").exists(), "the project-local default must not be used")
    }


    @Test
    fun `an old version only warns about the data dir property`() {
        // -Dmindustry.data.dir exists from v147. The run must stay usable: the game falls back to
        // MINDUSTRY_DATA_DIR or its own directory, so this is a warning, not a failure.
        writeDataDirProject("146")

        val result = runner().withArguments("runMindustry", "--dry-run").build()

        assertTrue(result.output.contains("v147"), "the warning must name the version that works:\n${result.output}")
        assertTrue(
            result.output.contains("MINDUSTRY_DATA_DIR"),
            "the warning must explain the fallback:\n${result.output}",
        )
    }


    @Test
    fun `MINDUSTRY_DATA_DIR decides the data and mods paths when nothing is configured`() {
        // The game itself honors that variable (v126+), so the plugin must follow the same directory
        // instead of guessing, and must not pass the JVM property for it.
        val envDataDir = testProjectDir.resolve("env-data").toFile().apply { mkdirs() }
        writeDataDirProject("146", rootRun = "")

        val result = runner()
            .withEnvironment(mapOf("MINDUSTRY_DATA_DIR" to envDataDir.absolutePath))
            .withArguments("clearMods", "--dry-run")
            .build()

        assertTrue(
            !result.output.contains("v147"),
            "nothing was configured, so there is no property to warn about:\n${result.output}",
        )

        val created = runner()
            .withEnvironment(mapOf("MINDUSTRY_DATA_DIR" to envDataDir.absolutePath))
            .withArguments("clearMods")
            .build()
        assertTrue(created.task(":clearMods")?.outcome == TaskOutcome.SUCCESS, created.output)
        assertTrue(File(envDataDir, "mods").isDirectory, "the mods path follows the environment variable")
        assertTrue(!rootDir.resolve("data").exists(), "no project-local data dir was asked for")
    }


    @Test
    fun `a subproject shares the root data dir`() {
        write("settings.gradle.kts", """rootProject.name = "test"
            include("sub")""")
        // No shorthand any more: a shared project-local directory is set per project, which is what a
        // real multi-project build does with an `allprojects { }` (or `subprojects { }`) block.
        write("build.gradle.kts", """
            ${pluginSnippet()}
            mindustryModRoot {
                mindustryApiVersion = "159"
                download {
                    mindustryDownloadVersion = "147"
                    // No test may reach the network: see offlineGameJar.
                    mindustryDownloadUrl = "file:///nonexistent/mindustry-releases"
                    // No test may reach the network: see offlineGameJar.
                    mindustryDownloadUrl = "file:///nonexistent/mindustry-releases"
                }
                run { gameDataDir = file("data") }
            }
        """)
        write("sub/build.gradle.kts", """
            ${pluginSnippet()}
            mindustryModRoot {
                run { gameDataDir = rootProject.layout.projectDirectory.dir("data").asFile }
            }
            mindustryMod { modMeta { name = "sub-mod"; version = "1.0"; java = true } }
        """)

        val root = runner().withArguments("clearMods").build()
        assertTrue(root.task(":clearMods")?.outcome == TaskOutcome.SUCCESS, root.output)
        assertTrue(rootDir.resolve("data/mods").isDirectory, root.output)

        val sub = runner().withArguments(":sub:clearMods").build()
        assertTrue(sub.task(":sub:clearMods")?.outcome == TaskOutcome.SUCCESS, sub.output)
        assertTrue(
            !rootDir.resolve("sub/data").exists(),
            "a subproject must use the root data dir, not one of its own:\n${sub.output}",
        )
    }


    @Test
    fun `clean does not delete the project data dir`() {
        // The data/ directory lives next to build/, not inside it: wiping the game's saves and settings with a
        // routine `clean` would be data loss.
        writeDataDirProject("147")
        val cleared = runner().withArguments("clearMods").build()
        assertTrue(cleared.task(":clearMods")?.outcome == TaskOutcome.SUCCESS, cleared.output)
        assertTrue(rootDir.resolve("data/mods").isDirectory, cleared.output)
        // Without something in build/, clean reports UP-TO-DATE and the deletion never happens.
        write("build/marker.txt", "something to delete")

        val cleaned = runner().withArguments("clean").build()

        assertTrue(cleaned.task(":clean")?.outcome == TaskOutcome.SUCCESS, cleaned.output)
        assertTrue(rootDir.resolve("data/mods").isDirectory, "clean must not touch the game data dir")
        assertTrue(!rootDir.resolve("build").exists(), "clean must still remove build/")
    }


    @Test
    fun `the resolved data dir is reported when the run starts`() {
        writeDataDirProject("147")
        // A junk jar at the download path keeps downloadMindustry SKIPPED (no network) and lets
        // runMindustry reach doFirst, which reports the data dir before the JVM fails on that jar.
        write("build/game/Mindustry-147.jar", "not a real jar")

        val result = failResult("runMindustry")

        val reported = result.output.lineSequence()
            .firstOrNull { it.contains("Game data directory:") }
            ?.substringAfter("Game data directory:")
            ?.trim()
        assertTrue(reported != null, "the run must report where the game stores its data:\n${result.output}")
        // Canonical paths: on macOS the JUnit temp dir is /var/... while Gradle reports /private/var/...
        assertTrue(
            File(reported!!).canonicalFile == rootDir.resolve("data").canonicalFile,
            "reported '$reported' must be <project>/data",
        )
    }
}
