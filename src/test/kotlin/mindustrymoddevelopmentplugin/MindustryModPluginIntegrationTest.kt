package mindustrymoddevelopmentplugin

import mindustrymoddevelopmentplugin.dsl.MindustryRunConfig
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MindustryModPluginIntegrationTest {

    @field:TempDir
    lateinit var testProjectDir: Path

    private val rootDir: File get() = testProjectDir.toFile()

    // =========================================================================
    //  game data dir (run.gameDataDir)
    // =========================================================================

    /** Root-project mod fixture; [rootRun] is the `run { }` body, which is where `gameDataDir` is set. */
    private fun writeDataDirProject(
        version: String,
        rootRun: String = """gameDataDir = file("data")""",
    ) {
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", """
            ${pluginSnippet()}
            mindustryModRoot {
                mindustryApiVersion = "159"
                download { mindustryDownloadVersion = "$version" }
                run {
                    $rootRun
                }
            }
            modMeta { name = "test-mod"; version = "1.0"; java = true }
        """)
    }

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
        // mindustryModsDir is gone: the path is always <gameDataDir>/mods.
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
        // The game itself honours that variable (v126+), so the plugin must follow the same directory
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
                download { mindustryDownloadVersion = "147" }
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
        // data/ lives next to build/, not inside it: wiping the game's saves and settings with a
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

    // =========================================================================
    //  downloadMindustry (the plugin's own downloader)
    // =========================================================================

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

        failResult("runMindustry")

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

    // =========================================================================
    //  runMindustry and packaging: no dependency by default
    // =========================================================================

    /** Root + one mod subproject; [rootRun] is the root's `run { }` body. */
    private fun writeMultiProjectFixture(rootRun: String) {
        write("settings.gradle.kts", """rootProject.name = "test"
            include("sub")""")
        write("build.gradle.kts", """
            ${pluginSnippet()}
            mindustryModRoot {
                mindustryApiVersion = "159"
                run {
                    gameDataDir = file("data")
                    $rootRun
                }
            }
        """)
        write("sub/build.gradle.kts", """
            ${pluginSnippet()}
            mindustryMod { modMeta { name = "sub-mod"; version = "1.0"; java = true } }
        """)
    }

    @Test
    fun `runMindustry does not build the mods by itself`() {
        writeMultiProjectFixture("")

        // The leading colon matters: a bare `runMindustry` also matches the subproject's own run task.
        val result = runner().withArguments(":runMindustry", "--dry-run").build()

        assertTrue(!result.output.contains(":sub:deploy"), "packaging must not be scheduled:\n${result.output}")
        assertTrue(result.output.contains(":clearMods"), result.output)
        assertTrue(result.output.contains(":downloadMindustry"), result.output)
    }

    @Test
    fun `a build script can wire packaging back in`() {
        writeMultiProjectFixture("")
        // The documented escape hatch: whoever wants the coupling writes it.
        write("build.gradle.kts", """
            ${pluginSnippet()}
            mindustryModRoot {
                mindustryApiVersion = "159"
                run { gameDataDir = file("data") }
            }
            tasks.named("runMindustry") { dependsOn(":sub:deploy") }
        """)

        val result = runner().withArguments(":runMindustry", "--dry-run").build()

        assertTrue(result.output.contains(":sub:deploy"), "the build script's dependency must apply:\n${result.output}")
    }

    // =========================================================================
    //  Helpers
    // =========================================================================

    private fun write(
        path: String,
        text: String,
    ) {
        rootDir.resolve(path).also { it.parentFile.mkdirs() }.writeText(text.trimIndent())
    }

    private fun runner(): GradleRunner = GradleRunner.create()
        .withProjectDir(rootDir)
        .withPluginClasspath()

    private fun tasksResult(vararg args: String) = runner()
        .withArguments("tasks", "--all", *args)
        .build()

    private fun failResult(vararg args: String) = runner()
        .withArguments(*args)
        .buildAndFail()

    /**
     * Apply the plugin-under-test via `plugins` block so Kotlin DSL generates accessors.
     *
     * Deliberately declares **no repositories**: the plugin has to make the Mindustry API resolvable
     * on its own (GitHub release assets, no POM, no mirror), and a fixture that needs nothing else is
     * the proof. A Java-only mod therefore builds with an empty `repositories { }`.
     */
    private fun pluginSnippet() = """plugins { id("io.github.box3-galen-nv.mindustry-mod-development-plugin") }"""

    /**
     * Apply Kotlin plugin via `buildscript` + `apply` instead of `plugins` block,
     * because [withPluginClasspath] already puts kotlin-gradle-plugin on the classpath
     * and specifying a version in `plugins` causes a version conflict.
     */
    private fun kotlinSnippet() = """
        buildscript {
            repositories { mavenCentral() }
            dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20") }
        }
        apply(plugin = "org.jetbrains.kotlin.jvm")

        // The one repository a Kotlin mod needs: kotlin-stdlib. The game API comes from the plugin.
        repositories { mavenCentral() }
    """.trimIndent()

    // =========================================================================
    //  d8 process handling
    // =========================================================================

    @Test
    fun `jarAndroid survives a d8 that writes more than a pipe buffer`() {
        // 20000 lines is far beyond the ~32-64 KiB pipe buffer. Reading the pipe only after waitFor()
        // deadlocked the child, and the build sat there until d8TimeoutMinutes; it is pinned to one
        // minute here so a regression fails instead of hanging the suite for 30.
        fakeAndroidSdk(chattyD8 = true)
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", androidBuildScript("d8TimeoutMinutes = 1"))
        write("mod.hjson", "name: '''test-mod'''\nversion: '''1.0'''\njava: true\n")

        val result = runner().withArguments("jarAndroid").build()

        assertTrue(
            result.task(":jarAndroid")?.outcome == TaskOutcome.SUCCESS,
            "a chatty d8 must not deadlock the build:\n" + result.output,
        )
    }

    // =========================================================================
    //  game API resolution (the release-asset route)
    // =========================================================================

    @Test
    fun `the game API coordinate resolves without any repository declared`() {
        // pluginSnippet() declares no repositories on purpose. Corrupting the asset name inside
        // MindustryApi makes the compiling fixtures fail, so the whole suite exercises the route; this
        // case pins the coordinate the compile classpath asks for.
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

    // =========================================================================
    //  IDEA run configurations (.run/) — the DSL -> file wiring
    // =========================================================================

    /** The generated Remote JVM Debug configuration, or null when it was not written. */
    private fun attachConfig(): String? =
        rootDir.resolve(".run/Mindustry-attach-debugger.run.xml").takeIf { it.isFile }?.readText()

    @Test
    fun `the generate task writes run configurations from the DSL port and is up to date after`() {
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", """
            ${pluginSnippet()}
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
            ${pluginSnippet()}
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
        // There is no DSL flag any more: the task only runs when it is asked for, and a project that
        // never wants it disables it like any other Gradle task.
        write(".run/Mindustry-attach-debugger.run.xml", "<component name=\"mine\" />\n")
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", """
            ${pluginSnippet()}
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
            ${pluginSnippet()}
            mindustryModRoot {
                mindustryApiVersion = "159"
                debug { debugPort = 5012 }
            }
        """)
        write("sub/build.gradle.kts", """
            ${pluginSnippet()}
            ${kotlinSnippet()}
            modMeta { name = "sub-mod"; version = "1.0"; java = true }
        """)
        val result = runner().withArguments("generateIdeaRunConfigs").build()
        assertTrue(result.task(":generateIdeaRunConfigs")?.outcome == TaskOutcome.SUCCESS, result.output)

        val attach = attachConfig()
        assertTrue(attach != null, "the root must generate the attach configuration")
        assertTrue(attach!!.contains("value=\"5012\""), "the subproject overwrote the root port:\n$attach")
    }

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
        // There is no DSL switch any more, so this is how a project asks for debugging on every run
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

    // =========================================================================
    //  Source roots (IDE breakpoints + what actually gets compiled)
    // =========================================================================

    @Test
    fun `kotlin sources compile from the project root`() {
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", """
            ${pluginSnippet()}
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
            ${pluginSnippet()}
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
            ${pluginSnippet()}
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
            ${pluginSnippet()}
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

    // =========================================================================
    //  Single-project mode  (bug fix regression)
    // =========================================================================

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

    // =========================================================================
    //  Root project tasks
    // =========================================================================

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

    // =========================================================================
    //  Multi-project mode
    // =========================================================================

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

    // =========================================================================
    //  Error: missing mod metadata
    // =========================================================================

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

    // =========================================================================
    //  Kotlin is optional — Java-only mods
    // =========================================================================

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

    // =========================================================================
    //  KotlinCompile include filter  (regression guard for **/*.kt)
    // =========================================================================

    @Test
    fun `compileKotlin does not compile gradle kts files`() {
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("mod.hjson", """name: '''test-mod'""""")
        write("build.gradle.kts", """
            ${pluginSnippet()}
            ${kotlinSnippet()}
            mindustryMod { modMeta { name = "test-mod"; version = "1.0"; java = true } }
        """)
        write("Main.kt", """fun main() = println("hello")""")
        val result = runner().withArguments("compileKotlin").build()
        assertTrue(result.task(":compileKotlin")?.outcome == TaskOutcome.SUCCESS, "compileKotlin should succeed; .kts files must not be compiled as sources")
    }

    // =========================================================================
    //  Icon renaming + warning
    // =========================================================================

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

    private fun jarEntryNames(): Set<String> {
        val libsDir = rootDir.resolve("build/libs")
        val jarFile = libsDir.listFiles()?.firstOrNull { it.name.endsWith(".jar") }
            ?: error("No jar found in build/libs")
        return ZipFile(jarFile).entries().asSequence().map { it.name }.toSet()
    }

    /**
     * Build a minimal usable fake Android SDK: `platforms/android-30/android.jar` plus
     * `build-tools/34.0.0/d8` (a shell script that writes an **empty zip** to the `--output` argument).
     * This lets `jarAndroid` really run in tests (deploy has to unpack it) without a real SDK.
     */
    private fun fakeAndroidSdk(chattyD8: Boolean = false): File {
        val sdk = rootDir.resolve("sdk")
        sdk.resolve("platforms/android-30").mkdirs()
        sdk.resolve("platforms/android-30/android.jar").writeText("fake android.jar")
        val buildTools = sdk.resolve("build-tools/34.0.0")
        buildTools.mkdirs()
        val d8 = buildTools.resolve("d8")
        // EOCD record: 'PK\005\006' + 18 zero bytes = a valid empty zip.
        // The real d8 writes a jar/zip to --output and deploy unpacks it, so plain text will not do.
        d8.writeText(
            """
            #!/bin/sh
            out=""
            prev=""
            for a in "${'$'}@"; do
              if [ "${'$'}prev" = "--output" ]; then out="${'$'}a"; fi
              prev="${'$'}a"
            done
            printf 'PK\005\006\000\000\000\000\000\000\000\000\000\000\000\000\000\000\000\000\000\000' > "${'$'}out"
              ${if (chattyD8) "i=0; while [ ${'$'}i -lt 20000 ]; do echo 'd8: a very chatty warning line that must not deadlock the pipe'; i=${'$'}((i+1)); done; i=0" else "# silent"}
            exit 0
            """.trimIndent() + "\n"
        )
        d8.setExecutable(true)
        return sdk
    }

    private fun androidBuildScript(extraBuild: String = "") = """
        ${pluginSnippet()}
        ${kotlinSnippet()}
        mindustryModRoot {
            mindustryApiVersion = "159"
            build {
                androidSdkDir = file("sdk")
                // Pin the artifact name (drop {build_count}): otherwise every build produces a
                // differently named file, no task can ever be UP-TO-DATE and declared inputs/outputs cannot be verified.
                format = "{name}-{version}"
                $extraBuild
            }
        }
        mindustryMod { modMeta { name = "test-mod"; version = "1.0"; java = true } }
    """.trimIndent()

    /**
     * Builds a fake command-line tools archive whose `sdkmanager` creates the `platforms` /
     * `build-tools` layout the plugin expects (with a fake `d8` that writes a valid empty zip).
     *
     * Lets the auto-install path run end to end from a `file:` URL — no network, no real SDK.
     */
    private fun fakeCommandLineToolsArchive(): String {
        // Where the stub records its arguments; a test that cares reads this file.
        val sdkCalls = rootDir.resolve("sdkmanager-calls.txt")
        val fakeD8 = rootDir.resolve("fake-d8").apply {
            writeText(
                """
                #!/bin/sh
                out=""
                prev=""
                for a in "${'$'}@"; do
                  if [ "${'$'}prev" = "--output" ]; then out="${'$'}a"; fi
                  prev="${'$'}a"
                done
                printf 'PK\005\006\000\000\000\000\000\000\000\000\000\000\000\000\000\000\000\000\000\000' > "${'$'}out"
                """.trimIndent() + "\n"
            )
            setExecutable(true)
        }

        val sdkManager = """
            #!/bin/sh
            root=""
            for a in "${'$'}@"; do
              case "${'$'}a" in --sdk_root=*) root="${'$'}{a#--sdk_root=}" ;; esac
            done
            echo "ARGS ${'$'}*" >> "${sdkCalls.absolutePath}"
            case "${'$'}*" in
              *--licenses*) : ;;
              *)
                mkdir -p "${'$'}root/platforms/android-30" "${'$'}root/build-tools/34.0.0"
                : > "${'$'}root/platforms/android-30/android.jar"
                cp "${fakeD8.absolutePath}" "${'$'}root/build-tools/34.0.0/d8"
                chmod +x "${'$'}root/build-tools/34.0.0/d8"
                ;;
            esac
            while read -r line; do :; done
            exit 0
        """.trimIndent() + "\n"

        val zip = rootDir.resolve("commandlinetools.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            out.putNextEntry(ZipEntry("cmdline-tools/bin/sdkmanager"))
            out.write(sdkManager.toByteArray())
            out.closeEntry()
        }
        return zip.toURI().toString()
    }

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
    // =========================================================================
    //  Download file name validation
    // =========================================================================

    /**
     * Build script with the download and run sub-configs.
     *
     * Both snippets are passed separately so each lands in the block that owns its property
     * (`download { }` for the game jar and the Android SDK, `run { }` for the data dir and deploy, `debug { }` for
     * logging and debugging).
     */
    private fun downloadAndRunScript(download: String = "", run: String = "", debug: String = "") = """
        ${pluginSnippet()}
        mindustryModRoot {
            mindustryApiVersion = "159"
            download {
                $download
            }
            run {
                $run
            }
            debug {
                $debug
            }
        }
    """.trimIndent()

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
    // =========================================================================
    //  enableRunLogging (debug sub-config)
    // =========================================================================

    @Test
    fun `enableRunLogging false configures runMindustry without error`() {
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", downloadAndRunScript(debug = """enableRunLogging = false"""))
        val result = tasksResult()
        assertTrue(result.task(":tasks")?.outcome == TaskOutcome.SUCCESS)
        assertTrue(result.output.contains("runMindustry"))
    }
    // =========================================================================
    //  build.androidSdkDir (jarAndroid SDK path)
    // =========================================================================

    @Test
    fun `build androidSdkDir configures jarAndroid without error`() {
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", """
            ${pluginSnippet()}
            ${kotlinSnippet()}
            mindustryModRoot {
                mindustryApiVersion = "159"
                build { androidSdkDir = file("sdk") }
            }
            mindustryMod { modMeta { name = "test-mod"; version = "1.0"; java = true } }
        """)
        val result = tasksResult()
        assertTrue(result.task(":tasks")?.outcome == TaskOutcome.SUCCESS)
        assertTrue(result.output.contains("jarAndroid"))
    }
    @Test
    fun `build d8Args configures jarAndroid without error`() {
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", """
            ${pluginSnippet()}
            ${kotlinSnippet()}
            mindustryModRoot {
                mindustryApiVersion = "159"
                build { d8Args = listOf("--no-desugaring", "--release") }
            }
            mindustryMod { modMeta { name = "test-mod"; version = "1.0"; java = true } }
        """)
        val result = tasksResult()
        assertTrue(result.task(":tasks")?.outcome == TaskOutcome.SUCCESS)
        assertTrue(result.output.contains("jarAndroid"))
    }

    @Test
    fun `build d8TimeoutMinutes configures jarAndroid without error`() {
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", """
            ${pluginSnippet()}
            ${kotlinSnippet()}
            mindustryModRoot {
                mindustryApiVersion = "159"
                build { d8TimeoutMinutes = 5L }
            }
            mindustryMod { modMeta { name = "test-mod"; version = "1.0"; java = true } }
        """)
        val result = tasksResult()
        assertTrue(result.task(":tasks")?.outcome == TaskOutcome.SUCCESS)
        assertTrue(result.output.contains("jarAndroid"))
    }

    // =========================================================================
    //  Metadata back-fill (generateModMeta)
    // =========================================================================

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
            ${pluginSnippet()}
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
            ${pluginSnippet()}
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

    // =========================================================================
    //  mod.json alone must still be packed into the jar
    // =========================================================================

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

    // =========================================================================
    //  plugin.json / plugin.hjson are also engine metadata file names
    // =========================================================================

    @Test
    fun `project with only plugin hjson counts as a mod project`() {
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("plugin.hjson", """name: '''plugin-mod'''""")
        // No modMeta DSL at all: relies entirely on the name in plugin.hjson
        write("build.gradle.kts", """
            ${pluginSnippet()}
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
            ${pluginSnippet()}
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

    // =========================================================================
    //  clearMods safety: never delete when the name cannot be resolved
    // =========================================================================

    private fun writeMultiProjectWithMod(modBuildScript: String) {
        write("settings.gradle.kts", """
            rootProject.name = "test"
            include("sub")
        """)
        write("build.gradle.kts", """
            ${pluginSnippet()}
            mindustryModRoot {
                mindustryApiVersion = "159"
                run { gameDataDir = file("data") }
            }
        """)
        write("sub/build.gradle.kts", """
            ${pluginSnippet()}
            ${kotlinSnippet()}
            $modBuildScript
        """)
    }

    @Test
    fun `clearMods resolves the name from mod hjson and keeps other mods`() {
        // The name lives only in mod.hjson (no DSL name) — exactly the workflow back-fill supports
        writeMultiProjectWithMod("""mindustryMod { modMeta { java = true } }""")
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
        writeMultiProjectWithMod("""mindustryMod { modMeta { java = true } }""")
        val modsDir = rootDir.resolve("data/mods").also { it.mkdirs() }
        val foreign = modsDir.resolve("[${MindustryRunConfig.DEFAULT_DEPLOY_TAG}]other-mod-1.0-Jar.jar").also { it.writeText("other") }

        val result = runner().withArguments("clearMods").build()
        assertTrue(result.task(":clearMods")?.outcome == TaskOutcome.SUCCESS)
        assertTrue(foreign.exists(), "无法确定模组名时必须什么都不删")
        assertTrue(result.output.contains("Skipping mod cleanup"), "应给出跳过清理的警告:\n${result.output}")
    }

    // =========================================================================
    //  jarAndroid input declaration: a changed jar must not leave a stale dex
    // =========================================================================

    @Test
    fun `jarAndroid is up to date when nothing changed and re-runs after a change`() {
        fakeAndroidSdk()
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", androidBuildScript())
        write("mod.hjson", "name: '''test-mod'''\nversion: '''1.0'''\njava: true\n")

        val first = runner().withArguments("jarAndroid").build()
        assertTrue(first.task(":jarAndroid")?.outcome == TaskOutcome.SUCCESS, "首次应真正执行")

        val second = runner().withArguments("jarAndroid").build()
        assertTrue(
            second.task(":jarAndroid")?.outcome == TaskOutcome.UP_TO_DATE,
            "无改动时应为 UP-TO-DATE，实际 ${second.task(":jarAndroid")?.outcome}",
        )

        // Change the metadata packed into the jar → jar is rebuilt → jarAndroid must re-run (otherwise the dex is stale)
        write("mod.hjson", "name: '''test-mod'''\nversion: '''2.0'''\njava: true\n")
        val third = runner().withArguments("jarAndroid").build()
        assertTrue(
            third.task(":jarAndroid")?.outcome == TaskOutcome.SUCCESS,
            "输入 jar 变化后必须重跑，实际 ${third.task(":jarAndroid")?.outcome}",
        )
    }

    // =========================================================================
    //  deploy keeps intermediate artifacts → jar / jarAndroid stay incremental
    // =========================================================================

    @Test
    fun `deploy keeps intermediate jars so jar and jarAndroid stay up to date`() {
        fakeAndroidSdk()
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", androidBuildScript())
        write("mod.hjson", "name: '''test-mod'''\nversion: '''1.0'''\njava: true\n")

        val first = runner().withArguments("deploy").build()
        assertTrue(first.task(":deploy")?.outcome == TaskOutcome.SUCCESS)

        val libs = rootDir.resolve("build/libs")
        val names = libs.listFiles()?.map { it.name }.orEmpty()
        assertTrue(names.any { it.endsWith("-Jar.jar") }, "桌面 jar 应保留在 libs 中，实际 $names")
        assertTrue(names.any { it.endsWith("-Android.jar") }, "Android jar 应保留在 libs 中，实际 $names")

        val second = runner().withArguments("deploy").build()
        assertTrue(
            second.task(":jar")?.outcome == TaskOutcome.UP_TO_DATE,
            "保留中间产物后 jar 应可增量，实际 ${second.task(":jar")?.outcome}",
        )
        assertTrue(
            second.task(":jarAndroid")?.outcome == TaskOutcome.UP_TO_DATE,
            "保留中间产物后 jarAndroid 应可增量，实际 ${second.task(":jarAndroid")?.outcome}",
        )
    }

    // =========================================================================
    //  buildModHJson up-to-date checks
    // =========================================================================

    @Test
    fun `buildModHJson is skipped when generateModMeta is false`() {
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", """
            ${pluginSnippet()}
            ${kotlinSnippet()}
            mindustryModRoot { mindustryApiVersion = "159" }
            mindustryMod { modMeta { name = "test-mod"; java = true } }
        """)
        val result = runner().withArguments("buildModHJson").build()
        assertTrue(
            result.task(":buildModHJson")?.outcome == TaskOutcome.SKIPPED,
            "未开启生成时应 SKIPPED 而不是空转执行，实际 ${result.task(":buildModHJson")?.outcome}",
        )
    }

    @Test
    fun `buildModHJson is up to date until the DSL changes`() {
        write("settings.gradle.kts", """rootProject.name = "test"""")
        val script = { version: String ->
            """
            ${pluginSnippet()}
            ${kotlinSnippet()}
            mindustryModRoot {
                mindustryApiVersion = "159"
                build { useHJson = true }
            }
            mindustryMod {
                generateModMeta = true
                modMeta { name = "test-mod"; version = "$version"; java = true }
            }
            """.trimIndent()
        }
        write("build.gradle.kts", script("1.0"))

        val first = runner().withArguments("buildModHJson").build()
        assertTrue(first.task(":buildModHJson")?.outcome == TaskOutcome.SUCCESS)

        val second = runner().withArguments("buildModHJson").build()
        assertTrue(
            second.task(":buildModHJson")?.outcome == TaskOutcome.UP_TO_DATE,
            "DSL 未变时应 UP-TO-DATE，实际 ${second.task(":buildModHJson")?.outcome}",
        )

        write("build.gradle.kts", script("2.0"))
        val third = runner().withArguments("buildModHJson").build()
        assertTrue(
            third.task(":buildModHJson")?.outcome == TaskOutcome.SUCCESS,
            "DSL 变化后必须重新生成，实际 ${third.task(":buildModHJson")?.outcome}",
        )
        assertTrue(rootDir.resolve("build/mod.hjson").readText().contains("version: '''2.0'''"))
    }

    @Test
    fun `modMeta can be configured as a top-level block`() {
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", """
            ${pluginSnippet()}
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
            ${pluginSnippet()}
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

    // =========================================================================
    //  Android SDK auto-download (download.androidSdkAutoDownload)
    // =========================================================================

    @Test
    fun `androidSdkExtraArgs reach the sdkmanager command line`() {
        // sdkmanager has no repository-URL flag, so a mirror has to be passed as a proxy. This proves
        // the DSL value really lands in the child process arguments, not just in the task inputs.
        val toolsUrl = fakeCommandLineToolsArchive()
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", """
            ${pluginSnippet()}
            ${kotlinSnippet()}
            mindustryModRoot {
                mindustryApiVersion = "159"
                build {
                    androidSdkDir = file("sdk")
                }
                download {
                    androidSdkAutoDownload = true
                    androidSdkDownloadUrl = "$toolsUrl"
                    // The stub below creates exactly these two, so the post-install check passes.
                    androidSdkDownloadPackages = listOf("platforms;android-30", "build-tools;34.0.0")
                    androidSdkExtraArgs = listOf(
                        "--proxy=http",
                        "--proxy_host=mirror.example",
                        "--proxy_port=80",
                    )
                }
            }
            mindustryMod { modMeta { name = "test-mod"; version = "1.0"; java = true } }
        """)
        write("mod.hjson", "name: '''test-mod'''\nversion: '''1.0'''\njava: true\n")

        val result = runner().withArguments("jarAndroid").build()

        assertTrue(result.task(":jarAndroid")?.outcome == TaskOutcome.SUCCESS, result.output)
        val calls = rootDir.resolve("sdkmanager-calls.txt").readLines()
        assertTrue(calls.size >= 2, "licence and package runs, got: $calls")
        calls.forEach { call ->
            assertTrue(call.contains("--proxy_host=mirror.example"), call)
            assertTrue(call.contains("--proxy_port=80"), call)
        }
    }

    @Test
    fun `jarAndroid installs the sdk when the configured directory is empty`() {
        val toolsUrl = fakeCommandLineToolsArchive()
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", """
            ${pluginSnippet()}
            ${kotlinSnippet()}
            mindustryModRoot {
                mindustryApiVersion = "159"
                build {
                    androidSdkDir = file("sdk")
                }
                download {
                    // Off by default; the point of this test is the install itself.
                    androidSdkAutoDownload = true
                    androidSdkDownloadUrl = "$toolsUrl"
                    androidSdkDownloadPackages = listOf("platforms;android-30", "build-tools;34.0.0")
                }
            }
            mindustryMod { modMeta { name = "test-mod"; version = "1.0"; java = true } }
        """)
        write("mod.hjson", "name: '''test-mod'''\nversion: '''1.0'''\njava: true\n")
        // The configured directory exists but is empty — the trigger case.
        rootDir.resolve("sdk").mkdirs()

        val result = runner().withArguments("jarAndroid").build()

        assertTrue(result.task(":jarAndroid")?.outcome == TaskOutcome.SUCCESS, result.output)
        assertTrue(
            result.output.contains("downloading the command-line tools"),
            "the build log should mention installing the SDK:\n${result.output}"
        )
        assertTrue(rootDir.resolve("sdk/cmdline-tools/latest/bin/sdkmanager").isFile, "tools should be unpacked")
        assertTrue(rootDir.resolve("sdk/platforms/android-30/android.jar").isFile, "platform should be installed")
        assertTrue(rootDir.resolve("sdk/build-tools/34.0.0/d8").isFile, "build-tools should be installed")

        val androidJar = rootDir.resolve("build/libs").listFiles()
            .orEmpty().firstOrNull { it.name.endsWith("-Android.jar") }
        assertTrue(androidJar != null, "the Android jar should be produced after the install")
    }

    @Test
    fun `auto download can be turned off and the old failure comes back`() {
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", """
            ${pluginSnippet()}
            ${kotlinSnippet()}
            mindustryModRoot {
                mindustryApiVersion = "159"
                build {
                    androidSdkDir = file("sdk")
                }
                download {
                    // This is the default now; kept explicit because that is what is under test.
                    androidSdkAutoDownload = false
                    // Deliberately unreachable: a wrong implementation would try to fetch this.
                    androidSdkDownloadUrl = "file:///nonexistent/commandlinetools.zip"
                }
            }
            mindustryMod { modMeta { name = "test-mod"; version = "1.0"; java = true } }
        """)
        rootDir.resolve("sdk").mkdirs()

        val result = failResult("jarAndroid")
        assertTrue(
            result.output.contains("No android.jar found"),
            "with auto-download off the empty SDK must fail loudly:\n${result.output}"
        )
        assertTrue(!result.output.contains("downloading the command-line tools"), "no download may happen")
    }
}
