package mindustrymoddevelopmentplugin

import mindustrymoddevelopmentplugin.game.MindustryApi
import java.util.zip.ZipFile
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * Integration tests for the tasks each project gets, and the guards around them.
 */
internal class PluginWiringIntegrationTest : TestKitFixture() {


    // ---- game API resolution (the release-asset route) ----

    @Test
    fun `the game API coordinate resolves without any repository declared`() {
        /*
        The pluginSnippet() fixture declares no repositories on purpose. Corrupting the asset name inside
        MindustryApi makes the compiling fixtures fail, so the whole suite exercises the route; this
        case pins the coordinate the compile classpath asks for.
        */
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", """
            ${pluginSnippet()}
            ${kotlinSnippet()}
            mindustryModRoot { mindustryApiVersion = "159" }
            modMeta { name = "test-mod"; version = "1.0"; java = true }
        """)

        val result = runner().withArguments("dependencies", "--configuration", "compileClasspath").build()

        assertTrue(
            result.output.contains("Anuken:Mindustry:v159"),
            "compileClasspath must contain the asset coordinate:\n" + result.output,
        )
    }

    // ---- IDEA run configurations (.run/) — the DSL -> file wiring ----

    /** The generated Remote JVM Debug configuration, or null when it was not written. */


    // ---- Single-project mode  (bug fix regression) ----

    @Test
    fun `single project mode creates jar deploy and buildModHJson tasks`() {
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", """
            ${pluginSnippet()}
            ${kotlinSnippet()}
            mindustryModRoot { mindustryApiVersion = "159" }
            mindustryMod { modMeta { name = "test-mod"; version = "1.0"; java = true } }
        """)
        val result = tasksResult()
        assertTrue(result.output.contains("deploy"), "Missing deploy task")
        assertTrue(result.output.contains("jarAndroid"), "Missing jarAndroid task")
        assertTrue(result.output.contains("buildModHJson"), "Missing buildModHJson task")
    }


    // ---- Root project tasks ----

    @Test
    fun `root project creates downloadMindustry and runMindustry tasks`() {
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", """
            ${pluginSnippet()}
            mindustryModRoot { mindustryApiVersion = "159" }
        """)
        val result = tasksResult()
        assertTrue(result.output.contains("downloadMindustry"), "Missing downloadMindustry")
        assertTrue(result.output.contains("runMindustry"), "Missing runMindustry")
    }


    // ---- Multi-project mode ----

    @Test
    fun `subproject has downloadMindustry and runMindustry tasks`() {
        write("settings.gradle.kts", """
            rootProject.name = "test"
            include("sub")
        """)
        write("build.gradle.kts", "${pluginSnippet()}")
        write("sub/build.gradle.kts", """
            ${pluginSnippet()}
            ${kotlinSnippet()}
            mindustryMod { modMeta { name = "sub-mod"; version = "1.0"; java = true } }
        """)
        val result = runner()
            .withProjectDir(rootDir)
            .withPluginClasspath()
            .withArguments(":sub:tasks", "--all")
            .build()
        assertTrue(result.output.contains("downloadMindustry"), "Sub missing downloadMindustry")
        assertTrue(result.output.contains("runMindustry"), "Sub missing runMindustry")
    }


    @Test
    fun `subproject can configure modMeta as a top-level block`() {
        // Mirrors the multi-project example in the README: the subproject owns its metadata.
        write("settings.gradle.kts", """
            rootProject.name = "test"
            include("sub")
        """)
        write("build.gradle.kts", """
            ${pluginSnippet()}
            mindustryModRoot { mindustryApiVersion = "159" }
        """)
        write("sub/build.gradle.kts", """
            ${pluginSnippet()}
            ${kotlinSnippet()}
            modMeta { name = "sub-mod"; version = "3.0"; java = true }
            mindustryMod { generateModMeta = true }
        """)
        val result = runner().withArguments("tasks", "--all").build()
        assertTrue(result.output.contains("sub:deploy"), "the subproject must be detected as a mod: ${result.output}")
        assertTrue(result.output.contains("sub:buildModHJson"), "expected buildModHJson in sub")
    }


    @Test
    fun `multi project configures subproject but not root for mod tasks`() {
        write("settings.gradle.kts", """
            rootProject.name = "test"
            include("sub")
        """)
        write("build.gradle.kts", """
            ${pluginSnippet()}
            mindustryModRoot { mindustryApiVersion = "159" }
        """)
        write("sub/build.gradle.kts", """
            ${pluginSnippet()}
            ${kotlinSnippet()}
            mindustryMod { modMeta { name = "sub-mod"; version = "1.0"; java = true } }
        """)
        val result = tasksResult()
        assertTrue(result.output.contains("downloadMindustry"), "Root missing downloadMindustry")
        assertTrue(result.output.contains("sub:deploy"), "Sub should have deploy")
        assertTrue(!result.output.contains("\ndeploy"), "Root should not have deploy")
    }


    // ---- Error: missing mod metadata ----

    @Test
    fun `jar fails with helpful error when no mod metadata`() {
        write("settings.gradle.kts", """
            rootProject.name = "test"
            include("sub")
        """)
        write("build.gradle.kts", "${pluginSnippet()}")
        write("sub/build.gradle.kts", """
            ${pluginSnippet()}
            ${kotlinSnippet()}
            mindustryMod { modMeta { java = true } }
        """)
        val result = failResult(":sub:jar")
        assertTrue(result.output.contains("No mod metadata found"), "Expected helpful error, got: ${result.output.take(500)}")
    }


    // ---- Kotlin is optional — Java-only mods ----

    @Test
    fun `java only mod has no kotlin plugin and still builds`() {
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", """
            ${pluginSnippet()}
            mindustryModRoot {
                mindustryApiVersion = "159"
                // Pin the artifact name so jarEntryNames() finds a stable file.
                build { format = "{name}-{version}" }
            }
            modMeta { name = "test-mod"; version = "1.0"; java = true }
        """)
        write("JavaOnly.java", "package moda;\n\npublic class JavaOnly { public static int value() { return 7; } }")

        val result = runner().withArguments("jar").build()
        assertTrue(result.task(":jar")?.outcome == TaskOutcome.SUCCESS, result.output)
        val entries = jarEntryNames()
        assertTrue(
            entries.contains("moda/JavaOnly.class"),
            "a Java-only mod must compile without the kotlin(\"jvm\") plugin: $entries",
        )
    }


    @Test
    fun `kotlin sources without the kotlin plugin fail with a helpful error`() {
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", """
            ${pluginSnippet()}
            mindustryModRoot { mindustryApiVersion = "159" }
            modMeta { name = "test-mod"; version = "1.0"; java = true }
        """)
        write("Main.kt", """fun main() = println("hello")""")

        val result = failResult("tasks", "--all")
        assertTrue(
            result.output.contains("Main.kt"),
            "the error should name the offending Kotlin source: ${result.output.take(600)}",
        )
        assertTrue(
            result.output.contains("kotlin(\"jvm\") plugin is not applied"),
            "the error should say which plugin is missing: ${result.output.take(600)}",
        )
    }

    @Test
    fun `a root project that is a mod does not package its subprojects sources`() {
        // Multi-project mode puts the mods under src/<name>/, which is inside the root project's
        // directory. The root used to compile those sources into its own jar.
        write("settings.gradle.kts", """rootProject.name = "test"
            include("sub")""")
        write("build.gradle.kts", """
            ${pluginSnippet()}
            mindustryModRoot { mindustryApiVersion = "159" }
            modMeta { name = "root-mod"; version = "1.0"; java = true }
        """)
        write("RootCls.java", "package rootonly;\npublic class RootCls {}\n")
        write("sub/build.gradle.kts", """
            ${pluginSnippet()}
            modMeta { name = "sub-mod"; version = "1.0"; java = true }
        """)
        write("sub/SubCls.java", "package subonly;\npublic class SubCls {}\n")

        val result = runner().withArguments(":jar").build()

        assertTrue(result.task(":jar")?.outcome == TaskOutcome.SUCCESS, result.output)
        val jar = rootDir.resolve("build/libs").listFiles()?.firstOrNull { it.name.endsWith(".jar") }
        assertTrue(jar != null, "the root's jar must exist:\n${result.output}")
        val entries = ZipFile(jar).use { zip -> zip.entries().asSequence().map { it.name }.toList() }
        assertTrue(entries.any { it.startsWith("rootonly/") }, "the root's own source belongs in it: $entries")
        assertTrue(!entries.any { it.startsWith("subonly/") }, "the subproject's source does not: $entries")
    }


    @Test
    fun `root-only settings on a subproject are reported instead of being ignored`() {
        write("settings.gradle.kts", """rootProject.name = "test"
            include("sub")""")
        write("build.gradle.kts", """
            ${pluginSnippet()}
            mindustryModRoot { mindustryApiVersion = "159" }
        """)
        write("sub/build.gradle.kts", """
            ${pluginSnippet()}
            mindustryModRoot {
                mindustryApiVersion = "147"
                build { jarSuffix = "-Desk" }
            }
            modMeta { name = "sub-mod"; version = "1.0"; java = true }
        """)

        val result = runner().withArguments("tasks").build()

        assertTrue(
            result.output.contains("Ignoring") && result.output.contains("mindustryApiVersion"),
            "setting the API version on a subproject must be reported:\n${result.output}",
        )
        assertTrue(
            result.output.contains("build.jarSuffix"),
            "the ignored build setting must be named:\n${result.output}",
        )
    }

    @Test
    fun `a subproject's own deploy task is left alone instead of breaking clearMods`() {
        write("settings.gradle.kts", """rootProject.name = "test"
            include("sub")""")
        write("build.gradle.kts", pluginSnippet())
        write("sub/build.gradle.kts", """
            ${pluginSnippet()}
            // A deployment of its own, say to a server — not the mod packaging task.
            tasks.register("deploy") { doLast { println("own deploy") } }
            modMeta { name = "sub-mod"; version = "1.0"; java = true }
        """)

        val result = runner().withArguments("clearMods").build()

        assertTrue(result.task(":clearMods")?.outcome == TaskOutcome.SUCCESS, result.output)
        assertTrue(
            result.output.contains("already has a 'deploy' task"),
            "the conflict must be reported, not guessed at:\n${result.output}",
        )
    }
    /**
     * Standalone `d8` stub: writes a valid (empty) zip to its `--output` argument.
     *
     * Used by the "configured d8, no SDK involvement" test. A real d8 writes a jar there, so the stub has
     * to produce something `zipTree` can read later.
     */
}
