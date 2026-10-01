package mindustrymoddevelopmentplugin

import java.io.File
import org.gradle.api.GradleException

/**
 * Resolves the directory Mindustry keeps its user data in, the way the engine itself does.
 *
 * This is the default behind `run.gameDataDir`, and therefore behind the `<dataDir>/mods` path the
 * plugin deploys into. The order matches `ClientLauncher`: `MINDUSTRY_DATA_DIR` first
 * (the game reads that variable since v126), otherwise the per-OS application data directory.
 */
internal object GameDataDir {

    /** Engine variable that overrides the data directory; honoured by the game since v126. */
    const val ENV_VAR = "MINDUSTRY_DATA_DIR"

    /** The Android client's application id; the BE build rewrites it to `io.anuke.mindustry.be`. */
    const val ANDROID_APP_ID = "io.anuke.mindustry"

    /**
     * `/storage/emulated/0/Android/data/<appId>/files` — what `getExternalFilesDir(null)` returns on
     * Android, and the only data directory the APK ever uses.
     */
    fun androidDataDir(appId: String): File = File("/storage/emulated/0/Android/data/$appId/files")

    /**
     * Returns the data directory, preferring [env] over the per-OS location.
     *
     * Every input is a parameter so the resolution can be tested without touching the real
     * environment or the running JVM's system properties.
     *
     * @throws GradleException on an operating system whose directory is not known, naming the DSL
     *   property to set instead of guessing.
     */

    fun resolve(
        env: String? = System.getenv(ENV_VAR),
        osName: String = System.getProperty("os.name"),
        userHome: String = System.getProperty("user.home"),
        appData: String? = System.getenv("APPDATA"),
        hostPlatform: HostPlatform = HostPlatform.detect(),
        androidAppId: String = ANDROID_APP_ID,
    ): File {
        if (!env.isNullOrBlank()) return File(env)

        // Android comes first because `os.name` says "Linux" there too, which would give the desktop path.
        // The client ignores both MINDUSTRY_DATA_DIR and -Dmindustry.data.dir: AndroidLauncher sets the data
        // directory to getExternalFilesDir(null) unconditionally, and that is this path.
        if (hostPlatform == HostPlatform.Android) return androidDataDir(androidAppId)

        val os = osName.lowercase()
        return when {
            os.contains("mac") -> File(userHome, "Library/Application Support/Mindustry")
            os.contains("win") -> File(appData ?: "$userHome/AppData/Roaming", "Mindustry")
            os.contains("linux") -> File(userHome, ".local/share/Mindustry")
            else -> throw GradleException(
                "Unsupported OS '$osName', so the Mindustry data directory cannot be inferred. " +
                "Set it yourself (the mods path follows it):\n" +
                "  mindustryModRoot {\n" +
                "      run {\n" +
                "          gameDataDir = file(\"/path/to/Mindustry\")\n" +
                "      }\n" +
                "  }"
            )
        }
    }
}
