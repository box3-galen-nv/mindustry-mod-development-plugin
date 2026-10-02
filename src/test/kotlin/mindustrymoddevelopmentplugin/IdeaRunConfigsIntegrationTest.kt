package mindustrymoddevelopmentplugin

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Integration tests for generateIdeaRunConfigs.
 */
internal class IdeaRunConfigsIntegrationTest : TestKitFixture() {


    @Test
    fun `the generate task writes run configurations from the DSL port and is up to date after`() {
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", """
            ${pluginSnippet}
            ${kotlinSnippet()}
            mindustryModRoot {
                mindustryApiVersion = "159"
                debug { debugPort = 5011 }
            }
            modMeta { name = "test-mod"; version = "1.0"; java = true }
        """)

        // Nothing is written while configuring: the plugin must not touch the project dir.
        assertTrue(attachConfig() == null, ".run/ must stay empty until the task runs")

        val first = runner().withArguments("generateIdeaRunConfigs").build()
        assertTrue(first.task(":generateIdeaRunConfigs")?.outcome == TaskOutcome.SUCCESS, first.output)

        val attach = attachConfig()
        assertTrue(attach != null, "the attach configuration should be generated")
        assertTrue(attach!!.contains("value=\"5011\""), "the attach port must come from the DSL:\n$attach")
        val gradle = rootDir.resolve(".run/Mindustry-runMindustry-debug.run.xml").readText()
        assertTrue(gradle.contains("port 5011"), "the Gradle configuration name should use the DSL port:\n$gradle")
        assertTrue(gradle.contains("-PmindustryDebug=true"), gradle)

        // Declared inputs/outputs: a second run must not rewrite the files.
        val second = runner().withArguments("generateIdeaRunConfigs").build()
        assertTrue(
            second.task(":generateIdeaRunConfigs")?.outcome == TaskOutcome.UP_TO_DATE,
            "expected UP-TO-DATE, got ${second.task(":generateIdeaRunConfigs")?.outcome}",
        )
    }


    @Test
    fun `the generate task depends on nothing`() {
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", """
            ${pluginSnippet}
            ${kotlinSnippet()}
            mindustryModRoot { mindustryApiVersion = "159" }
            modMeta { name = "test-mod"; version = "1.0"; java = true }
        """)
        val result = runner().withArguments("generateIdeaRunConfigs", "--dry-run").build()

        // A standalone task: no download, no build, no deploy in its graph (dry runs suffix the
        // task path with the outcome, hence the first token).
        val scheduled = result.output.lines()
            .map { it.trim() }
            .filter { it.startsWith(":") }
            .map { it.substringBefore(' ') }
        assertTrue(scheduled == listOf(":generateIdeaRunConfigs"), "expected a single task, got $scheduled")
    }


    @Test
    fun `the task can be switched off the Gradle way`() {
        // There is no DSL flag anymore: the task only runs when it is asked for, and a project that
        // never wants it disables it like any other Gradle task.
        write(".run/Mindustry-attach-debugger.run.xml", "<component name=\"mine\" />\n")
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", """
            ${pluginSnippet}
            ${kotlinSnippet()}
            mindustryModRoot { mindustryApiVersion = "159" }
            modMeta { name = "test-mod"; version = "1.0"; java = true }
            tasks.named("generateIdeaRunConfigs") { enabled = false }
        """)

        val result = runner().withArguments("generateIdeaRunConfigs").build()

        assertTrue(
            result.task(":generateIdeaRunConfigs")?.outcome == TaskOutcome.SKIPPED,
            "expected SKIPPED, got ${result.task(":generateIdeaRunConfigs")?.outcome}",
        )
        assertTrue(
            rootDir.resolve(".run/Mindustry-attach-debugger.run.xml").readText().contains("mine"),
            "a disabled task must not touch .run/ at all",
        )
        assertTrue(!rootDir.resolve(".run/Mindustry-runMindustry-debug.run.xml").exists())
    }


    @Test
    fun `a subproject does not overwrite the root run configurations`() {
        // Every project runs configureRoot(), and the files always land in the root project — so
        // the last configured subproject used to overwrite the root's port with its own default.
        write("settings.gradle.kts", """
            rootProject.name = "test"
            include("sub")
        """)
        write("build.gradle.kts", """
            ${pluginSnippet}
            mindustryModRoot {
                mindustryApiVersion = "159"
                debug { debugPort = 5012 }
            }
        """)
        write("sub/build.gradle.kts", """
            ${pluginSnippet}
            ${kotlinSnippet()}
            modMeta { name = "sub-mod"; version = "1.0"; java = true }
        """)
        val result = runner().withArguments("generateIdeaRunConfigs").build()
        assertTrue(result.task(":generateIdeaRunConfigs")?.outcome == TaskOutcome.SUCCESS, result.output)

        val attach = attachConfig()
        assertTrue(attach != null, "the root must generate the attach configuration")
        assertTrue(attach!!.contains("value=\"5012\""), "the subproject overwrote the root port:\n$attach")
    }

    private fun attachConfig(): String? =
        rootDir.resolve(".run/Mindustry-attach-debugger.run.xml").takeIf { it.isFile }?.readText()
}
