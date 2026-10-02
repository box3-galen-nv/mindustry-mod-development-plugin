package mindustrymoddevelopmentplugin

import java.io.File
import java.nio.file.Path
import java.util.zip.ZipFile
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir

/**
 * The shared Gradle TestKit fixture for the integration tests.
 *
 * Holds the project-writing helpers and the fakes the per-topic test classes need; each of those extends
 * this and keeps only its own tests, so no fixture line is duplicated across them.
 */
internal abstract class TestKitFixture {

    @field:TempDir
    lateinit var testProjectDir: Path

    protected val rootDir: File get() = testProjectDir.toFile()

    // ---- game data dir (run.gameDataDir) ----

    /**
     * The `download { }` body for a test that must never, ever reach the network.
     *
     * A junk jar at the resolved path keeps `downloadMindustry` from fetching anything, and the `file://`
     * URL is the safety net: if the path ever stops matching (a default version change, say), the task
     * fails locally instead of downloading the real game and launching it during the suite. That is not
     * hypothetical — it happened once.
     */

    protected fun writeDataDirProject(
        version: String,
        rootRun: String = """gameDataDir = file("data")""",
    ) {
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", """
            ${pluginSnippet}
            mindustryModRoot {
                mindustryApiVersion = "159"
                download {
                    mindustryDownloadVersion = "$version"
                    // No test may reach the network for the game itself.
                    mindustryDownloadUrl = "file:///nonexistent/mindustry-releases"
                }
                run {
                    $rootRun
                }
            }
            modMeta { name = "test-mod"; version = "1.0"; java = true }
        """)
    }


    // ---- Helpers ----

    protected fun write(
        path: String,
        text: String,
    ) {
        rootDir.resolve(path).also { it.parentFile.mkdirs() }.writeText(text.trimIndent())
    }


    protected fun runner(): GradleRunner = GradleRunner.create()
        .withProjectDir(rootDir)
        .withPluginClasspath()


    protected fun tasksResult(vararg args: String) = runner()
        .withArguments("tasks", "--all", *args)
        .build()


    protected fun failResult(vararg args: String) = runner()
        .withArguments(*args)
        .buildAndFail()

    /**
     * Apply the plugin-under-test via `plugins` block so Kotlin DSL generates accessors.
     *
     * Deliberately declares **no repositories**: the plugin has to make the Mindustry API resolvable
     * on its own (GitHub release assets, no POM, no mirror), and a fixture that needs nothing else is
     * the proof. A Java-only mod therefore builds with an empty `repositories { }`.
     */

    protected val pluginSnippet = """plugins { id("io.github.box3-galen-nv.mindustry-mod-development-plugin") }"""

    /**
     * Apply Kotlin plugin via `buildscript` + `apply` instead of `plugins` block,
     * because [GradleRunner.withPluginClasspath] already puts kotlin-gradle-plugin on the classpath
     * and specifying a version in `plugins` causes a version conflict.
     */

    protected fun kotlinSnippet() = """
        buildscript {
            repositories { mavenCentral() }
            dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20") }
        }
        apply(plugin = "org.jetbrains.kotlin.jvm")

        // The one repository a Kotlin mod needs: kotlin-stdlib. The game API comes from the plugin.
        repositories { mavenCentral() }
    """.trimIndent()


    protected fun jarEntryNames(): Set<String> {
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

    protected fun fakeAndroidSdk(chattyD8: Boolean = false): File {
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


    protected fun androidBuildScript(extraBuild: String = "") = """
        ${pluginSnippet}
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
     * Lets the auto-installation path run end to end from a `file:` URL — no network, no real SDK.
     */

    protected fun downloadAndRunScript(download: String = "", run: String = "", debug: String = "") = """
        ${pluginSnippet}
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
}
