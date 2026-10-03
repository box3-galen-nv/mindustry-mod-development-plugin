package mindustrymoddevelopmentplugin.sdk

import mindustrymoddevelopmentplugin.dsl.MindustryBuildConfig
import mindustrymoddevelopmentplugin.dsl.MindustryDownloadConfig
import java.io.File
import org.gradle.api.file.DirectoryProperty

/**
 * Everything the Android toolchain reads from the root project's `build { }` and `download { }`.
 *
 * Shared by `downloadAndroidSdk` and `jarAndroid`, so it lives with the SDK tooling rather than inside one
 * of them. The values are resolved at configuration time exactly like the DSL properties they come from.
 */
internal class AndroidSdkOptions(
    val androidSdkDir: DirectoryProperty? = null,
    val d8Args: List<String> = emptyList(),
    val d8TimeoutMinutes: Long = MindustryBuildConfig.DEFAULT_D8_TIMEOUT_MINUTES,
    val d8DrainJoinMillis: Long = MindustryBuildConfig.DEFAULT_D8_DRAIN_JOIN_MILLIS,
    val autoDownloadSdk: Boolean = true,
    val sdkDownloadUrl: String = "",
    val sdkDownloadPackages: List<String> = emptyList(),
    val sdkExtraArgs: List<String> = emptyList(),
    val sdkInstallDir: File = File("."),
    val sdkDownloadTimeoutMinutes: Long =
        MindustryDownloadConfig.DEFAULT_ANDROID_SDK_DOWNLOAD_TIMEOUT_MINUTES,
    /**
     * A d8 command resolved at configuration time from `build.d8Executable`, the `PATH`, or an
     * already-installed SDK. Null means "nothing usable yet", which is what makes the SDK get installed
     * instead. It is a prefix rather than one path, because the build-tools fallback is
     * `java -cp lib/d8.jar com.android.tools.r8.D8`.
     */
    val d8Command: List<String>? = null,
)
