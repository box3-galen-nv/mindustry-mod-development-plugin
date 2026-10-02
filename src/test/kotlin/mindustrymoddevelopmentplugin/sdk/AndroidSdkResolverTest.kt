package mindustrymoddevelopmentplugin.sdk

import java.io.File
import java.nio.file.Path
import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/**
 * Verifies the lookup priority of [AndroidSdk.resolveAndroidSdkDir]:
 * configured androidSdkDir → ANDROID_HOME → ANDROID_SDK_ROOT → user home → throw.
 * env / userHome / osName are all injectable, so no real environment is needed.
 */
class AndroidSdkResolverTest {

    @field:TempDir
    lateinit var tempDir: Path

    private val userHome: File get() = tempDir.toFile()

    private fun sdkDir(name: String): File =
        userHome.resolve(name).also { it.mkdirs() }

    // ---- configured androidSdkDir ----

    @Test
    fun `configured dir wins over env`() {
        val configured = sdkDir("configured")
        val envHome = sdkDir("env-home")
        val result = AndroidSdk.resolveAndroidSdkDir(
            configured = configured,
            env = mapOf("ANDROID_HOME" to envHome.path),
            userHome = userHome,
        )
        assertTrue(result == configured, "configured dir should take priority")
    }

    @Test
    fun `configured dir missing falls back to android home`() {
        // Regression: a configured-but-absent path used to end the search, hiding a working
        // ANDROID_HOME behind a stale androidSdkDir.
        val missing = File(userHome, "missing-sdk")
        val envHome = sdkDir("env-home")
        val result = AndroidSdk.resolveAndroidSdkDir(
            configured = missing,
            env = mapOf("ANDROID_HOME" to envHome.path),
            userHome = userHome,
        )
        assertTrue(result == envHome, "expected the env SDK, got $result")
    }

    @Test
    fun `configured dir missing falls back to the user home location`() {
        val missing = File(userHome, "missing-sdk")
        val userSdk = sdkDir("Android/Sdk")
        val result = AndroidSdk.resolveAndroidSdkDir(
            configured = missing,
            env = emptyMap(),
            userHome = userHome,
            osName = "Linux",
        )
        assertTrue(result == userSdk, "expected the user-home SDK, got $result")
    }

    @Test
    fun `configured dir missing is reported when nothing else is valid`() {
        val missing = File(userHome, "missing-sdk")
        val e = assertThrows<GradleException> {
            AndroidSdk.resolveAndroidSdkDir(configured = missing, env = emptyMap(), userHome = userHome)
        }
        assertTrue(e.message!!.contains(missing.path), "error should mention the configured path")
        assertTrue(e.message!!.contains("build.androidSdkDir"), "error should say where it came from")
    }

    // ---- env vars ----

    @Test
    fun `ANDROID_HOME used when configured is null`() {
        val envHome = sdkDir("env-home")
        val result = AndroidSdk.resolveAndroidSdkDir(
            configured = null,
            env = mapOf("ANDROID_HOME" to envHome.path),
            userHome = userHome,
        )
        assertTrue(result == envHome)
    }
    @Test
    fun `ANDROID_SDK_ROOT used when ANDROID_HOME invalid`() {
        val sdkRoot = sdkDir("sdk-root")
        val result = AndroidSdk.resolveAndroidSdkDir(
            configured = null,
            env = mapOf(
                "ANDROID_HOME" to File(userHome, "bad").path,
                "ANDROID_SDK_ROOT" to sdkRoot.path,
            ),
            userHome = userHome,
        )
        assertTrue(result == sdkRoot)
    }
    // ---- user home fallback + failure ----

    @Test
    fun `falls back to mac user home location`() {
        val macSdk = userHome.resolve("Library/Android/sdk").also { it.mkdirs() }
        val result = AndroidSdk.resolveAndroidSdkDir(
            configured = null,
            env = emptyMap(),
            userHome = userHome,
            osName = "Mac OS X",
        )
        assertTrue(result == macSdk)
    }
    @Test
    fun `throws with tried paths when nothing found`() {
        val e = assertThrows<GradleException> {
            AndroidSdk.resolveAndroidSdkDir(
                configured = null,
                env = emptyMap(),
                userHome = userHome,
                osName = "Mac OS X",
            )
        }
        assertTrue(e.message!!.contains("Tried"), "error should list tried paths")
        assertTrue(e.message!!.contains("Library/Android/sdk"))
    }
    // ---- defaultAndroidSdkDirs per OS ----

