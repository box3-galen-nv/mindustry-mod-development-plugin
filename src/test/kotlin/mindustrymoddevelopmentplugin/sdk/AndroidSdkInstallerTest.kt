package mindustrymoddevelopmentplugin.sdk

import java.io.File
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Covers the SDK auto-install path offline.
 *
 * A fake command-line tools archive is served from a `file:` URL; its `sdkmanager` records how
 * it was invoked and creates the `platforms` / `build-tools` layout the plugin expects. No
 * network access and no real SDK are involved.
 */
class AndroidSdkInstallerTest {

    @field:TempDir
    lateinit var tempDir: Path

    private val root: File get() = tempDir.toFile()

    /** A quiet sink so the installer's progress lines do not pollute the test output. */
    private val log: (String) -> Unit = {}

    /** Zips [sdkManagerScript] as `cmdline-tools/bin/sdkmanager` and returns the archive URL. */
    private fun toolsArchive(sdkManagerScript: String): String {
        val zip = File(root, "commandlinetools.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            out.putNextEntry(ZipEntry("cmdline-tools/bin/sdkmanager"))
            out.write(sdkManagerScript.toByteArray())
            out.closeEntry()
        }
        return zip.toURI().toString()
    }

    /**
     * Writes `<sdk>/{platforms/android-30/android.jar, build-tools/34.0.0/d8}` when called
     * without `--licenses`, and appends its arguments to [record]. Returns the archive URL.
     */
    private fun fakeToolsArchive(record: File): String {
        // Built as a separate file so the sdkmanager script can simply copy it into place.
        val fakeD8 = File(root, "fake-d8").apply {
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

        return toolsArchive(
            """
            #!/bin/sh
            root=""
            for a in "${'$'}@"; do
              case "${'$'}a" in --sdk_root=*) root="${'$'}{a#--sdk_root=}" ;; esac
            done
            echo "ARGS ${'$'}*" >> "${record.absolutePath}"
            case "${'$'}*" in
              *--licenses*) : ;;
              *)
                mkdir -p "${'$'}root/platforms/android-30" "${'$'}root/build-tools/34.0.0"
                : > "${'$'}root/platforms/android-30/android.jar"
                cp "${fakeD8.absolutePath}" "${'$'}root/build-tools/34.0.0/d8"
                chmod +x "${'$'}root/build-tools/34.0.0/d8"
                ;;
            esac
            # Consume the licence answers so the writer never blocks on a full pipe.
            while read -r line; do :; done
            exit 0
            """.trimIndent() + "\n"
        )
    }

    // ---- needsInstall ----

    @Test
    fun `needsInstall is true for a null missing or empty directory`() {
        assertTrue(AndroidSdkInstaller.needsInstall(null))
        assertTrue(AndroidSdkInstaller.needsInstall(File(root, "does-not-exist")))
        assertTrue(AndroidSdkInstaller.needsInstall(File(root, "empty").also { it.mkdirs() }))
    }

    @Test
    fun `needsInstall is true when a part is missing`() {
        val onlyPlatform = File(root, "only-platform")
        File(onlyPlatform, "platforms/android-30").mkdirs()
        File(onlyPlatform, "platforms/android-30/android.jar").writeText("x")
        assertTrue(AndroidSdkInstaller.needsInstall(onlyPlatform), "build-tools missing")

        val onlyBuildTools = File(root, "only-build-tools")
        File(onlyBuildTools, "build-tools/34.0.0").mkdirs()
        File(onlyBuildTools, "build-tools/34.0.0/d8").writeText("x")
        assertTrue(AndroidSdkInstaller.needsInstall(onlyBuildTools), "platforms missing")
    }

    @Test
    fun `needsInstall is false for a complete sdk`() {
        val sdk = File(root, "complete")
        File(sdk, "platforms/android-30").mkdirs()
        File(sdk, "platforms/android-30/android.jar").writeText("x")
        File(sdk, "build-tools/34.0.0").mkdirs()
        File(sdk, "build-tools/34.0.0/d8").writeText("x")
        assertTrue(!AndroidSdkInstaller.needsInstall(sdk))
    }

    @Test
    fun `needsInstall checks the requested package versions`() {
        // Regression: "some platform + some build-tools" is not the same as the requested ones.
        val sdk = File(root, "wrong-versions")
        File(sdk, "platforms/android-30").mkdirs()
        File(sdk, "platforms/android-30/android.jar").writeText("x")
        File(sdk, "build-tools/30.0.0").mkdirs()
        File(sdk, "build-tools/30.0.0/d8").writeText("x")

        // Usable as-is …
        assertTrue(!AndroidSdkInstaller.needsInstall(sdk))
        // … but not for the requested versions.
        assertTrue(AndroidSdkInstaller.needsInstall(sdk, listOf("platforms;android-34")))
        assertTrue(AndroidSdkInstaller.needsInstall(sdk, listOf("build-tools;34.0.0")))
        assertTrue(
            AndroidSdkInstaller.needsInstall(sdk, listOf("platforms;android-30", "build-tools;34.0.0")),
            "one missing package is enough",
        )
        assertTrue(!AndroidSdkInstaller.needsInstall(sdk, listOf("platforms;android-30", "build-tools;30.0.0")))
    }

    @Test
    fun `needsInstall tolerates specs it cannot verify`() {
        val sdk = File(root, "unverifiable")
        File(sdk, "platforms/android-30").mkdirs()
        File(sdk, "platforms/android-30/android.jar").writeText("x")
        File(sdk, "build-tools/34.0.0").mkdirs()
        File(sdk, "build-tools/34.0.0/d8").writeText("x")

        // The sdkmanager tool owns these; guessing would trigger an installation on every build.
        assertTrue(!AndroidSdkInstaller.needsInstall(sdk, listOf("platform-tools")))
        assertTrue(!AndroidSdkInstaller.needsInstall(sdk, listOf("cmdline-tools;latest")))
        assertTrue(!AndroidSdkInstaller.needsInstall(sdk, listOf("platforms;android-30", "platform-tools")))
        // A malformed spec must not trigger an installation either.
        assertTrue(!AndroidSdkInstaller.needsInstall(sdk, listOf("platforms")))
        assertTrue(!AndroidSdkInstaller.needsInstall(sdk, listOf("platforms;")))
    }

    @Test
    fun `install fails when sdkmanager did not provide the requested package`() {
        // The fake sdkmanager creates platforms/android-30 + build-tools/34.0.0 only.
        val url = toolsArchive(
            """
            #!/bin/sh
            root=""
            for a in "${'$'}@"; do
              case "${'$'}a" in --sdk_root=*) root="${'$'}{a#--sdk_root=}" ;; esac
            done
            case "${'$'}*" in
              *--licenses*) : ;;
              *) mkdir -p "${'$'}root/platforms/android-30"; : > "${'$'}root/platforms/android-30/android.jar" ;;
            esac
            while read -r line; do :; done
            exit 0
            """.trimIndent() + "\n"
        )

        val error = runCatching {
            AndroidSdkInstaller.install(
                File(root, "sdk-partial"), url, listOf("platforms;android-30", "build-tools;34.0.0"), 5, log,
            )
        }.exceptionOrNull()

        assertTrue(error != null, "install should fail when the requested package is absent")
        assertTrue(error!!.message!!.contains("build-tools;34.0.0"), "message should name the missing package: ${error.message}")
        assertTrue(error.message!!.contains("download.androidSdkDownloadPackages"), "message should point at the DSL: ${error.message}")
    }

    @Test
    fun `install succeeds when only unverifiable packages are requested`() {
        // `platform-tools` cannot be checked locally (isPackagePresent returns true), so an installation
        // that created nothing must not be reported as "does not provide: any platforms/build-tools".
        val url = toolsArchive("#!/bin/sh\nwhile read -r line; do :; done\nexit 0\n")
        val sdk = File(root, "sdk-unverifiable")

        AndroidSdkInstaller.install(sdk, url, listOf("platform-tools"), 5, log)

        assertTrue(File(sdk, "cmdline-tools/latest/bin/sdkmanager").isFile, "the tools must still be unpacked")
    }

    // ---- resolveOrInstall (what downloadAndroidSdk runs before jarAndroid) ----

    @Test
    fun `jarAndroid reuses an sdk that satisfies the requested packages`() {
        val sdk = File(root, "usable")
        File(sdk, "platforms/android-34").mkdirs()
        File(sdk, "platforms/android-34/android.jar").writeText("x")
        File(sdk, "build-tools/34.0.0").mkdirs()
        File(sdk, "build-tools/34.0.0/d8").writeText("x")

        val project = ProjectBuilder.builder().build()
        val result = AndroidSdkInstaller.resolveOrInstall(androidSdkOptions(
                project, sdk,
                // A URL that cannot work: if an installation were attempted, this test would fail.
                packages = listOf("platforms;android-34", "build-tools;34.0.0"),
            ),
            project.logger,
        )

        assertTrue(result == sdk, "the existing SDK must be reused, got $result")
        assertTrue(!File(sdk, "cmdline-tools").exists(), "nothing may be downloaded")
    }

    @Test
    fun `jarAndroid installs into the configured directory when versions do not match`() {
        // Present but wrong versions: the loose "some platform + some build-tools" check used to
        // accept this and d8 then ran against the wrong SDK.
        val sdk = File(root, "stale")
        File(sdk, "platforms/android-30").mkdirs()
        File(sdk, "platforms/android-30/android.jar").writeText("x")
        File(sdk, "build-tools/30.0.0").mkdirs()
        File(sdk, "build-tools/30.0.0/d8").writeText("x")

        val record = File(root, "sdkmanager-calls.txt")
        val url = fakeToolsArchive(record) // creates platforms/android-30 + build-tools/34.0.0
        val project = ProjectBuilder.builder().build()
        val result = AndroidSdkInstaller.resolveOrInstall(androidSdkOptions(
                project, sdk, url,
                packages = listOf("platforms;android-30", "build-tools;34.0.0"),
            ),
            project.logger,
        )

        assertTrue(result == sdk, "the configured dir is the install target, got $result")
        assertTrue(File(sdk, "build-tools/34.0.0/d8").isFile, "the missing package must be installed")
        assertTrue(File(sdk, "cmdline-tools/latest/bin/sdkmanager").isFile, "tools must be unpacked")
    }

    @Test
    fun `jarAndroid does not install when auto download is off`() {
        val sdk = File(root, "off")
        File(sdk, "platforms/android-30").mkdirs()
        File(sdk, "platforms/android-30/android.jar").writeText("x")
        File(sdk, "build-tools/34.0.0").mkdirs()
        File(sdk, "build-tools/34.0.0/d8").writeText("x")

        val project = ProjectBuilder.builder().build()
        val result = AndroidSdkInstaller.resolveOrInstall(androidSdkOptions(
                project, sdk, "file:///nonexistent/commandlinetools.zip",
                packages = listOf("platforms;android-34"),
                autoDownload = false,
            ),
            project.logger,
        )

        assertTrue(result == sdk, "auto-download off must fall back to the found SDK, got $result")
        assertTrue(!File(sdk, "cmdline-tools").exists(), "nothing may be downloaded")
    }

    // ---- extra sdkmanager flags (download.androidSdkExtraArgs) ----

    @Test
    fun `extra flags reach both sdkmanager invocations`() {
        val record = File(root, "calls.txt")
        val url = fakeToolsArchive(record)
        val sdk = File(root, "extra-args-sdk")

        AndroidSdkInstaller.install(
            sdkRoot = sdk,
            toolsUrl = url,
            packages = listOf("platforms;android-30", "build-tools;34.0.0"),
            timeoutMinutes = 5,
            log = log,
            extraArgs = listOf("--proxy=http", "--proxy_host=mirror.example", "--proxy_port=80"),
        )

        val calls = record.readLines()
        assertTrue(calls.size == 2, "one call per invocation, got: $calls")
        // The license run needs the proxy too: it is the first thing that downloads the manifest.
        calls.forEach { call ->
            assertTrue(call.contains("--proxy_host=mirror.example"), call)
            assertTrue(call.contains("--proxy_port=80"), call)
            assertTrue(call.contains("--sdk_root="), call)
        }
        assertTrue(calls.any { it.contains("--licenses") }, calls.toString())
        assertTrue(calls.any { it.contains("platforms;android-30") }, calls.toString())
    }

    @Test
    fun `conflicting extra flags are rejected before anything is downloaded`() {
        val sdk = File(root, "conflict-sdk")

        val error = assertThrows(GradleException::class.java) {
            AndroidSdkInstaller.install(
                sdkRoot = sdk,
                // Deliberately unreachable: a rejected flag must fail before the download, not after.
                toolsUrl = "file:///nonexistent/commandlinetools.zip",
                packages = listOf("platforms;android-30"),
                timeoutMinutes = 5,
                log = log,
                extraArgs = listOf("--sdk_root=/elsewhere", "--list"),
            )
        }

        val message = error.message!!
        assertTrue(message.contains("--sdk_root=/elsewhere"), message)
        assertTrue(message.contains("--list"), message)
        assertTrue(message.contains("download.androidSdkExtraArgs"), message)
        assertTrue(!sdk.exists(), "nothing may be created before the arguments are validated")
    }

    @Test
    fun `a windows sdk with only d8_bat counts as complete`() {
        // Windows build-tools install `d8.bat` (the jar sits under lib/), so a file check for `d8` alone
        // declared a perfectly usable SDK incomplete and made the installer download it again.
        val sdk = File(root, "win-sdk")
        File(sdk, "platforms/android-30").mkdirs()
        File(sdk, "platforms/android-30/android.jar").writeText("x")
        File(sdk, "build-tools/34.0.0").mkdirs()
        File(sdk, "build-tools/34.0.0/d8.bat").writeText("rem d8")

        assertTrue(!AndroidSdkInstaller.needsInstall(sdk), "a d8.bat build-tools is usable")
    }

    // ---- install ----

    @Test
    fun `install downloads unpacks accepts licences and installs packages`() {
        val record = File(root, "sdkmanager-calls.txt")
        val url = fakeToolsArchive(record)
        val sdk = File(root, "sdk")

        AndroidSdkInstaller.install(sdk, url, listOf("platforms;android-30"), 5, log)

        // Unpacked into the layout sdkmanager expects, with the exec bit restored.
        val sdkManager = File(sdk, "cmdline-tools/latest/bin/sdkmanager")
        assertTrue(sdkManager.isFile, "sdkmanager should be unpacked to cmdline-tools/latest/bin")
        assertTrue(sdkManager.canExecute(), "sdkmanager must be executable (zip extraction drops the bit)")

        // License step first, then the requested packages, both with --sdk_root.
        val calls = record.readLines()
        assertTrue(calls.size == 2, "expected licences + install, got $calls")
        assertTrue(calls[0].contains("--licenses"), "first call must accept licences: ${calls[0]}")
        assertTrue(calls[0].contains("--sdk_root=${sdk.absolutePath}"), "got ${calls[0]}")
        assertTrue(calls[1].contains("platforms;android-30"), "second call must install packages: ${calls[1]}")

        // The download is cached, and the SDK is usable afterward.
        assertTrue(File(sdk, "commandlinetools.zip").let { !it.exists() }, "the zip should be deleted after unpacking")
        assertTrue(!AndroidSdkInstaller.needsInstall(sdk), "SDK should be complete after install")
    }

    @Test
    fun `install is a no-op when the tools are already unpacked`() {
        val record = File(root, "sdkmanager-calls.txt")
        val url = fakeToolsArchive(record)
        val sdk = File(root, "sdk-already")

        AndroidSdkInstaller.install(sdk, url, listOf("platforms;android-30"), 5, log)
        val afterFirst = record.readLines().size
        AndroidSdkInstaller.install(sdk, url, listOf("platforms;android-30"), 5, log)

        // Second run skips the download+unpack but still asks sdkmanager (it is idempotent).
        assertTrue(record.readLines().size == afterFirst + 2, "second run should not re-download")
    }

    @Test
    fun `install fails with a helpful message when the archive has no sdkmanager`() {
        val zip = File(root, "broken.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            out.putNextEntry(ZipEntry("cmdline-tools/README.txt"))
            out.write("not a tools archive".toByteArray())
            out.closeEntry()
        }
        val sdk = File(root, "sdk-broken")

        val error = runCatching {
            AndroidSdkInstaller.install(sdk, zip.toURI().toString(), listOf("platforms;android-30"), 5, log)
        }.exceptionOrNull()

        assertTrue(error != null, "install should fail")
        assertTrue(
            error!!.message!!.contains("androidSdkDownloadUrl"),
            "error should point at the download URL setting: ${error.message}"
        )
    }

    @Test
    fun `a broken pipe does not replace the sdkmanager output`() {
        /*
        `exec 0<&-` closes the script's stdin, so the plugin's license answers definitely hit a closed
        pipe. That IOException used to escape and become the reported failure, hiding the output that
        says what to fix — it was timing-dependent, so CI saw it on one job and not another.
        */
        val url = toolsArchive(
            """
            #!/bin/sh
            exec 0<&-
            echo "Warning: Failed to download any source lists!"
            exit 1
            """.trimIndent() + "\n"
        )

        val error = runCatching {
            AndroidSdkInstaller.install(File(root, "sdk-broken-pipe"), url, listOf("platforms;android-30"), 5, log)
        }.exceptionOrNull()

        assertTrue(error != null, "install should fail")
        assertTrue(
            !error!!.message!!.contains("Broken pipe"),
            "the child's output must win over the pipe error: ${error.message}"
        )
        assertTrue(
            error.message!!.contains("download any source lists", ignoreCase = true) ||
                error.message!!.contains("androidSdkDownloadUrl"),
            "the hint should come from sdkmanager output: ${error.message}"
        )
    }

    @Test
    fun `install reports an unusable download url`() {
        val error = runCatching {
            AndroidSdkInstaller.install(File(root, "sdk-bad-url"), "not a url", emptyList(), 5, log)
        }.exceptionOrNull()

        assertTrue(error != null && error.message!!.contains("androidSdkDownloadUrl"), "got ${error?.message}")
    }

    @Test
    fun `install explains an unreachable package repository`() {
        // Reproduces the real failure seen when sdkmanager cannot write its manifest cache
        // (read-only HOME), which it reports as a download failure.
        val url = toolsArchive(
            """
            #!/bin/sh
            echo "Warning: Failed to download any source lists!"
            echo "Warning: IO exception while downloading manifest"
            exit 1
            """.trimIndent() + "\n"
        )

        val error = runCatching {
            AndroidSdkInstaller.install(File(root, "sdk-unreachable"), url, listOf("platforms;android-30"), 5, log)
        }.exceptionOrNull()

        assertTrue(error != null, "install should fail")
        val message = error!!.message!!
        assertTrue(message.contains("ANDROID_USER_HOME"), "the hint should name the cache variable: $message")
        assertTrue(message.contains("dl.google.com"), "the hint should name the host: $message")
        assertTrue(message.contains("androidSdkDownloadUrl"), "the hint should mention the mirror option: $message")
    }

    /** [AndroidSdkOptions] with the SDK dir wired through a real Gradle property. */
    private fun androidSdkOptions(
        project: org.gradle.api.Project,
        sdkDir: File,
        toolsUrl: String = "file:///nonexistent/commandlinetools.zip",
        packages: List<String> = emptyList(),
        autoDownload: Boolean = true,
    ) = AndroidSdkOptions(
        androidSdkDir = project.objects.directoryProperty().apply { set(sdkDir) },
        autoDownloadSdk = autoDownload,
        sdkDownloadUrl = toolsUrl,
        sdkDownloadPackages = packages,
        sdkInstallDir = File(root, "install-dir"),
        sdkDownloadTimeoutMinutes = 5,
    )

}
