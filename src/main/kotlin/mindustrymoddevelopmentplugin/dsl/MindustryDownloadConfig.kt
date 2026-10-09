package mindustrymoddevelopmentplugin.dsl

import mindustrymoddevelopmentplugin.MindustryModPlugin
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * Download configuration child (`mindustryModRoot { download { ... } }`).
 *
 * Everything this plugin downloads: the game jar and the Android SDK that `jarAndroid` may install.
 */
abstract class MindustryDownloadConfig {

    /** Mindustry version to download / run. Default [DEFAULT_VERSION]. */
    abstract val mindustryDownloadVersion: Property<String>

    /** Mindustry release download base URL. Default [DEFAULT_URL]. */
    abstract val mindustryDownloadUrl: Property<String>

    /**
     * Storage path or direct path of the game jar.
     *
     * - Ending in `.jar` → treated as a file path and used directly (download is skipped if it exists).
     * - Anything else   → treated as a directory, with the file name appended automatically.
     *
     * Default `<rootProject>/build/game/` (directory mode).
     * The default is registered as a `convention` in [MindustryModPlugin] while the plugin is
     * applied, and depends on the configured download version.
     */
    abstract val mindustryGamePath: RegularFileProperty

    /**
     * File name template for the downloaded jar, supporting the `{version}` placeholder.
     *
     * For example `"Mindustry-{version}"` → `Mindustry-146.jar`.
     * Spaces or special characters (`\ / : * ? " < > |`) fail immediately.
     * Chinese characters produce a warning.
     *
     * Default [DEFAULT_FILE_NAME].
     */
    abstract val mindustryDownloadFileName: Property<String>

    // ---- Android SDK download (consumed by jarAndroid) ----

    /**
     * Whether `jarAndroid` installs an Android SDK when none is usable.
     *
     * Optional — off by default, so a build never starts a multi-hundred-megabyte download on its
     * own. When enabled and the resolved SDK is missing, empty or has no usable `platforms` /
     * `build-tools` (including [MindustryBuildConfig.androidSdkDir] pointing at an empty directory),
     * the plugin downloads the official command-line tools from [androidSdkDownloadUrl] and runs
     * `sdkmanager` for [androidSdkDownloadPackages].
     */
    abstract val androidSdkAutoDownload: Property<Boolean>

    /**
     * Where the auto-installation fetches the command-line tools from.
     *
     * Defaults to the official Google build for the current OS ([commandLineToolsUrl]), so pointing
     * this at a mirror is all it takes to use another channel. The archive must be the official
     * `commandlinetools-<os>-<build>_latest.zip` layout.
     *
     * **Only point this at a channel you trust**: the archive is unpacked and its `sdkmanager` is
     * executed as part of the build, and the download is verified by TLS alone (no checksum).
     */
    abstract val androidSdkDownloadUrl: Property<String>

    /**
     * `sdkmanager` package specs the auto-installation downloads and installs, e.g.
     * `"platforms;android-34"`. Default [DEFAULT_ANDROID_SDK_DOWNLOAD_PACKAGES].
     *
     * Add `"platform-tools"` only if something else in your setup needs `adb`.
     */
    abstract val androidSdkDownloadPackages: ListProperty<String>

    /**
     * Whether to install the platform packages even when a `d8` already resolved.
     *
     * `android.jar` is the desugaring classpath and is architecture independent — it is a zip of Java
     * classes — so it is useful even where `build-tools` cannot run at all. That is the Termux case:
     * `pkg install d8` supplies the dexer, and this supplies the platform. Off by default, because it
     * changes what an otherwise finished toolchain installs.
     */
    abstract val androidSdkDownloadPlatformOnly: Property<Boolean>

