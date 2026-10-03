package mindustrymoddevelopmentplugin

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * Integration tests for jarAndroid: d8, SDK discovery and installation.
 */
internal class AndroidSdkIntegrationTest : TestKitFixture() {


    // ---- d8 process handling ----

    @Test
    fun `jarAndroid survives a d8 that writes more than a pipe buffer`() {
        /*
        20000 lines is far beyond the ~32-64 KiB pipe buffer. Reading the pipe only after waitFor()
        deadlocked the child, and the build sat there until d8TimeoutMinutes; it is pinned to one
        minute here so a regression fails instead of hanging the suite for 30.
        */
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

    // ---- build.androidSdkDir (jarAndroid SDK path) ----

    @Test
    fun `build androidSdkDir configures jarAndroid without error`() {
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", """
            ${pluginSnippet}
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
            ${pluginSnippet}
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
            ${pluginSnippet}
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


    // ---- Android SDK auto-download (download.androidSdkAutoDownload) ----

    @Test
    fun `androidSdkExtraArgs reach the sdkmanager command line`() {
        // The sdkmanager tool has no repository-URL flag, so a mirror has to be passed as a proxy. This proves
        // the DSL value really lands in the child process arguments, not just in the task inputs.
        val toolsUrl = fakeCommandLineToolsArchive()
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", """
            ${pluginSnippet}
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
            ${pluginSnippet}
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
            ${pluginSnippet}
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
        // The d8 executable is resolved before android.jar now (dexing no longer depends on the SDK), so an empty SDK
        // directory reports the missing d8 first. Either way it fails loudly rather than downloading.
        assertTrue(
            result.output.contains("No d8 command found") || result.output.contains("No android.jar found"),
            "with auto-download off the empty SDK must fail loudly:\n${result.output}"
        )
        assertTrue(!result.output.contains("downloading the command-line tools"), "no download may happen")
    }


    @Test
    fun `jarAndroid dexes with a configured d8 and never installs an sdk`() {
        // This is the Termux shape: `pkg install d8` gives a d8, and no Android SDK is involved at all.
        // An SDK that happens to exist on the machine may still supply android.jar, which only helps.
        val d8 = fakeD8Script()
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", androidBuildScript(
            extraBuild = """androidSdkDir = file("no-such-sdk")
                d8Executable = file("${d8.name}")""",
        ))
        write("src/test-mod/Mod.java", "package testmod;\npublic class Mod {}\n")

        val result = runner().withArguments("jarAndroid").build()

        assertTrue(result.task(":jarAndroid")?.outcome == TaskOutcome.SUCCESS, result.output)
        assertTrue(
            !result.output.contains("Downloading") && !rootDir.resolve("sdkmanager-calls.txt").exists(),
            "a configured d8 must not trigger an SDK install:\n${result.output}",
        )
        val androidJar = rootDir.resolve("build/libs").listFiles()?.firstOrNull { it.name.endsWith("-Android.jar") }
        assertTrue(
            androidJar != null && androidJar.length() > 0,
            "the dex jar must exist:\n${result.output}",
        )
    }

