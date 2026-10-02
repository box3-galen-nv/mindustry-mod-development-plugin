package mindustrymoddevelopmentplugin

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Integration tests for modMeta, mod.json and buildModHJson.
 */
internal class ModMetadataIntegrationTest : TestKitFixture() {


    // ---- Metadata back-fill (generateModMeta) ----

    @Test
    fun `generateModMeta back-fills fields from existing mod hjson`() {
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("mod.hjson", """
            name: '''file-mod'''
            author: '''file-author'''
            version: '''2.0'''
            minGameVersion: 146
            hidden: true
            dependencies: ['dep-a']
        """)
        write("build.gradle.kts", """
            ${pluginSnippet}
            ${kotlinSnippet()}
            mindustryModRoot {
                mindustryApiVersion = "159"
                // These assertions are HJSON-specific; the default format is mod.json now.
                build { useHJson = true }
            }
            mindustryMod {
                generateModMeta = true
                modMeta {
                    name = "dsl-mod"
                    java = true
                }
            }
        """)
        val result = runner().withArguments("buildModHJson").build()
        assertTrue(result.task(":buildModHJson")?.outcome == TaskOutcome.SUCCESS)

        val generated = rootDir.resolve("build/mod.hjson")
        assertTrue(generated.isFile, "build/mod.hjson should be generated")
        val text = generated.readText()
        assertTrue(text.contains("name: '''dsl-mod'''"), "DSL name must win:\n$text")
        assertTrue(text.contains("author: '''file-author'''"), "author should be inherited:\n$text")
        assertTrue(text.contains("version: '''2.0'''"), "version should be inherited:\n$text")
        assertTrue(text.contains("minGameVersion: '''146'''"), "minGameVersion should be inherited:\n$text")
        assertTrue(text.contains("hidden: true"), "hidden should be inherited:\n$text")
        assertTrue(text.contains("dependencies: ['''dep-a''']"), "dependencies should be inherited:\n$text")
    }


    @Test
    fun `generateModMeta works with indented mod json as source`() {
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("mod.json", """
            {
              "name": "json-mod",
              "author": "json-author",
              "minGameVersion": "146",
              "hidden": true
            }
        """)
        write("build.gradle.kts", """
            ${pluginSnippet}
            ${kotlinSnippet()}
            mindustryModRoot {
                mindustryApiVersion = "159"
                build { useHJson = false }
            }
            mindustryMod {
                generateModMeta = true
                modMeta { name = "json-mod" }
            }
        """)
        val result = runner().withArguments("buildModHJson").build()
        assertTrue(result.task(":buildModHJson")?.outcome == TaskOutcome.SUCCESS)

        val text = rootDir.resolve("build/mod.json").readText()
        assertTrue(text.contains("\"author\": \"json-author\""), "author should be inherited from indented JSON:\n$text")
        assertTrue(text.contains("\"minGameVersion\": \"146\""), "minGameVersion should be inherited:\n$text")
        assertTrue(text.contains("\"hidden\": true"), "hidden should be inherited:\n$text")
    }


    // ---- plugin.json / plugin.hjson are also engine metadata file names ----

    @Test
    fun `project with only plugin hjson counts as a mod project`() {
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("plugin.hjson", """name: '''plugin-mod'''""")
        // No modMeta DSL at all: relies entirely on the name in plugin.hjson
        write("build.gradle.kts", """
            ${pluginSnippet}
            ${kotlinSnippet()}
            mindustryModRoot { mindustryApiVersion = "159" }
        """)
        val result = runner().withArguments("jar").build()
        assertTrue(result.task(":jar")?.outcome == TaskOutcome.SUCCESS)
        val entries = jarEntryNames()
        assertTrue(entries.contains("plugin.hjson"), "plugin.hjson must be packed; got $entries")
    }


    @Test
    fun `generateModMeta back-fills from the highest priority metadata file`() {
        write("settings.gradle.kts", """rootProject.name = "test"""")
        // The engine's findMeta() looks at mod.json first, so back-fill must take it too
        write("mod.json", """{ "name": "from-json", "author": "json-author" }""")
        write("mod.hjson", "name: '''from-hjson'''\nauthor: '''hjson-author'''\n")
        write("build.gradle.kts", """
            ${pluginSnippet}
            ${kotlinSnippet()}
            mindustryModRoot {
                mindustryApiVersion = "159"
                build { useHJson = true }
            }
            mindustryMod {
                generateModMeta = true
                modMeta { name = "dsl-mod" }
            }
        """)
        val result = runner().withArguments("buildModHJson").build()
        assertTrue(result.task(":buildModHJson")?.outcome == TaskOutcome.SUCCESS)

        val text = rootDir.resolve("build/mod.hjson").readText()
        assertTrue(text.contains("name: '''dsl-mod'''"), "DSL name must win:\n$text")
        assertTrue(text.contains("author: '''json-author'''"), "mod.json has engine priority over mod.hjson:\n$text")
    }


    @Test
    fun `modMeta can be configured as a top-level block`() {
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", """
            ${pluginSnippet}
            ${kotlinSnippet()}
            mindustryModRoot {
                mindustryApiVersion = "159"
                build { format = "{name}-{version}" }
            }
            modMeta {
                name = "TopLevelMod"
                version = "2.0"
                java = true
            }
        """)
        val result = runner().withArguments("jar").build()
        assertTrue(result.task(":jar")?.outcome == TaskOutcome.SUCCESS, result.output)

        // The jar name comes from ext.modMeta, so this proves the top-level block is wired in.
        val jars = rootDir.resolve("build/libs").listFiles().orEmpty().map { it.name }
        assertTrue(jars.any { it.startsWith("TopLevelMod-2.0") }, "jars: $jars")
    }


    @Test
    fun `buildModHJson writes mod json by default`() {
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", """
            ${pluginSnippet}
            ${kotlinSnippet()}
            mindustryModRoot { mindustryApiVersion = "159" }
            mindustryMod {
                generateModMeta = true
                modMeta { name = "test-mod"; java = true }
            }
        """)
        val result = runner().withArguments("buildModHJson").build()
        assertTrue(result.task(":buildModHJson")?.outcome == TaskOutcome.SUCCESS)

        val generated = rootDir.resolve("build/mod.json")
        assertTrue(generated.isFile, "the default metadata format is mod.json")
        assertTrue(!rootDir.resolve("build/mod.hjson").exists(), "mod.hjson must not be generated by default")
        assertTrue(generated.readText().contains("\"name\": \"test-mod\""), generated.readText())
    }
}