    /**
     * Extra command-line flags for `sdkmanager`, appended to both the license and the package run.
     * Default is empty.
     *
     * `sdkmanager` has no option for pointing at another *repository*: it always reads Google's own
     * package list, so a mirror has to be reached as an HTTP proxy. That is what this is for, for
     * example `--proxy=http --proxy_host=<mirror host> --proxy_port=80` (add `--no_https` for a proxy
     * that only speaks HTTP), which is the mechanism the classic Android SDK mirrors document. Other
     * useful flags: `--channel=3` for preview packages, `--include_obsolete` for withdrawn ones.
     *
     * Flags that would fight the plugin's own invocation are rejected: `--sdk_root` (always set to the
     * SDK being installed), and the action flags `--list`, `--install`, `--uninstall`, `--licenses`,
     * `--version` and `--help`. Package specs belong in [androidSdkDownloadPackages], not here.
     */
    abstract val androidSdkExtraArgs: ListProperty<String>

    /**
     * Timeout for the command-line tools download and for each `sdkmanager` call, **in minutes** —
     * a download timeout, not a limit on how long the game may run.
     *
     * The tools are ~140 MB and the packages another ~130 MB, so this is generous on purpose.
     * Default [DEFAULT_ANDROID_SDK_DOWNLOAD_TIMEOUT_MINUTES].
     */
    abstract val androidSdkDownloadTimeoutMinutes: Property<Long>

    init {
        mindustryDownloadVersion.convention(DEFAULT_VERSION)
        mindustryDownloadUrl.convention(DEFAULT_URL)
        mindustryDownloadFileName.convention(DEFAULT_FILE_NAME)
        androidSdkAutoDownload.convention(DEFAULT_ANDROID_SDK_AUTO_DOWNLOAD)
        androidSdkDownloadPackages.convention(DEFAULT_ANDROID_SDK_DOWNLOAD_PACKAGES)
        androidSdkDownloadPlatformOnly.convention(DEFAULT_ANDROID_SDK_DOWNLOAD_PLATFORM_ONLY)
        androidSdkDownloadUrl.convention(commandLineToolsUrl())
        androidSdkDownloadTimeoutMinutes.convention(DEFAULT_ANDROID_SDK_DOWNLOAD_TIMEOUT_MINUTES)
    }

    companion object {

        /** Default value of [mindustryDownloadVersion]. */
        // The v147 release is the first that understands -Dmindustry.data.dir, so the default works with
        // run.gameDataDir instead of only warning about an unsupported version.
        const val DEFAULT_VERSION = "147"

        /** Default value of [mindustryDownloadUrl]. */
        const val DEFAULT_URL = "https://github.com/Anuken/Mindustry/releases/download"

        /** Default value of [mindustryDownloadFileName]. */
        const val DEFAULT_FILE_NAME = "Mindustry-{version}"

        /** Default value of [androidSdkAutoDownload]. */
        const val DEFAULT_ANDROID_SDK_AUTO_DOWNLOAD = false

        /** Default value of [androidSdkDownloadPackages]. */
        val DEFAULT_ANDROID_SDK_DOWNLOAD_PACKAGES = listOf("platforms;android-34", "build-tools;34.0.0")

        /** Default value of [androidSdkDownloadPlatformOnly]. */
        const val DEFAULT_ANDROID_SDK_DOWNLOAD_PLATFORM_ONLY = false

        /** Default value of [androidSdkDownloadTimeoutMinutes], in minutes. */
        const val DEFAULT_ANDROID_SDK_DOWNLOAD_TIMEOUT_MINUTES = 30L

        // ---- Command-line tools URL ----

        /** Build number of the official command-line tools that [commandLineToolsUrl] pins. */
        private const val COMMAND_LINE_TOOLS_BUILD = "13114758"

        /**
         * Official command-line tools download for [osName], the default of
         * [androidSdkDownloadUrl]; anything unrecognized falls back to the Linux build, which at least
         * produces a clear sdkmanager error instead of a 404.
         */
        fun commandLineToolsUrl(osName: String = System.getProperty("os.name")): String {
            val os = osName.lowercase()
            val slug = when {
                os.contains("mac") || os.contains("darwin") -> "mac"
                os.contains("win") -> "win"
                else -> "linux"
            }
            return "https://dl.google.com/android/repository/commandlinetools-$slug-${COMMAND_LINE_TOOLS_BUILD}_latest.zip"
        }
    }
}
