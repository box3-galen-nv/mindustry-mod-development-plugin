package mindustrymoddevelopmentplugin.dsl

import mindustrymoddevelopmentplugin.platform.TargetPlatform
import java.io.File
import mindustrymoddevelopmentplugin.meta.ModMeta
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MindustryModExtensionTest {

    /** `MindustryRunConfig` is an abstract Gradle-managed type, so it comes from the root extension. */
    private fun runConfig(): MindustryRunConfig = ProjectBuilder.builder().build()
        .extensions.create("mindustryModRoot", MindustryModRootExtension::class.java)
        .run

    @Test
    fun `readBuildCounter returns 0 when file missing`() {
        val project = ProjectBuilder.builder().build()
        val ext = project.extensions.create(
            "mindustryMod", MindustryModExtension::class.java, project
        )
        assertTrue(ext.readBuildCounter() == 0)
    }
    @Test
    fun `writeBuildCounter then readBack`() {
        val project = ProjectBuilder.builder().build()
        val ext = project.extensions.create(
            "mindustryMod", MindustryModExtension::class.java, project
        )
        ext.writeBuildCounter(5)
        assertTrue(ext.readBuildCounter() == 5)
    }
    @Test
    fun `readBuildCounter invalid content returns 0`() {
        val project = ProjectBuilder.builder().build()
        val buildDir = project.layout.buildDirectory.asFile.get().also { it.mkdirs() }
        File(buildDir, "buildCounter.txt").writeText("not-a-number")
        val ext = project.extensions.create(
            "mindustryMod", MindustryModExtension::class.java, project
        )
        assertTrue(ext.readBuildCounter() == 0)
    }
    @Test
    fun `readBuildCounter returns persisted value across calls`() {
        val project = ProjectBuilder.builder().build()
        val ext = project.extensions.create(
            "mindustryMod", MindustryModExtension::class.java, project
        )
        ext.writeBuildCounter(42)
        val read1 = ext.readBuildCounter()
        val read2 = ext.readBuildCounter()
        assertTrue(read1 == 42)
        assertTrue(read2 == 42)
    }
    // =========================================================================
    //  File path properties (readme / license / icon / assets)
    // =========================================================================

    @Test
    fun `readme defaults to projectDir README`() {
        val project = ProjectBuilder.builder().build()
        val ext = project.extensions.create(
            "mindustryMod", MindustryModExtension::class.java, project
        )
        val expected = project.layout.projectDirectory.file("README.md").asFile
        assertTrue(ext.readme.get().asFile == expected)
    }
    @Test
    fun `license defaults to projectDir LICENSE`() {
        val project = ProjectBuilder.builder().build()
        val ext = project.extensions.create(
            "mindustryMod", MindustryModExtension::class.java, project
        )
        val expected = project.layout.projectDirectory.file("LICENSE").asFile
        assertTrue(ext.license.get().asFile == expected)
    }
    @Test
    fun `icon defaults to projectDir icon-png`() {
        val project = ProjectBuilder.builder().build()
        val ext = project.extensions.create(
            "mindustryMod", MindustryModExtension::class.java, project
        )
        val expected = project.layout.projectDirectory.file("icon.png").asFile
        assertTrue(ext.icon.get().asFile == expected)
    }
    @Test
    fun `assets defaults to projectDir assets dir`() {
        val project = ProjectBuilder.builder().build()
        val ext = project.extensions.create(
            "mindustryMod", MindustryModExtension::class.java, project
        )
        val expected = project.layout.projectDirectory.dir("assets").asFile
        assertTrue(ext.assets.files.any { it == expected })
    }
    // =========================================================================
    //  deployTag (run sub-config)
    // =========================================================================

    @Test
    fun `deployTag defaults to the plugin constant`() {
        val config = runConfig()
        assertTrue(config.deployTag.get() == MindustryRunConfig.DEFAULT_DEPLOY_TAG)
        assertTrue(config.deployTag.get() != "d", "the default is deliberately longer than the old single letter")
    }

    @Test
    fun `the default deploy tag is safe in a file name`() {
        val tag = MindustryRunConfig.DEFAULT_DEPLOY_TAG
        assertTrue(tag.length in 1..10, "'$tag' must stay short: it prefixes every deployed file name")
        assertTrue(Regex("[A-Za-z0-9._-]+").matches(tag), "'$tag' must not need escaping on any file system")
        assertTrue(!tag.contains('[') && !tag.contains(']'), "'$tag' is wrapped in brackets by the plugin")
    }

    @Test
    fun `deployTag can be customized`() {
        val config = runConfig()
        config.deployTag.set("x")
        assertTrue(config.deployTag.get() == "x")
    }

    // =========================================================================
    //  cleanDeployedFiles (run sub-config)
    // =========================================================================

    @Test
    fun `cleanDeployedFiles defaults to true`() {
        assertTrue(runConfig().cleanDeployedFiles.get() == true)
    }

    @Test
    fun `cleanDeployedFiles can be set to false`() {
        val config = runConfig()
        config.cleanDeployedFiles.set(false)
        assertTrue(config.cleanDeployedFiles.get() == false)
    }
    // =========================================================================
    //  useDeployRun (run sub-config)
    // =========================================================================

    @Test
    fun `useDeployRun defaults to true`() {
        val project = ProjectBuilder.builder().build()
        val config = project.objects.newInstance(MindustryRunConfig::class.java)
        assertTrue(config.useDeployRun.get() == true)
    }
    @Test
    fun `useDeployRun can be set to false`() {
        val project = ProjectBuilder.builder().build()
        val config = project.objects.newInstance(MindustryRunConfig::class.java)
        config.useDeployRun.set(false)
        assertTrue(config.useDeployRun.get() == false)
    }
    // =========================================================================
    //  maxLogFiles (debug sub-config)
    // =========================================================================

    @Test
    fun `maxLogFiles defaults to 25`() {
        val project = ProjectBuilder.builder().build()
        val config = project.objects.newInstance(MindustryDebugConfig::class.java)
        assertTrue(config.maxLogFiles.get() == 25)
        assertTrue(MindustryDebugConfig.DEFAULT_MAX_LOG_FILES == 25)
    }
    @Test
    fun `maxLogFiles can be customized`() {
        val project = ProjectBuilder.builder().build()
        val config = project.objects.newInstance(MindustryDebugConfig::class.java)
        config.maxLogFiles.set(10)
        assertTrue(config.maxLogFiles.get() == 10)
    }
    // =========================================================================
    //  enableRunLogging (debug sub-config)
    // =========================================================================

    @Test
    fun `enableRunLogging defaults to true`() {
        val project = ProjectBuilder.builder().build()
        val config = project.objects.newInstance(MindustryDebugConfig::class.java)
        assertTrue(config.enableRunLogging.get() == true)
        assertTrue(MindustryDebugConfig.DEFAULT_ENABLE_RUN_LOGGING)
    }
    @Test
    fun `enableRunLogging can be set to false`() {
        val project = ProjectBuilder.builder().build()
        val config = project.objects.newInstance(MindustryDebugConfig::class.java)
        config.enableRunLogging.set(false)
        assertTrue(config.enableRunLogging.get() == false)
    }
    // =========================================================================
    //  debugging switches (debug sub-config)
    // =========================================================================

    @Test
    fun `debug switches default to off on port 5005`() {
        val project = ProjectBuilder.builder().build()
        val config = project.objects.newInstance(MindustryDebugConfig::class.java)
        assertTrue(!config.enableDebug.get(), "debugging must stay off unless asked for")
        assertTrue(!config.debugSuspend.get())
        assertTrue(config.debugPort.get() == MindustryDebugConfig.DEFAULT_DEBUG_PORT)
        assertTrue(config.debugPort.get() == 5005)
    }

    @Test
    fun `debug switches can be enabled`() {
        val project = ProjectBuilder.builder().build()
        val config = project.objects.newInstance(MindustryDebugConfig::class.java)
        config.enableDebug.set(true)
        config.debugSuspend.set(true)
        config.debugPort.set(5011)
        assertTrue(config.enableDebug.get() && config.debugSuspend.get())
        assertTrue(config.debugPort.get() == 5011)
    }

    // =========================================================================
    //  androidSdkDir (build sub-config)
    // =========================================================================

    @Test
    fun `androidSdkDir defaults to unset`() {
        val project = ProjectBuilder.builder().build()
        val config = project.objects.newInstance(MindustryBuildConfig::class.java)
        assertTrue(!config.androidSdkDir.isPresent)
    }
    @Test
    fun `androidSdkDir can be set`() {
        val project = ProjectBuilder.builder().build()
        val config = project.objects.newInstance(MindustryBuildConfig::class.java)
        val sdkDir = project.layout.projectDirectory.dir("sdk")
        config.androidSdkDir.set(sdkDir)
        assertTrue(config.androidSdkDir.get().asFile == sdkDir.asFile)
    }
    // =========================================================================
    //  d8Args (build sub-config)
    // =========================================================================

    @Test
    fun `d8Args defaults to empty`() {
        val project = ProjectBuilder.builder().build()
        val config = project.objects.newInstance(MindustryBuildConfig::class.java)
        assertTrue(config.d8Args.get().isEmpty())
    }
    @Test
    fun `d8Args can be set`() {
        val project = ProjectBuilder.builder().build()
        val config = project.objects.newInstance(MindustryBuildConfig::class.java)
        config.d8Args.set(listOf("--no-desugaring", "--release"))
        assertTrue(config.d8Args.get() == listOf("--no-desugaring", "--release"))
    }

    // =========================================================================
    //  d8TimeoutMinutes (build sub-config)
    // =========================================================================

    @Test
    fun `d8TimeoutMinutes defaults to 30 minutes`() {
        val project = ProjectBuilder.builder().build()
        val config = project.objects.newInstance(MindustryBuildConfig::class.java)
        assertTrue(MindustryBuildConfig.DEFAULT_D8_TIMEOUT_MINUTES == 30L)
        assertTrue(config.d8TimeoutMinutes.get() == 30L, "got ${config.d8TimeoutMinutes.get()}")
    }
    @Test
    fun `d8TimeoutMinutes can be set`() {
        val project = ProjectBuilder.builder().build()
        val config = project.objects.newInstance(MindustryBuildConfig::class.java)
        config.d8TimeoutMinutes.set(5L)
        assertTrue(config.d8TimeoutMinutes.get() == 5L)
    }

    // =========================================================================
    //  android SDK download settings (download sub-config, consumed by jarAndroid)
    // =========================================================================

    @Test
    fun `androidSdkAutoDownload defaults to false`() {
        val project = ProjectBuilder.builder().build()
        val config = project.objects.newInstance(MindustryDownloadConfig::class.java)
        assertTrue(MindustryDownloadConfig.DEFAULT_ANDROID_SDK_AUTO_DOWNLOAD == false)
        assertTrue(config.androidSdkAutoDownload.get() == false, "got ${config.androidSdkAutoDownload.get()}")
    }
    @Test
    fun `androidSdkAutoDownload can be enabled`() {
        val project = ProjectBuilder.builder().build()
        val config = project.objects.newInstance(MindustryDownloadConfig::class.java)
        config.androidSdkAutoDownload.set(true)
        assertTrue(config.androidSdkAutoDownload.get())
    }

    @Test
    fun `android sdk download settings live on the download config`() {
        val project = ProjectBuilder.builder().build()
        val config = project.objects.newInstance(MindustryDownloadConfig::class.java)
        assertTrue(
            config.androidSdkDownloadPackages.get() ==
                MindustryDownloadConfig.DEFAULT_ANDROID_SDK_DOWNLOAD_PACKAGES
        )
        assertTrue(
            config.androidSdkDownloadPackages.get() == listOf("platforms;android-34", "build-tools;34.0.0"),
            "got ${config.androidSdkDownloadPackages.get()}"
        )
        assertTrue(
            config.androidSdkDownloadTimeoutMinutes.get() ==
                MindustryDownloadConfig.DEFAULT_ANDROID_SDK_DOWNLOAD_TIMEOUT_MINUTES
        )
        assertTrue(config.androidSdkDownloadTimeoutMinutes.get() == 30L)
    }

    @Test
    fun `commandLineToolsUrl picks the per-os official build`() {
        assertTrue(MindustryDownloadConfig.commandLineToolsUrl("Mac OS X").contains("commandlinetools-mac-"))
        assertTrue(MindustryDownloadConfig.commandLineToolsUrl("Darwin").contains("commandlinetools-mac-"))
        assertTrue(MindustryDownloadConfig.commandLineToolsUrl("Windows 11").contains("commandlinetools-win-"))
        assertTrue(MindustryDownloadConfig.commandLineToolsUrl("Linux").contains("commandlinetools-linux-"))
        // Anything unknown falls back to the Linux build rather than a 404.
        assertTrue(MindustryDownloadConfig.commandLineToolsUrl("Plan 9").contains("commandlinetools-linux-"))
        assertTrue(MindustryDownloadConfig.commandLineToolsUrl("Linux").startsWith("https://dl.google.com/"))
    }

    // =========================================================================
    //  build counter (atomic increment)
    // =========================================================================

    @Test
    fun `concurrent increments produce distinct numbers`() {
        val project = ProjectBuilder.builder().build()
        val ext = project.extensions.create("mindustryMod", MindustryModExtension::class.java, project)
        val threads = 8
        val perThread = 25
        val numbers = java.util.Collections.synchronizedList(mutableListOf<Int>())

        val workers = (1..threads).map {
            Thread {
                repeat(perThread) { numbers.add(ext.incrementBuildCounter()) }
            }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join() }

        val expected = threads * perThread
        assertTrue(numbers.size == expected, "expected $expected numbers, got ${numbers.size}")
        assertTrue(numbers.toSet().size == expected, "lost update: only ${numbers.toSet().size} distinct numbers")
        assertTrue(ext.readBuildCounter() == expected, "the file must hold the last number, got ${ext.readBuildCounter()}")
    }

    // =========================================================================
    //  modMeta is a top-level extension
    // =========================================================================

    @Test
    fun `modMeta is registered as its own top-level extension`() {
        val project = ProjectBuilder.builder().build()
        val meta = project.extensions.create("modMeta", ModMeta::class.java)
        val ext = project.extensions.create("mindustryMod", MindustryModExtension::class.java, project)

        assertTrue(project.extensions.getByName("modMeta") === meta)
        assertTrue(ext.modMeta === meta, "mindustryMod.modMeta must be the same instance")
    }

    @Test
    fun `top level modMeta feeds the mod extension`() {
        val project = ProjectBuilder.builder().build()
        project.extensions.create("modMeta", ModMeta::class.java)
        val ext = project.extensions.create("mindustryMod", MindustryModExtension::class.java, project)

        // Configured through the top-level block, read through the mod extension.
        val meta = project.extensions.getByType(ModMeta::class.java)
        meta.name = "FromTopLevel"
        meta.version = "9.9"
        assertTrue(ext.modMeta.name == "FromTopLevel")
        assertTrue(ext.modVersion(TargetPlatform.Jar).get().startsWith("FromTopLevel-9.9"))
    }

    @Test
    fun `mindustryMod modMeta block writes the same object`() {
        val project = ProjectBuilder.builder().build()
        project.extensions.create("modMeta", ModMeta::class.java)
        val ext = project.extensions.create("mindustryMod", MindustryModExtension::class.java, project)

        ext.modMeta { it.name = "FromNested" }
        assertTrue(project.extensions.getByType(ModMeta::class.java).name == "FromNested")
    }

    @Test
    fun `modMeta is created on demand when the plugin did not register it`() {
        val project = ProjectBuilder.builder().build()
        val ext = project.extensions.create("mindustryMod", MindustryModExtension::class.java, project)

        ext.modMeta.name = "Lazy"
        assertTrue(project.extensions.getByType(ModMeta::class.java).name == "Lazy")
    }

    // =========================================================================
    //  useHJson (build sub-config)
    // =========================================================================

    @Test
    fun `useHJson defaults to false`() {
        val project = ProjectBuilder.builder().build()
        val config = project.objects.newInstance(MindustryBuildConfig::class.java)
        assertTrue(MindustryBuildConfig.DEFAULT_USE_HJSON == false)
        assertTrue(config.useHJson.get() == false, "got ${config.useHJson.get()}")
    }
    @Test
    fun `useHJson can be enabled`() {
        val project = ProjectBuilder.builder().build()
        val config = project.objects.newInstance(MindustryBuildConfig::class.java)
        config.useHJson.set(true)
        assertTrue(config.useHJson.get())
    }

    // =========================================================================
    //  the four sub-configs are reachable through their DSL blocks
    // =========================================================================

    @Test
    fun `root extension exposes download run build and debug sub-configs`() {
        val project = ProjectBuilder.builder().build()
        val ext = project.extensions.create("mindustryModRoot", MindustryModRootExtension::class.java)
        // In a build script Gradle turns these Action parameters into receiver lambdas; in a plain
        // unit test the SAM conversion hands the config over as a parameter instead.
        ext.download { it.mindustryDownloadVersion.set("150") }
        ext.run { it.deployTag.set("tag") }
        ext.build { it.jarSuffix.set("-Desk") }
        ext.debug { it.debugPort.set(5010) }
        assertTrue(ext.download.mindustryDownloadVersion.get() == "150")
        assertTrue(ext.run.deployTag.get() == "tag")
        assertTrue(ext.build.jarSuffix.get() == "-Desk")
        assertTrue(ext.debug.debugPort.get() == 5010)
    }
}
