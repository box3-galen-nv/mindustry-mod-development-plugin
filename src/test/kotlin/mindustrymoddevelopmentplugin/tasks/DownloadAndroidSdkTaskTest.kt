package mindustrymoddevelopmentplugin.tasks

import mindustrymoddevelopmentplugin.sdk.AndroidSdkOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * When `downloadAndroidSdk` runs at all, and what it installs.
 *
 * These are the rules that decide whether a Termux user can get `android.jar` without an SDK that can run
 * `build-tools`: with a d8 already resolved the task is normally skipped, and the platform-only switch is
 * the one thing that re-enables it for the platform packages alone.
 */
class DownloadAndroidSdkTaskTest {

    private val packages = listOf("platforms;android-34", "build-tools;34.0.0")

    private fun options(
        d8Command: List<String>? = null,
        platformOnly: Boolean = false,
        autoDownload: Boolean = true,
    ) = AndroidSdkOptions(
        autoDownloadSdk = autoDownload,
        sdkDownloadPackages = packages,
        platformOnly = platformOnly,
        d8Command = d8Command,
    )

    @Test
    fun `a resolved d8 skips the task while the switch is off`() {
        val resolved = options(d8Command = listOf("/usr/bin/d8"))
        assertTrue(!DownloadAndroidSdkTask.shouldRun(resolved))
        // Off means the packages are untouched, so nothing platform-only is inferred either.
        assertEquals(packages, DownloadAndroidSdkTask.effectivePackages(resolved))
    }

    @Test
    fun `the platform-only switch re-enables the task and narrows the packages`() {
        val resolved = options(d8Command = listOf("/usr/bin/d8"), platformOnly = true)
        assertTrue(DownloadAndroidSdkTask.shouldRun(resolved))
        assertEquals(
            listOf("platforms;android-34"),
            DownloadAndroidSdkTask.effectivePackages(resolved),
            "build-tools belong to the standalone d8, so installing them would fight it",
        )
    }

    @Test
    fun `the switch does not narrow a full install`() {
        val noD8 = options(platformOnly = true)
        assertTrue(DownloadAndroidSdkTask.shouldRun(noD8))
        assertEquals(packages, DownloadAndroidSdkTask.effectivePackages(noD8))
    }

    @Test
    fun `the switch alone does not run without a platform package to install`() {
        val resolved = AndroidSdkOptions(
            autoDownloadSdk = true,
            sdkDownloadPackages = listOf("build-tools;34.0.0"),
            platformOnly = true,
            d8Command = listOf("/usr/bin/d8"),
        )
        assertTrue(!DownloadAndroidSdkTask.shouldRun(resolved))
        assertTrue(DownloadAndroidSdkTask.effectivePackages(resolved).isEmpty())
    }

    @Test
    fun `auto download off skips everything`() {
        assertTrue(!DownloadAndroidSdkTask.shouldRun(options(autoDownload = false)))
        assertTrue(!DownloadAndroidSdkTask.shouldRun(options(d8Command = listOf("d8"), platformOnly = true, autoDownload = false)))
    }
}
