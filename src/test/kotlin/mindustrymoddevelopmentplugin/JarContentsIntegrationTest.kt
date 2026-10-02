package mindustrymoddevelopmentplugin

import java.util.zip.ZipFile
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Integration tests for what ends up inside the packaged jar.
 */
internal class JarContentsIntegrationTest : TestKitFixture() {


    @Test
    fun `jar contains icon-png when source is icon-png`() {
        val result = buildJar("icon.png")
        assertTrue(jarEntryNames().contains("icon.png"))
        assertTrue(!result.output.contains("not a PNG image"))
    }

    @Test
    fun `jar contains preview-png when source is preview-png`() {
        val result = buildJar("preview.png")
        assertTrue(jarEntryNames().contains("preview.png"))
        assertTrue(!result.output.contains("not a PNG image"))
    }

    @Test
    fun `jar renames custom png to icon-png`() {
        val result = buildJar("my-logo.png")
        val entries = jarEntryNames()
        assertTrue(entries.contains("icon.png"), "Expected icon.png in jar, got $entries")
        assertTrue(!entries.contains("my-logo.png"), "my-logo.png should not appear in jar")
        assertTrue(!result.output.contains("not a PNG image"))
    }

    @Test
    fun `warns for icon-jpg`() {
        val result = buildJar("icon.jpg")
        val entries = jarEntryNames()
        assertTrue(entries.contains("icon.png"), "Expected icon.png in jar, got $entries")
        assertTrue(result.output.contains("not a PNG image"))
    }

    @Test
    fun `warns for preview-gif`() {
        val result = buildJar("preview.gif")
        val entries = jarEntryNames()
        assertTrue(entries.contains("icon.png"), "Expected icon.png in jar, got $entries")
        assertTrue(result.output.contains("not a PNG image"))
    }
    // ---- Download file name validation ----

    /**
     * Build script with the download and run sub-configs.
     *
     * Both snippets are passed separately so each lands in the block that owns its property
     * (`download { }` for the game jar and the Android SDK, `run { }` for the data dir and deploy, `debug { }` for
     * logging and debugging).
     */


    // ---- mod.json alone must still be packed into the jar ----

    @Test
    fun `jar includes mod json when only mod json exists`() {
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("mod.json", """{ "name": "json-mod", "version": "1.0", "java": true }""")
        write("build.gradle.kts", """
            ${pluginSnippet()}
            ${kotlinSnippet()}
            mindustryModRoot { mindustryApiVersion = "159" }
            mindustryMod { modMeta { name = "json-mod"; version = "1.0"; java = true } }
        """)
        val result = runner().withArguments("jar").build()
        assertTrue(result.task(":jar")?.outcome == TaskOutcome.SUCCESS)
        val entries = jarEntryNames()
        assertTrue(entries.contains("mod.json"), "mod.json must be packed even when useHJson says mod.hjson; got $entries")
    }


    @Test
    fun `the built jar carries the mod metadata`() {
        /*
        A jar without its metadata is not a mod the game can load, and nothing in this suite looked inside
        the jar. The format is pinned so the artifact name is deterministic: the default carries
        {build_count}, and inspecting a stale file under a hard-coded name is how a phantom bug was chased.
        */
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", """
            ${pluginSnippet()}
            mindustryModRoot {
                mindustryApiVersion = "159"
                build { format = "{name}-{version}" }
                download {
                    mindustryDownloadVersion = "147"
                    // No test may reach the network for the game itself.
                    mindustryDownloadUrl = "file:///nonexistent/mindustry-releases"
                }
            }
            mindustryMod { generateModMeta = true }
            modMeta { name = "meta-mod"; version = "1.0"; java = true }
        """)
        write("Meta.java", "package metamod;\npublic class Meta {}\n")

        val result = runner().withArguments("jar").build()

        assertTrue(result.task(":jar")?.outcome == TaskOutcome.SUCCESS, result.output)
        val jar = rootDir.resolve("build/libs/meta-mod-1.0-Jar.jar")
        assertTrue(
            jar.isFile,
            "expected ${jar.path}, found ${rootDir.resolve("build/libs").listFiles()?.toList()}",
        )
        val entries = ZipFile(jar).use { zip -> zip.entries().asSequence().map { it.name }.toList() }
        assertTrue(entries.contains("mod.json"), "the metadata must be packaged: $entries")
        val metadata = ZipFile(jar).use { zip ->
            zip.getInputStream(zip.getEntry("mod.json")).bufferedReader().readText()
        }
        assertTrue(
            metadata.contains("\"name\": \"meta-mod\""),
            "the jar must carry this mod's own metadata: $metadata",
        )
    }


    // ---- Icon renaming + warning ----

    private fun iconBuildScript(iconFile: String) = """
        ${pluginSnippet()}
        ${kotlinSnippet()}
        mindustryModRoot { mindustryApiVersion = "159" }
        mindustryMod {
            modMeta { name = "test-mod"; version = "1.0"; java = true }
            icon = file("$iconFile")
        }
    """.trimIndent()


    private fun buildJar(iconName: String, createIcon: Boolean = true): BuildResult {
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", iconBuildScript(iconName))
        if (createIcon) write(iconName, "dummy")
        return runner().withArguments("jar").build()
    }
}
