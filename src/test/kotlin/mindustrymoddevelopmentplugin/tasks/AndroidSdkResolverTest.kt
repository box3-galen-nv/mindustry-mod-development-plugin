package mindustrymoddevelopmentplugin.tasks

import java.io.File
import java.nio.file.Path
import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/**
 * Verifies the lookup priority of [resolveAndroidSdkDir]:
 * configured androidSdkDir → ANDROID_HOME → ANDROID_SDK_ROOT → user home → throw.
 * env / userHome / osName are all injectable, so no real environment is needed.
 */
class AndroidSdkResolverTest {

    @field:TempDir
    lateinit var tempDir: Path

    private val userHome: File get() = tempDir.toFile()

    private fun sdkDir(name: String): File =
        userHome.resolve(name).also { it.mkdirs() }

    // =========================================================================
    //  configured androidSdkDir
    // =========================================================================

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

    // =========================================================================
    //  env vars
    // =========================================================================

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
    // =========================================================================
    //  user home fallback + failure
    // =========================================================================

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
    // =========================================================================
    //  defaultAndroidSdkDirs per OS
    // =========================================================================

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

    // =========================================================================
    //  SDK version-number ordering (sdkVersionParts / compareSdkVersions)
    // =========================================================================

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

    // =========================================================================
    //  findAndroidSdkDir — the non-throwing variant used before auto-install
    // =========================================================================

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
        // Nothing anywhere: null is the "no SDK yet" answer the auto-install path relies on.
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
}
