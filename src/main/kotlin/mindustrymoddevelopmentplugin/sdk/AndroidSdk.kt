package mindustrymoddevelopmentplugin.sdk

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
        // The *first* number group, not the last: `android-30-ext7` is platform 30, and taking the last
        // one compared it as 7 — older than `android-9`, so the wrong directory could win.
        val m = SDK_VERSION_PATTERN.find(dir.name)?.value ?: return emptyList()
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
        /*
        A configured directory that is *not there* must not end the search: it used to return
        null immediately, which hid a perfectly good ANDROID_HOME behind a stale path. Callers
        that want to create the configured path do it themselves (resolveOrInstallSdk).
        */
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
     * Locates a d8 command *without needing an Android SDK*, in this order:
     *
     * 1. [configured] — `build.d8Executable`, the explicit override;
     * 2. `d8` on [pathEnv] — what `pkg install d8` provides on Termux;
     * 3. the SDK's newest `build-tools`, as `d8`, `d8.bat`, or `lib/d8.jar`.
     *
     * The result is a **command prefix**, because the last case is not an executable: build-tools ships a
     * `d8` shell script wrapping `lib/d8.jar`, and Android has no `/bin/sh`, so that script cannot run
     * there while the jar always can. Prefixes are also why [buildD8Command] takes a list.
     *
     * @return the prefix, or null when no d8 exists anywhere.
     */
    fun resolveD8(
        configured: File?,
        pathEnv: String?,
        sdkRoot: File?,
        osName: String = System.getProperty("os.name"),
    ): List<String>? {
        if (configured != null && configured.isFile) return executableOrJar(configured)
        onPath(pathEnv, osName)?.let { return listOf(it.absolutePath) }

        val buildTools = sdkRoot?.let { File(it, "build-tools") }
            ?.listFiles()
            ?.filter { it.isDirectory }
            ?.filter {
                File(it, "d8").exists() || File(it, "d8.bat").exists() || File(it, "lib/d8.jar").exists()
            }
            ?.maxWithOrNull(Comparator(::compareSdkVersions))
            ?: return null

        return when {
            File(buildTools, "d8").exists() -> listOf(File(buildTools, "d8").absolutePath)
            File(buildTools, "d8.bat").exists() -> listOf(File(buildTools, "d8.bat").absolutePath)
            else -> executableOrJar(File(buildTools, "lib/d8.jar"))
        }
    }

    /** `<sdk>/platforms/<newest>/android.jar`, or null: d8 works without it, just less precisely. */
    fun findAndroidJar(sdkRoot: File): File? =
        File(sdkRoot, "platforms").listFiles()
            ?.filter { it.isDirectory && File(it, "android.jar").exists() }
            ?.maxWithOrNull(Comparator(::compareSdkVersions))
            ?.let { File(it, "android.jar") }

    /** A `.jar` given as the d8 command has to go through the JVM; anything else is run directly. */
    private fun executableOrJar(file: File): List<String> =
        if (file.extension.equals("jar", ignoreCase = true)) {
            listOf(javaExecutable(), "-cp", file.absolutePath, "com.android.tools.r8.D8")
        } else {
            listOf(file.absolutePath)
        }

    /** The JVM running the build, so the `lib/d8.jar` fallback does not depend on `java` being on PATH. */
    private fun javaExecutable(): String {
        val executable = if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
        return File(File(System.getProperty("java.home"), "bin"), executable).absolutePath
    }

    /** First executable named [name] (plus the Windows launcher suffixes) in the `PATH`-style [pathEnv]. */
    private fun onPath(pathEnv: String?, osName: String): File? {
        if (pathEnv.isNullOrBlank()) return null
        val suffixes = if (osName.lowercase().contains("win")) listOf(".exe", ".bat", ".cmd", "") else listOf("")
        return pathEnv.split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .asSequence()
            .flatMap { dir -> suffixes.asSequence().map { File(dir, "d8" + it) } }
            .firstOrNull { it.isFile }
    }

    /**
     * Assemble the d8 command line. Custom [extraArgs] are inserted after the defaults
     * (`--min-api` etc.) so they can override them, and before `--output`.
     */
    fun buildD8Command(
        d8Command: List<String>,
        deps: Collection<File>,
        minApi: Int = 14,
        extraArgs: List<String> = emptyList(),
        output: File,
        input: File,
    ): List<String> {
        val args = d8Command.toMutableList()
        deps.forEach { args.add("--classpath"); args.add(it.absolutePath) }
        args.add("--min-api"); args.add(minApi.toString())
        args.addAll(extraArgs)
        args.add("--output"); args.add(output.absolutePath)
        args.add(input.absolutePath)
        return args
    }
}
