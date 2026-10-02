package mindustrymoddevelopmentplugin

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Integration tests for runMindustry's own wiring.
 */
internal class RunTaskIntegrationTest : TestKitFixture() {


    @Test
    fun `runMindustry does not build the mods by itself`() {
        writeMultiProjectFixture()

        // The leading colon matters: a bare `runMindustry` also matches the subproject's own run task.
        val result = runner().withArguments(":runMindustry", "--dry-run").build()

        assertTrue(!result.output.contains(":sub:deploy"), "packaging must not be scheduled:\n${result.output}")
        assertTrue(result.output.contains(":clearMods"), result.output)
        assertTrue(result.output.contains(":downloadMindustry"), result.output)
    }


    @Test
    fun `a build script can wire packaging back in`() {
        writeMultiProjectFixture()
        // The documented escape hatch: whoever wants the coupling writes it.
        write("build.gradle.kts", """
            ${pluginSnippet}
            mindustryModRoot {
                mindustryApiVersion = "159"
                run { gameDataDir = file("data") }
            }
            tasks.named("runMindustry") { dependsOn(":sub:deploy") }
        """)

        val result = runner().withArguments(":runMindustry", "--dry-run").build()

        assertTrue(result.output.contains(":sub:deploy"), "the build script's dependency must apply:\n${result.output}")
    }

    // ---- enableRunLogging (debug sub-config) ----

    @Test
    fun `enableRunLogging false configures runMindustry without error`() {
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", downloadAndRunScript(debug = """enableRunLogging = false"""))
        val result = tasksResult()
        assertTrue(result.task(":tasks")?.outcome == TaskOutcome.SUCCESS)
        assertTrue(result.output.contains("runMindustry"))
    }

    private fun writeMultiProjectFixture() {
        write("settings.gradle.kts", """rootProject.name = "test"
            include("sub")""")
        write("build.gradle.kts", """
            ${pluginSnippet}
            mindustryModRoot {
                mindustryApiVersion = "159"
                run {
                    gameDataDir = file("data")
                }
            }
        """)
        write("sub/build.gradle.kts", """
            ${pluginSnippet}
            mindustryMod { modMeta { name = "sub-mod"; version = "1.0"; java = true } }
        """)
    }
}
