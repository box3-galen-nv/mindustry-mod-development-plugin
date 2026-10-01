package mindustrymoddevelopmentplugin.dsl

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * Build configuration child (`mindustryModRoot { build { ... } }`).
 */
abstract class MindustryBuildConfig {

    /**
     * Generated mod metadata file format. `true` emits `mod.hjson`, `false` emits `mod.json`
     * (the default, [DEFAULT_USE_HJSON]).
     *
     * Only affects *generation* (`generateModMeta`); back-fill and the jar always pick up
     * whichever metadata files already exist.
     */
    abstract val useHJson: Property<Boolean>

    /** Base file name format, supporting `{name}` `{author}` `{version}` `{build_count}` `{time}`. Default [DEFAULT_FORMAT]. */
    abstract val format: Property<String>

    /** Suffix of the `jar` platform artifact. Default [DEFAULT_JAR_SUFFIX]. */
    abstract val jarSuffix: Property<String>

    /** Suffix of the `jarAndroid` platform artifact. Default [DEFAULT_ANDROID_SUFFIX]. */
    abstract val androidSuffix: Property<String>

    /** Suffix of the merged `deploy` artifact. Default [DEFAULT_DEPLOY_SUFFIX] (empty). */
    abstract val deploySuffix: Property<String>

    /** Date format of the `{time}` placeholder. Default [DEFAULT_TIME_FORMAT]. */
    abstract val timeFormat: Property<String>

    /**
     * Android SDK directory (used by `jarAndroid` for d8 compilation).
     *
     * **Optional.** When unset — or set to a path that does not exist — the plugin searches
     * `ANDROID_HOME` → `ANDROID_SDK_ROOT` → common user locations (macOS `~/Library/Android/sdk`,
     * Windows `%LOCALAPPDATA%/Android/Sdk`, Linux `~/Android/Sdk`). A stale path here therefore
     * does not hide a working environment variable.
     *
     * A configured path decides where an SDK is *installed*. With `download.androidSdkAutoDownload`
     * on, an absent or empty directory here is created and populated with
     * `download.androidSdkDownloadPackages`. The
     * installer never touches an SDK it did not install into: one found elsewhere, behind
     * `ANDROID_HOME` for example, is used as-is. With auto-download off the build fails and lists
     * every location it probed.
     */
    abstract val androidSdkDir: DirectoryProperty

    /**
     * An explicit `d8` command to dex with, so no Android SDK is needed at all.
     *
     * Point it at `d8` (Termux's `pkg install d8` installs one) or at a `d8.jar`, which is then run
     * through the build's own JVM. When unset, `d8` on the `PATH` and then the SDK's build-tools are tried.
     */
    abstract val d8Executable: RegularFileProperty

    /**
     * Extra arguments passed to d8 (a list of strings), e.g. `"--no-desugaring"`, `"--release"`.
     * Inserted after the default arguments (`--min-api 14` etc.) and can override them.
     * Default [DEFAULT_D8_ARGS] (empty).
     */

    abstract val d8Args: ListProperty<String>

    /**
     * Timeout for the d8 subprocess spawned by `jarAndroid`, **in minutes**.
     *
     * On timeout d8 is killed forcibly and the build fails, so a hung d8 cannot stall the
     * build indefinitely. Default [DEFAULT_D8_TIMEOUT_MINUTES].
     */
    abstract val d8TimeoutMinutes: Property<Long>

    /**
     * How long to wait for the d8 output drain thread after d8 has exited, in milliseconds.
     *
     * The drain itself runs while d8 works, so this only bounds the final join — long enough for a slow
     * pipe to flush, not a limit on d8 (that is [d8TimeoutMinutes]). Default
     * [DEFAULT_D8_DRAIN_JOIN_MILLIS].
     */
    abstract val d8DrainJoinMillis: Property<Long>

    init {
        useHJson.convention(DEFAULT_USE_HJSON)
        format.convention(DEFAULT_FORMAT)
        jarSuffix.convention(DEFAULT_JAR_SUFFIX)
        androidSuffix.convention(DEFAULT_ANDROID_SUFFIX)
        deploySuffix.convention(DEFAULT_DEPLOY_SUFFIX)
        timeFormat.convention(DEFAULT_TIME_FORMAT)
        d8Args.convention(DEFAULT_D8_ARGS)
        d8TimeoutMinutes.convention(DEFAULT_D8_TIMEOUT_MINUTES)
        d8DrainJoinMillis.convention(DEFAULT_D8_DRAIN_JOIN_MILLIS)
    }

    companion object {
        // Every default is a named constant referenced by init { }, so the block above never
        // mixes inline literals with named defaults.

        /** Default value of [useHJson]. */
        val DEFAULT_USE_HJSON = false

        /** Default value of [format]. */
        val DEFAULT_FORMAT = "{name}-{version}.{build_count}"

        /** Default value of [jarSuffix]. */
        val DEFAULT_JAR_SUFFIX = "-Jar"

        /** Default value of [androidSuffix]. */
        val DEFAULT_ANDROID_SUFFIX = "-Android"

        /** Default value of [deploySuffix]. */
        val DEFAULT_DEPLOY_SUFFIX = ""

        /** Default value of [timeFormat]. */
        val DEFAULT_TIME_FORMAT = "yyyyMMdd_HHmmss"

        /** Default value of [d8Args]. */
        val DEFAULT_D8_ARGS: List<String> = emptyList()

        /** Default value of [d8TimeoutMinutes], in minutes. */
        const val DEFAULT_D8_TIMEOUT_MINUTES = 30L

        /** Default value of [d8DrainJoinMillis], in milliseconds. */
        const val DEFAULT_D8_DRAIN_JOIN_MILLIS = 5_000L
    }
}
