package mindustrymoddevelopmentplugin

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * Integration tests for which sources compile from the project root.
 */
internal class SourceLayoutIntegrationTest : TestKitFixture() {


    // ---- Source roots (IDE breakpoints + what actually gets compiled) ----

    @Test
    fun `kotlin sources compile from the project root`() {
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", """
            ${pluginSnippet}
            ${kotlinSnippet()}
            mindustryModRoot {
                mindustryApiVersion = "159"
                // Pin the artifact name so jarEntryNames() finds a stable file.
                build { format = "{name}-{version}" }
            }
            modMeta { name = "test-mod"; version = "1.0"; java = true }
        """)
        write("Root.kt", "package moda\n\nobject Root { const val VALUE = 7 }")

        val result = runner().withArguments("jar").build()
        assertTrue(result.task(":jar")?.outcome == TaskOutcome.SUCCESS, result.output)
        val entries = jarEntryNames()
        assertTrue(entries.contains("moda/Root.class"), "the root source must be compiled into the jar: $entries")
    }


    @Test
    fun `sources under build are not compiled`() {
        // The previous include was `**/*.kt` with no excludes, so a .kt file inside build/ was
        // compiled as well — a duplicate of a root source failed the build.
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", """
            ${pluginSnippet}
            ${kotlinSnippet()}
            mindustryModRoot {
                mindustryApiVersion = "159"
                // Pin the artifact name so jarEntryNames() finds a stable file.
                build { format = "{name}-{version}" }
            }
            modMeta { name = "test-mod"; version = "1.0"; java = true }
        """)
        write("Root.kt", "package moda\n\nobject Duplicated { const val VALUE = 1 }")
        write("build/generated/Duplicated.kt", "package moda\n\nobject Duplicated { const val VALUE = 2 }")

        val result = runner().withArguments("jar").build()
        assertTrue(
            result.task(":jar")?.outcome == TaskOutcome.SUCCESS,
            "a .kt file under build/ must not be compiled (duplicate declaration):\n${result.output}",
        )
    }


    @Test
    fun `sources in the project root and in src main kotlin both compile once`() {
        // The project dir overlaps src/main/kotlin, so overlapping source dirs must not compile
        // the same file twice (which would fail as a duplicate declaration).
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", """
            ${pluginSnippet}
            ${kotlinSnippet()}
            mindustryModRoot {
                mindustryApiVersion = "159"
                build { format = "{name}-{version}" }
            }
            modMeta { name = "test-mod"; version = "1.0"; java = true }
        """)
        write("AtRoot.kt", "package moda\n\nobject AtRoot { const val VALUE = 1 }")
        write("src/main/kotlin/InDefault.kt", "package moda\n\nobject InDefault { const val VALUE = 2 }")

        val result = runner().withArguments("jar").build()
        assertTrue(result.task(":jar")?.outcome == TaskOutcome.SUCCESS, result.output)
        val entries = jarEntryNames()
        assertTrue(entries.contains("moda/AtRoot.class"), entries.toString())
        assertTrue(entries.contains("moda/InDefault.class"), entries.toString())
    }


    @Test
    fun `java sources at the project root are compiled into the jar`() {
        // README promised Kotlin/Java, but only **/*.kt was ever compiled.
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", """
            ${pluginSnippet}
            ${kotlinSnippet()}
            mindustryModRoot {
                mindustryApiVersion = "159"
                // Pin the artifact name so jarEntryNames() finds a stable file.
                build { format = "{name}-{version}" }
            }
            modMeta { name = "test-mod"; version = "1.0"; java = true }
        """)
        write("JavaMod.java", "package moda;\n\npublic class JavaMod { public static int value() { return 7; } }")

        val result = runner().withArguments("jar").build()
        assertTrue(result.task(":jar")?.outcome == TaskOutcome.SUCCESS, result.output)
        val entries = jarEntryNames()
        assertTrue(entries.contains("moda/JavaMod.class"), "the Java source must be compiled into the jar: $entries")
    }


    // ---- KotlinCompile include filter  (regression guard for **/*.kt) ----

    @Test
    fun `compileKotlin does not compile gradle kts files`() {
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("mod.hjson", """name: '''test-mod'""""")
        write("build.gradle.kts", """
            ${pluginSnippet}
            ${kotlinSnippet()}
            mindustryMod { modMeta { name = "test-mod"; version = "1.0"; java = true } }
        """)
        write("Main.kt", """fun main() = println("hello")""")
        val result = runner().withArguments("compileKotlin").build()
        assertTrue(result.task(":compileKotlin")?.outcome == TaskOutcome.SUCCESS, "compileKotlin should succeed; .kts files must not be compiled as sources")
    }
}
