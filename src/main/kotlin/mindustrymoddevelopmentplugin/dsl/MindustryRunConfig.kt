package mindustrymoddevelopmentplugin.dsl

import mindustrymoddevelopmentplugin.platform.HostPlatform
import mindustrymoddevelopmentplugin.game.GameDataDir
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property

/**
 * Run configuration child (`mindustryModRoot { run { ... } }`).
 *
 * Holds everything about launching the game: the deployment, the debugging and logging switches, and
 * the Android SDK settings that `jarAndroid` needs.
 *
 * The SDK settings live here rather than in `build { }` because installing an SDK means downloading
 * one. `build { }` keeps only [MindustryBuildConfig.androidSdkDir], the location of an SDK that is
 * already there.
 */
abstract class MindustryRunConfig {

    // ---- Where the game stores its data ----

    /**
     * Directory the game keeps its user data in — `settings.bin`, `saves/`, `screenshots/`, and the
     * `mods/` folder the plugin deploys into (`<gameDataDir>/mods`, created when missing).
     *
     * When unset, the game's own directory is used, resolved the way the engine resolves it:
     * `MINDUSTRY_DATA_DIR` when that variable is set, otherwise the per-OS application directory
     * (`~/Library/Application Support/Mindustry`, `%AppData%/Mindustry`,
     * `~/.local/share/Mindustry`).
     *
     * Setting it makes the plugin pass `-Dmindustry.data.dir=<absolute path>` to the game JVM — for a
     * project-local directory write `gameDataDir = layout.projectDirectory.dir("data")`. That property exists from Mindustry
     * **v147**; an older game only gets a warning and falls back to the variable or its own default.
     */
    abstract val hostPlatform: Property<HostPlatform>

    /**
     * The Android application id whose `files` directory holds the game data, used when
     * [hostPlatform] is [HostPlatform.Android]. The BE build uses `io.anuke.mindustry.be`.
     */
    abstract val androidAppId: Property<String>

    /**
     * Where this build is expected to run: [HostPlatform.Auto] decides from the environment, and setting
     * it explicitly is how a desktop CI job describes a Termux target (or the other way round).
     */
    abstract val gameDataDir: DirectoryProperty

    /**
     * Tag that identifies a file as deployed by this plugin: artifacts land under `<gameDataDir>/mods`
     * as `[<tag>]<artifact name>.jar`, and [cleanDeployedFiles] removes them again by that tag.
     *
     * Default [DEFAULT_DEPLOY_TAG]. Keep it short and free of characters that a file name cannot hold,
     * and prefer something that will not collide with another tool's files.
     */
    abstract val deployTag: Property<String>

    /**
     * Whether `clearMods` removes the files this plugin deployed earlier, before the run deploys the
     * new ones. Default [DEFAULT_CLEAN_DEPLOYED_FILES].
     *
     * Turn it off to keep old deployments around (for example to compare two builds); the plugin then
     * never deletes anything under `<gameDataDir>/mods`.
     */
    abstract val cleanDeployedFiles: Property<Boolean>

    /**
     * Whether `runMindustry` copies the merged `deploy` artifact instead of `jar` alone. When `true`
     * the Android DEX compilation is used, when `false` it is skipped. Default [DEFAULT_USE_DEPLOY_RUN].
     *
     * This only chooses *which* artifact is copied — `runMindustry` never depends on that task. Build
     * first (`./gradlew deploy runMindustry`) or wire the dependency in the build script.
     */
    abstract val useDeployRun: Property<Boolean>


    // ---- Android SDK install target (consumed by jarAndroid) ----

    /**
     * Directory the SDK is installed into when [MindustryBuildConfig.androidSdkDir] is unset.
     *
     * Defaults to `<gradleUserHome>/mindustry-mod-development-plugin/android-sdk`, which survives `clean`
     * and is shared by every project using this plugin. When `androidSdkDir` *is* set, the SDK
     * is installed there instead — an empty configured directory is the common case.
     */
    abstract val useHeadlessServer: Property<Boolean>

    /** Working directory of the headless server; its data directory is `<workingDir>/config`. */
    abstract val headlessServerWorkingDir: DirectoryProperty

    /**
     * Where the built jar is copied when running on Android, because the game's own `Android/data`
     * directory cannot be written by other apps on Android 11+. Import it in the game from there.
     */
    abstract val androidStagingDir: DirectoryProperty

    /** After staging, also ask Android to launch the game (`am start`). Off by default. */
    abstract val androidLaunchApk: Property<Boolean>

    /**
     * A Mindustry APK to stage and import. itch.io serves no direct file URL — its downloads go through a
     * session-backed handshake — so the plugin cannot fetch the APK by itself and this is the path the user
     * puts theirs in. Default: `build/game/Mindustry.apk` in the root project.
     */
    abstract val androidApkPath: RegularFileProperty

    /**
     * Optional URL of an APK this build may fetch: the user's own itch.io key link, or a mirror. Empty (the
     * default) means nothing is downloaded, and `downloadAndroidApk` only reports where to get one.
     */
    abstract val androidApkUrl: Property<String>

    /**
     * Whether a run checks the staged APK's version against the one this build compiles against. Only reads
     * the APK's own `assets/version.properties` and warns; it never fails a build and never uses the network.
     */
    abstract val androidApkVersionCheck: Property<Boolean>

    /**
     * Run `server-release.jar` instead of the desktop client.
     *
     * The server is a plain JVM process: it is the only way to debug on Android, and it works on a desktop
     * too (a dedicated server for testing a mod without the GUI).
     */
    abstract val androidSdkInstallDir: DirectoryProperty

    init {
        // The gameDataDir property has no convention here: its default is the root project's directory, which this
        // class cannot see. MindustryModPlugin registers it.
        hostPlatform.convention(HostPlatform.Auto)
        useHeadlessServer.convention(false)
        androidLaunchApk.convention(false)
        androidApkUrl.convention(DEFAULT_ANDROID_APK_URL)
        androidApkVersionCheck.convention(DEFAULT_ANDROID_APK_VERSION_CHECK)
        androidAppId.convention(GameDataDir.ANDROID_APP_ID)
        deployTag.convention(DEFAULT_DEPLOY_TAG)
        cleanDeployedFiles.convention(DEFAULT_CLEAN_DEPLOYED_FILES)
        useDeployRun.convention(DEFAULT_USE_DEPLOY_RUN)
    }

    companion object {

        /** Default value of [deployTag]. */
        const val DEFAULT_DEPLOY_TAG = "mm-deploy"

        /** Default value of [cleanDeployedFiles]. */
        const val DEFAULT_CLEAN_DEPLOYED_FILES = true

        /** Default value of [useDeployRun]. */
        const val DEFAULT_USE_DEPLOY_RUN = true

        /** Default value of [androidApkUrl]: nothing is fetched unless the user names a URL. */
        const val DEFAULT_ANDROID_APK_URL = ""

        /** Default value of [androidApkVersionCheck]. */
        const val DEFAULT_ANDROID_APK_VERSION_CHECK = true

    }
}
