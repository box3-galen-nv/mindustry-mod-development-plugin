package mindustrymoddevelopmentplugin.tasks

import java.io.File
import org.gradle.api.GradleException

/**
 * Android SDK discovery and d8 command-line assembly.
 *
 * SDK lookup order: `build.androidSdkDir` → `ANDROID_HOME` → `ANDROID_SDK_ROOT` →
 * common user-home locations ([defaultAndroidSdkDirs]); platform / build-tools are
 * ordered naturally by version number, not as strings.
 */
internal object AndroidSdk {

    /** Trailing version-number segment of an SDK directory name (e.g. `android-30`, `34.0.0`); compiled once. */
    private val SDK_VERSION_PATTERN = Regex("""(\d+(?:\.\d+)*)""")

    /**
     * Parse the version-number segments of an SDK directory name (e.g. `"android-30"` → `[30]`,
     * `"34.0.0"` → `[34,0,0]`). Takes the **last** numeric segment of the name
     * (`"android-30-ext7"` still parses as `[30]`). Used for natural version ordering, so that
     * string sort does not place `"android-9"` before `"android-30"`.
     */
    internal fun sdkVersionParts(dir: File): List<Int> {
        val m = SDK_VERSION_PATTERN.findAll(dir.name).lastOrNull()?.value ?: return emptyList()
        return m.split('.').mapNotNull { it.toIntOrNull() }
    }

    /** Compare two SDK directories by version (segment-by-segment numeric; fewer segments sorts lower). */
    internal fun compareSdkVersions(a: File, b: File): Int {
        val va = sdkVersionParts(a)
        val vb = sdkVersionParts(b)
        val len = minOf(va.size, vb.size)
        for (i in 0 until len) {
            val c = va[i].compareTo(vb[i])
            if (c != 0) return c
        }
        return va.size.compareTo(vb.size)
    }

    /** User-home locations where Android Studio installs the SDK by default. */
    fun defaultAndroidSdkDirs(
        userHome: File,
        env: Map<String, String> = System.getenv(),
        osName: String = System.getProperty("os.name"),
    ): List<File> {
        val os = osName.lowercase()
        return when {
            os.contains("mac") -> listOf(File(userHome, "Library/Android/sdk"))
            os.contains("win") -> listOfNotNull(
                env["LOCALAPPDATA"]?.let { File(it, "Android/Sdk") },
                File(userHome, "AppData/Local/Android/Sdk"),
            )
            else -> listOf(File(userHome, "Android/Sdk"), File(userHome, ".android/sdk"))
        }
    }

    /**
     * Resolve the Android SDK directory for `jarAndroid`, in order:
     *
     * 1. `build.androidSdkDir` (if configured and actually present)
     * 2. `ANDROID_HOME`, then `ANDROID_SDK_ROOT`
     * 3. common user-home locations ([defaultAndroidSdkDirs])
     *
     * Throws with a helpful message if nothing valid is found. Use [findAndroidSdkDir] when
     * "no SDK yet" is a recoverable state — the auto-installation path does exactly that instead of
     * failing.
     */
    fun resolveAndroidSdkDir(
        configured: File?,
        env: Map<String, String> = System.getenv(),
        userHome: File = File(System.getProperty("user.home")),
        osName: String = System.getProperty("os.name"),
    ): File {
        findAndroidSdkDir(configured, env, userHome, osName)?.let { return it }

        // Report every candidate, not just the first ones, to make the failure actionable.
        val tried = mutableListOf<String>()
        // A configured path is listed first: it is the one the user can fix.
        if (configured != null) tried.add("${configured.path} (build.androidSdkDir)")
        for ((name, value) in listOf(
            "ANDROID_HOME" to env["ANDROID_HOME"],
            "ANDROID_SDK_ROOT" to env["ANDROID_SDK_ROOT"],
        )) {
            if (value != null) tried.add("$name=$value")
        }
        defaultAndroidSdkDirs(userHome, env, osName).forEach { tried.add(it.path) }

        throw GradleException(
            "No valid Android SDK found. Tried: ${tried.joinToString("; ")}\n" +
            "Configure it via mindustryModRoot { build { androidSdkDir = file(\"<sdk-path>\") } }, " +
            "or set the ANDROID_HOME / ANDROID_SDK_ROOT environment variable."
        )
    }

    /**
     * Non-throwing counterpart of [resolveAndroidSdkDir]: returns an existing SDK directory
     * or null, using the same lookup order. Used before deciding whether to auto-install.
     */
    fun findAndroidSdkDir(
        configured: File?,
        env: Map<String, String> = System.getenv(),
        userHome: File = File(System.getProperty("user.home")),
        osName: String = System.getProperty("os.name"),
    ): File? {
        // A configured directory that is *not there* must not end the search: it used to return
        // null immediately, which hid a perfectly good ANDROID_HOME behind a stale path. Callers
        // that want to create the configured path do it themselves (resolveOrInstallSdk).
        if (configured != null && configured.isDirectory) return configured

        for (value in listOf(env["ANDROID_HOME"], env["ANDROID_SDK_ROOT"])) {
            if (value != null) {
                val dir = File(value)
                if (dir.isDirectory) return dir
            }
        }

        return defaultAndroidSdkDirs(userHome, env, osName).firstOrNull { it.isDirectory }
    }

    /**
     * Extra hint when d8 rejects the input bytecode.
     *
     * The bundled d8 only understands class files up to a certain version, and reports the mismatch
     * as `Unsupported class file major version NN` — with nothing about what to change. Observed
     * for real when the mod was built with a newer JDK than the bundled build-tools supports.
     */
    fun d8FailureHint(output: String): String {
        if (!output.contains("Unsupported class file major version")) return ""
        return "\n\nThe mod was compiled to a newer bytecode level than this d8 understands. Lower the " +
            "compiler target (for example Kotlin `compilerOptions { jvmTarget = JvmTarget.JVM_17 }`, or " +
            "Java `sourceCompatibility`/`targetCompatibility = JavaVersion.VERSION_17`) and rebuild. " +
            "A newer `build-tools` in download.androidSdkDownloadPackages also raises the supported level."
    }

    /**
     * Assemble the d8 command line. Custom [extraArgs] are inserted after the defaults
     * (`--min-api` etc.) so they can override them, and before `--output`.
     */
    fun buildD8Command(
        d8Binary: String,
        deps: Collection<File>,
        minApi: Int = 14,
        extraArgs: List<String> = emptyList(),
        output: File,
        input: File,
    ): List<String> {
        val args = mutableListOf(d8Binary)
        deps.forEach { args.add("--classpath"); args.add(it.absolutePath) }
        args.add("--min-api"); args.add(minApi.toString())
        args.addAll(extraArgs)
        args.add("--output"); args.add(output.absolutePath)
        args.add(input.absolutePath)
        return args
    }
}