    @Test
    fun `default mac sdk path`() {
        val dirs = AndroidSdk.defaultAndroidSdkDirs(userHome, emptyMap(), "Mac OS X")
        assertTrue(dirs == listOf(File(userHome, "Library/Android/sdk")))
    }
    @Test
    fun `default windows sdk paths use LOCALAPPDATA then user home`() {
        val localAppData = "C:\\Users\\x\\AppData\\Local"
        val dirs = AndroidSdk.defaultAndroidSdkDirs(userHome, mapOf("LOCALAPPDATA" to localAppData), "Windows 11")
        assertTrue(dirs == listOf(
            File(localAppData, "Android/Sdk"),
            File(userHome, "AppData/Local/Android/Sdk"),
        ))
    }
    @Test
    fun `default linux sdk paths`() {
        val dirs = AndroidSdk.defaultAndroidSdkDirs(userHome, emptyMap(), "Linux")
        assertTrue(dirs == listOf(File(userHome, "Android/Sdk"), File(userHome, ".android/sdk")))
    }

    // ---- SDK version-number ordering (sdkVersionParts / compareSdkVersions) ----

    @Test
    fun `sdkVersionParts parses trailing version numbers`() {
        assertTrue(AndroidSdk.sdkVersionParts(File("android-30")) == listOf(30))
        assertTrue(AndroidSdk.sdkVersionParts(File("34.0.0")) == listOf(34, 0, 0))
        assertTrue(AndroidSdk.sdkVersionParts(File("android-9")) == listOf(9))
        assertTrue(AndroidSdk.sdkVersionParts(File("no-version-dir")) == emptyList<Int>())
    }

    @Test
    fun `compareSdkVersions orders by version number not by string`() {
        // String ordering puts android-9 before android-30; version-number ordering must do the opposite
        assertTrue(AndroidSdk.compareSdkVersions(File("android-9"), File("android-30")) < 0)
        assertTrue(AndroidSdk.compareSdkVersions(File("android-30"), File("android-33")) < 0)
        assertTrue(AndroidSdk.compareSdkVersions(File("android-33"), File("android-9")) > 0)
        assertTrue(AndroidSdk.compareSdkVersions(File("build-tools/34.0.0"), File("build-tools/9.0.0")) > 0)
    }

    // ---- findAndroidSdkDir — the non-throwing variant used before auto-install ----

    @Test
    fun `findAndroidSdkDir returns null instead of throwing when nothing exists`() {
        val found = AndroidSdk.findAndroidSdkDir(
            configured = null,
            env = emptyMap(),
            userHome = userHome,
            osName = "Linux",
        )
        assertTrue(found == null, "expected null, got $found")
    }

    @Test
    fun `findAndroidSdkDir skips a configured path that does not exist`() {
        val missing = File(userHome, "missing")
        val envHome = sdkDir("env-home")
        assertTrue(
            AndroidSdk.findAndroidSdkDir(
                configured = missing, env = mapOf("ANDROID_HOME" to envHome.path), userHome = userHome,
            ) == envHome,
            "the env SDK must be found even though androidSdkDir is stale",
        )
        // Nothing anywhere: null is the "no SDK yet" answer the auto-installation path relies on.
        assertTrue(AndroidSdk.findAndroidSdkDir(configured = missing, env = emptyMap(), userHome = userHome) == null)
    }

    @Test
    fun `findAndroidSdkDir prefers an existing configured dir`() {
        val configured = sdkDir("configured")
        val envHome = sdkDir("env-home")
        assertTrue(
            AndroidSdk.findAndroidSdkDir(
                configured = configured, env = mapOf("ANDROID_HOME" to envHome.path), userHome = userHome,
            ) == configured,
        )
    }

    @Test
    fun `findAndroidSdkDir returns the configured directory and follows the env order`() {
        val configured = sdkDir("configured")
        val envHome = sdkDir("env-home")
        val rootHome = sdkDir("root-home")

        assertTrue(
            AndroidSdk.findAndroidSdkDir(configured, mapOf("ANDROID_HOME" to envHome.path), userHome) == configured
        )
        assertTrue(AndroidSdk.findAndroidSdkDir(null, mapOf("ANDROID_HOME" to envHome.path), userHome) == envHome)
        assertTrue(AndroidSdk.findAndroidSdkDir(null, mapOf("ANDROID_SDK_ROOT" to rootHome.path), userHome) == rootHome)
    }

