package mindustrymoddevelopmentplugin

import java.io.File
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Integration tests for downloadMindustry: keeping, replacing and naming the game jar.
 */
internal class DownloadIntegrationTest : TestKitFixture() {


    // ---- downloadMindustry (the plugin's own downloader) ----

    @Test
    fun `single project mode deploys the mod into the game data dir`() {
        // Single-project mode: the root project itself is the mod, so `subprojects` is empty. This is
        // the regression test for `runMindustry` deploying nothing at all in that setup.
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", """
            ${pluginSnippet()}
            mindustryModRoot {
                mindustryApiVersion = "159"
                // Pinned: the default format carries {build_count}, so two separate Gradle invocations
                // (deploy, then runMindustry) would look for differently named artifacts.
                build { format = "{name}-{version}" }
                download {
                    mindustryDownloadVersion = "146"
                    mindustryDownloadUrl = "file:///nonexistent/mindustry-releases"
                }
                run { gameDataDir = file("data") }
                debug { enableRunLogging = false }
            }
            modMeta { name = "solo-mod"; version = "1.0"; java = true }
        """)
        // A junk jar at the download path keeps downloadMindustry from reaching the network; the game
        // JVM then fails to start, which is expected here and happens after the deployment.
        write("build/game/Mindustry-146.jar", "not a real jar")

        val packaged = runner().withArguments("deploy").build()
        assertTrue(packaged.task(":deploy")?.outcome == TaskOutcome.SUCCESS, packaged.output)

        val run = failResult("runMindustry")
        assertTrue(
            !run.output.contains("github.com"),
            "the suite must never fetch the real game:\n${run.output}",
        )

        val deployed = rootDir.resolve("data/mods").listFiles()?.map { it.name }.orEmpty()
        assertTrue(
            deployed.any { it.endsWith(".jar") },
            "the root project's own mod must be deployed in single-project mode, got: $deployed",
        )
    }


    @Test
    fun `an existing game jar is kept and needs no network`() {
        writeDataDirProject("146")
        // A jar the user put there: 14 bytes, so it cannot be a real game jar.
        write("build/game/Mindustry-146.jar", "not a real jar")

        // `--offline` is the proof: with the third-party download task this failed, because its own
        // offline predicate was evaluated before the "already downloaded" one.
        val result = runner().withArguments("downloadMindustry", "--offline").build()

        assertTrue(result.task(":downloadMindustry")?.outcome == TaskOutcome.SUCCESS, result.output)
        assertTrue(
            result.output.contains("does not look like a jar"),
            "the existing file must be reported, not replaced:\n${result.output}",
        )
        assertTrue(
            !result.output.contains("github.com"),
            "the suite must never fetch the real game:\n${result.output}",
        )
        assertTrue(
            rootDir.resolve("build/game/Mindustry-146.jar").readText() == "not a real jar",
            "the user's file must survive",
        )
    }


    @Test
    fun `changing the download version replaces the jar this build downloaded`() {
        // A fixed file name (no {version}) is the case where only the URL can reveal the change.
        val releases = testProjectDir.resolve("releases").toFile().apply { mkdirs() }
        fun publish(version: String, size: Int) {
            File(releases, "v$version").apply { mkdirs() }
            File(releases, "v$version/Mindustry.jar").writeBytes(ByteArray(size).also {
                it[0] = 0x50
                it[1] = 0x4B
            })
        }
        publish("146", 1_100_000)
        publish("147", 1_200_000)

        fun writeFixture(version: String) {
            write("settings.gradle.kts", """rootProject.name = "test"""")
            write("build.gradle.kts", """
                ${pluginSnippet()}
                mindustryModRoot {
                    mindustryApiVersion = "159"
                    download {
                        mindustryDownloadVersion = "$version"
                        mindustryDownloadUrl = "${releases.toURI()}"
                        mindustryGamePath = file("game/fixed.jar")
                    }
                }
            """)
        }

        writeFixture("146")
        val first = runner().withArguments("downloadMindustry").build()
        assertTrue(first.task(":downloadMindustry")?.outcome == TaskOutcome.SUCCESS, first.output)
        assertTrue(rootDir.resolve("game/fixed.jar").length() == 1_100_000L, "the 146 body is expected")

        // Same file name, same task — only the URL changed, and that must still re-download.
        writeFixture("147")
        val second = runner().withArguments("downloadMindustry").build()
        assertTrue(second.task(":downloadMindustry")?.outcome == TaskOutcome.SUCCESS, second.output)
        assertTrue(rootDir.resolve("game/fixed.jar").length() == 1_200_000L, "the 147 body is expected")

        // And a third run of the same version is up to date without any transfer.
        val third = runner().withArguments("downloadMindustry").build()
        assertTrue(third.task(":downloadMindustry")?.outcome == TaskOutcome.UP_TO_DATE, third.output)
    }

    // ---- runMindustry and packaging: no dependency by default ----

    /** Root + one mod subproject; [rootRun] is the root's `run { }` body. */


    @Test
    fun `default download filename works`() {
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", downloadAndRunScript())
        val result = tasksResult()
        assertTrue(result.task(":tasks")?.outcome == TaskOutcome.SUCCESS)
    }

    @Test
    fun `custom download filename with version placeholder works`() {
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", downloadAndRunScript(download = """mindustryDownloadFileName = "game-v{version}" """))
        val result = tasksResult()
        assertTrue(result.task(":tasks")?.outcome == TaskOutcome.SUCCESS)
    }

    @Test
    fun `invalid char in download filename throws error`() {
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", downloadAndRunScript(download = """mindustryDownloadFileName = "game/v1" """))
        val result = failResult("tasks", "--all")
        assertTrue(result.output.contains("Invalid character"))
    }

    @Test
    fun `space in download filename throws error`() {
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", downloadAndRunScript(download = """mindustryDownloadFileName = "game v1" """))
        val result = failResult("tasks", "--all")
        assertTrue(result.output.contains("Invalid character"))
    }

    @Test
    fun `chinese chars in download filename warns`() {
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", downloadAndRunScript(download = """mindustryDownloadFileName = "游戏-v{version}" """))
        val result = tasksResult()
        assertTrue(result.output.contains("CJK characters"))
    }
}
