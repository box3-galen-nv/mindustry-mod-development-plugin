package mindustrymoddevelopmentplugin

import mindustrymoddevelopmentplugin.dsl.MindustryRunConfig
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Integration tests for clearMods.
 */
internal class ClearModsIntegrationTest : TestKitFixture() {


    @Test
    fun `clearMods resolves the name from mod hjson and keeps other mods`() {
        // The name lives only in mod.hjson (no DSL name) — exactly the workflow back-fill supports
        writeMultiProjectWithMod()
        write("sub/mod.hjson", "name: '''my-mod'''\nversion: '''1.0'''\n")
        val modsDir = rootDir.resolve("data/mods").also { it.mkdirs() }
        val ownStale = modsDir.resolve("[${MindustryRunConfig.DEFAULT_DEPLOY_TAG}]my-mod-0.9-Jar.jar").also { it.writeText("old") }
        val foreign = modsDir.resolve("[${MindustryRunConfig.DEFAULT_DEPLOY_TAG}]other-mod-1.0-Jar.jar").also { it.writeText("other") }

        val result = runner().withArguments("clearMods").build()
        assertTrue(result.task(":clearMods")?.outcome == TaskOutcome.SUCCESS)
        assertTrue(!ownStale.exists(), "该模组自己的旧 jar 应被清理")
        assertTrue(foreign.exists(), "其他模组的 jar 绝不能被删（空名字 contains(\"\") 恒为 true）")
    }


    @Test
    fun `clearMods keeps everything and warns when no name can be resolved`() {
        // No DSL name and no metadata file → the name cannot be resolved
        writeMultiProjectWithMod()
        val modsDir = rootDir.resolve("data/mods").also { it.mkdirs() }
        val foreign = modsDir.resolve("[${MindustryRunConfig.DEFAULT_DEPLOY_TAG}]other-mod-1.0-Jar.jar").also { it.writeText("other") }

        val result = runner().withArguments("clearMods").build()
        assertTrue(result.task(":clearMods")?.outcome == TaskOutcome.SUCCESS)
        assertTrue(foreign.exists(), "无法确定模组名时必须什么都不删")
        assertTrue(result.output.contains("Skipping mod cleanup"), "应给出跳过清理的警告:\n${result.output}")
    }


    // ---- clearMods safety: never delete when the name cannot be resolved ----

    private fun writeMultiProjectWithMod() {
        write("settings.gradle.kts", """
            rootProject.name = "test"
            include("sub")
        """)
        write("build.gradle.kts", """
            ${pluginSnippet}
            mindustryModRoot {
                mindustryApiVersion = "159"
                run { gameDataDir = file("data") }
            }
        """)
        write("sub/build.gradle.kts", """
            ${pluginSnippet}
            ${kotlinSnippet()}
            mindustryMod { modMeta { java = true } }
        """)
    }
}