    // ---- resolveD8: dexing without an Android SDK ----

    private fun executable(dir: File, name: String): File =
        File(dir, name).also {
            it.parentFile.mkdirs()
            it.writeText("#!/bin/sh\n")
            it.setExecutable(true)
        }

    @Test
    fun `a configured d8 executable wins over the path and the sdk`() {
        val configured = executable(sdkDir("tools"), "my-d8")
        val onPath = sdkDir("path-bin").also { executable(it, "d8") }
        val sdk = sdkDir("sdk").also { executable(File(it, "build-tools/34.0.0"), "d8") }

        assertTrue(
            AndroidSdk.resolveD8(configured, onPath.path, sdk, "Linux") == listOf(configured.absolutePath)
        )
    }

    @Test
    fun `a configured d8 jar is run through the build JVM`() {
        val jar = File(sdkDir("tools"), "d8.jar").also { it.writeText("x") }

        val command = AndroidSdk.resolveD8(jar, null, null, "Linux")!!

        assertTrue(command[1] == "-cp" && command[2] == jar.absolutePath, "got $command")
        assertTrue(command[3] == "com.android.tools.r8.D8", "got $command")
        assertTrue(command[0].endsWith("java") || command[0].endsWith("java.exe"), "got $command")
    }

    @Test
    fun `d8 on the path is used when nothing is configured`() {
        val bin = sdkDir("path-bin")
        val d8 = executable(bin, "d8")

        assertTrue(AndroidSdk.resolveD8(null, bin.path, null, "Linux") == listOf(d8.absolutePath))
    }

    @Test
    fun `build-tools are the last resort, and a lone lib jar becomes a java command`() {
        val sdk = sdkDir("sdk")
        val tools = File(sdk, "build-tools/34.0.0").also { it.mkdirs() }

        // Windows layout first: d8.bat with no d8.
        File(tools, "d8.bat").writeText("rem d8")
        assertTrue(AndroidSdk.resolveD8(null, null, sdk, "Windows") == listOf(File(tools, "d8.bat").absolutePath))

        // The launcher is missing entirely. This used to fail outright — and it is exactly the Termux case,
        // where build-tools' d8 script cannot run because Android has no /bin/sh, while its jar always can.
        File(tools, "d8.bat").delete()
        val jar = File(tools, "lib/d8.jar").also { it.parentFile.mkdirs(); it.writeText("x") }
        val command = AndroidSdk.resolveD8(null, null, sdk, "Linux")!!
        assertTrue(command[1] == "-cp" && command[2] == jar.absolutePath, "got $command")
        assertTrue(command[3] == "com.android.tools.r8.D8", "got $command")

        // A real launcher beats the jar.
        executable(tools, "d8")
        assertTrue(AndroidSdk.resolveD8(null, null, sdk, "Linux") == listOf(File(tools, "d8").absolutePath))
    }

    @Test
    fun `the newest build-tools wins`() {
        val sdk = sdkDir("sdk")
        executable(File(sdk, "build-tools/34.0.0"), "d8")
        val newer = executable(File(sdk, "build-tools/35.0.0"), "d8")

        assertTrue(AndroidSdk.resolveD8(null, null, sdk, "Linux") == listOf(newer.absolutePath))
    }

    @Test
    fun `no d8 anywhere yields null and android jar is optional`() {
        assertTrue(AndroidSdk.resolveD8(null, "", sdkDir("empty-sdk"), "Linux") == null)
        assertTrue(AndroidSdk.findAndroidJar(sdkDir("no-platforms")) == null)

        val sdk = sdkDir("sdk-with-platform")
        File(sdk, "platforms/android-30").mkdirs()
        File(sdk, "platforms/android-30/android.jar").writeText("x")
        File(sdk, "platforms/android-34").mkdirs()
        val newest = File(sdk, "platforms/android-34/android.jar").also { it.writeText("x") }

        assertTrue(AndroidSdk.findAndroidJar(sdk) == newest, "the newest platform wins")
    }
}
