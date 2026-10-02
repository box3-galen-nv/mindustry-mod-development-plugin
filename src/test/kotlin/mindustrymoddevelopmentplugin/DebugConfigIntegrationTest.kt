package mindustrymoddevelopmentplugin

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Integration tests for the debug { } switch and its command-line overrides.
 */
internal class DebugConfigIntegrationTest : TestKitFixture() {


    @Test
    fun `debug properties are parsed by value`() {
        // `-PmindustryDebug=false` used to switch debugging *on* (hasProperty ignores the value),
        // and `-PmindustryDebugSuspend=false` made the game wait for a debugger forever.
        fun runMindustryDryRun(vararg args: String): String {
            write("settings.gradle.kts", """rootProject.name = "test"""")
            write("build.gradle.kts", """
                ${pluginSnippet()}
                ${kotlinSnippet()}
                mindustryModRoot { mindustryApiVersion = "159" }
                modMeta { name = "test-mod"; version = "1.0"; java = true }
            """)
            return runner().withArguments("runMindustry", "--dry-run", *args).build().output
        }

        assertTrue(!runMindustryDryRun("-PmindustryDebug=false").contains("Debugger socket on port"))
        assertTrue(!runMindustryDryRun("-PmindustryDebugSuspend=false").contains("waits for a debugger"))
        // A bare flag keeps working: it means "enabled".
        val enabled = runMindustryDryRun("-PmindustryDebug")
        assertTrue(enabled.contains("Debugger socket on port"), enabled)
        assertTrue(runMindustryDryRun("-PmindustryDebug=true", "-PmindustryDebugSuspend=true")
            .contains("waits for a debugger"))
    }


    @Test
    fun `the command line overrides the debug defaults from the DSL`() {
        // `debug { }` holds defaults; a property given for one run wins in both directions, which is
        // what lets CI force the socket off for a project that enables it.
        fun dryRun(vararg args: String): String {
            write("settings.gradle.kts", """rootProject.name = "test"""")
            write("build.gradle.kts", """
                ${pluginSnippet()}
                ${kotlinSnippet()}
                mindustryModRoot {
                    mindustryApiVersion = "159"
                    debug { enableDebug = true }
                }
                modMeta { name = "test-mod"; version = "1.0"; java = true }
            """)
            return runner().withArguments("runMindustry", "--dry-run", *args).build().output
        }

        assertTrue(dryRun().contains("Debugger socket on port"), "the DSL default opens the socket")
        assertTrue(
            !dryRun("-PmindustryDebug=false").contains("Debugger socket on port"),
            "a per-run property must be able to switch it off again",
        )
    }


    @Test
    fun `gradle properties can make a debug run the default`() {
        // There is no DSL switch anymore, so this is how a project asks for debugging on every run
        // it starts locally — the same property, just persisted instead of passed per invocation.
        write("gradle.properties", "mindustryDebug=true\n")
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", """
            ${pluginSnippet()}
            ${kotlinSnippet()}
            mindustryModRoot { mindustryApiVersion = "159" }
            modMeta { name = "test-mod"; version = "1.0"; java = true }
        """)

        val result = runner().withArguments("runMindustry", "--dry-run").build()

        assertTrue(result.output.contains("Debugger socket on port"), result.output)
        // ...and deleting the line switches it off again, without touching the build script.
        write("gradle.properties", "mindustryDebug=false\n")
        val off = runner().withArguments("runMindustry", "--dry-run").build()
        assertTrue(!off.output.contains("Debugger socket on port"), off.output)
    }
}