    @Test
    fun `android run stages the artifact instead of launching a jvm`() {
        // The Termux shape end to end: a configured d8 so no SDK is needed, Android as the host platform,
        // and a staging directory instead of a game launch.
        val d8 = fakeD8Script()
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", """
            ${pluginSnippet}
            ${kotlinSnippet()}
            mindustryModRoot {
                mindustryApiVersion = "159"
                build {
                    androidSdkDir = file("no-such-sdk")
                    d8Executable = file("${d8.name}")
                    format = "{name}-{version}"
                }
                download {
                    mindustryDownloadVersion = "147"
                    // No test may reach the network: a wrong branch must fail here, not download the game.
                    mindustryDownloadUrl = "file:///nonexistent/mindustry-releases"
                }
                run {
                    hostPlatform = mindustrymoddevelopmentplugin.platform.HostPlatform.Android
                    androidStagingDir = file("staging")
                }
            }
            mindustryMod { modMeta { name = "test-mod"; version = "1.0"; java = true } }
        """)
        write("src/test-mod/Mod.java", "package testmod;\npublic class Mod {}\n")

        val result = runner().withArguments("deploy", "runMindustry").build()

        val staged = rootDir.resolve("staging").listFiles()?.map { it.name }.orEmpty()
        assertTrue(staged.any { it.endsWith(".jar") }, "the artifact must be staged: ${'$'}staged")
        assertTrue(
            result.output.contains("Mods -> Import mod"),
            "the user must be told how to load it:${'\n'}${result.output}",
        )
        assertTrue(
            !result.output.contains("DesktopLauncher"),
            "Android must not launch the desktop client:${'\n'}${result.output}",
        )
        assertTrue(
            !result.output.contains("github.com"),
            "the suite must never fetch the real game:${'\n'}${result.output}",
        )
    }

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

    private fun fakeD8Script(): File =
        rootDir.resolve("standalone-d8").apply {
            writeText(
                """
                #!/bin/sh
                out=""
                while [ ${'$'}# -gt 0 ]; do
                  case "${'$'}1" in
                    --output) out="${'$'}2"; shift 2 ;;
                    *) shift ;;
                  esac
                done
                printf 'PK\005\006' > "${'$'}out"
                head -c 18 /dev/zero >> "${'$'}out"
                """.trimIndent() + "\n"
            )
            setExecutable(true)
        }
    @Test
    fun `jarAndroid depends on downloadAndroidSdk`() {
        // Asking for jarAndroid alone has to pull the install in: that dependency is what keeps the download
        // out of jarAndroid's own action while still running before d8.
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", androidBuildScript())
        write("src/test-mod/Mod.java", "package testmod;\npublic class Mod {}\n")

        val result = runner().withArguments("jarAndroid", "--dry-run").build()

        assertTrue(result.output.contains(":downloadAndroidSdk"), result.output)
        assertTrue(result.output.contains(":jarAndroid"), result.output)
    }

    @Test
    fun `downloadAndroidSdk is skipped when a standalone d8 is available`() {
        // The Termux shape: `pkg install d8` means the toolchain already works, so the SDK task must do
        // nothing at all rather than install an SDK the user never asked for.
        val d8 = fakeD8Script()
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", androidBuildScript(
            extraBuild = """androidSdkDir = file("no-such-sdk")
                d8Executable = file("${d8.name}")""",
        ))
        write("src/test-mod/Mod.java", "package testmod;\npublic class Mod {}\n")

        val result = runner().withArguments("downloadAndroidSdk").build()

        assertTrue(result.task(":downloadAndroidSdk")?.outcome == TaskOutcome.SKIPPED, result.output)
        assertTrue(
            !rootDir.resolve("sdkmanager-calls.txt").exists(),
            "a standalone d8 must not install an SDK:\n${result.output}",
        )
    }

    @Test
    fun `downloadAndroidSdk leaves an SDK that already satisfies the packages alone`() {
        // The packages are on disk, so the task must finish without touching its URL: the file: URL below
        // fails loudly if anything ever tries to download it.
        fakeAndroidSdk()
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", """
            ${pluginSnippet}
            ${kotlinSnippet()}
            mindustryModRoot {
                mindustryApiVersion = "159"
                build {
                    androidSdkDir = file("sdk")
                    format = "{name}-{version}"
                }
                download {
                    androidSdkAutoDownload = true
                    androidSdkDownloadUrl = "file:///nonexistent/commandlinetools.zip"
                    androidSdkDownloadPackages = listOf("platforms;android-30", "build-tools;34.0.0")
                }
            }
            mindustryMod { modMeta { name = "test-mod"; version = "1.0"; java = true } }
        """)
        write("src/test-mod/Mod.java", "package testmod;\npublic class Mod {}\n")

        val result = runner().withArguments("downloadAndroidSdk").build()

        assertTrue(
            result.task(":downloadAndroidSdk")?.outcome in setOf(TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE),
            result.output,
        )
        assertTrue(
            !rootDir.resolve("sdkmanager-calls.txt").exists(),
            "an SDK that satisfies the packages needs no install:\n${result.output}",
        )
    }


    @Test
    fun `the platform-only switch installs the platform without build-tools`() {
        /*
        The Termux shape with the switch on: a standalone d8 supplies the dexer, so the SDK is wanted only
        for android.jar. build-tools must not be installed over the d8 the user already has.
        */
        val d8 = fakeD8Script()
        val toolsUrl = fakeCommandLineToolsArchive()
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", """
            ${pluginSnippet}
            ${kotlinSnippet()}
            mindustryModRoot {
                mindustryApiVersion = "159"
                build {
                    androidSdkDir = file("sdk")
                    d8Executable = file("${d8.name}")
                    format = "{name}-{version}"
                }
                download {
                    androidSdkAutoDownload = true
                    androidSdkDownloadPlatformOnly = true
                    androidSdkDownloadUrl = "$toolsUrl"
                    androidSdkDownloadPackages = listOf("platforms;android-30", "build-tools;34.0.0")
                }
            }
            mindustryMod { modMeta { name = "test-mod"; version = "1.0"; java = true } }
        """)
        write("src/test-mod/Mod.java", "package testmod;\npublic class Mod {}\n")

        val result = runner().withArguments("downloadAndroidSdk").build()

        assertTrue(result.task(":downloadAndroidSdk")?.outcome == TaskOutcome.SUCCESS, result.output)
        val calls = rootDir.resolve("sdkmanager-calls.txt").readLines()
        assertTrue(calls.any { it.contains("platforms;android-30") }, "the platform must be installed: $calls")
        assertTrue(calls.none { it.contains("build-tools") }, "build-tools belong to the user's d8: $calls")
    }


}
