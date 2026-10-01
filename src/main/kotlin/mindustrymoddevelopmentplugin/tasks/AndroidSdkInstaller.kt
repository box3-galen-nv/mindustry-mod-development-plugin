package mindustrymoddevelopmentplugin.tasks

import mindustrymoddevelopmentplugin.dsl.MindustryDownloadConfig
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import org.gradle.api.GradleException

/**
 * Installs an Android SDK from the official command-line tools when none is usable.
 *
 * The installation is: download the command-line tools zip, unpack it into
 * `<sdk>/cmdline-tools/latest/` (the layout `sdkmanager` expects), accept the SDK licenses
 * and let `sdkmanager` install the requested packages. Everything is cached, so a second
 * call is a no-op once the packages are present.
 *
 * Kept separate from [AndroidSdk] (which *locates* an SDK) because this is the only part
 * that touches the network and spawns a second external process.
 */
internal object AndroidSdkInstaller {

    private const val TOOLS_ZIP_NAME = "commandlinetools.zip"
    private const val TOOLS_STAGING_DIR = "cmdline-tools-staging"
    private const val TOOLS_DIR = "cmdline-tools/latest"

    /** Directory inside the SDK that holds sdkmanager's own cache/preferences. */
    private const val ANDROID_USER_HOME_DIR = ".android-user"

    /** Flags the plugin passes itself, so the user's extra arguments must not repeat them. */
    private val CONFLICTING_EXTRA_ARGS = setOf(
        "--sdk_root",
        "--list",
        "--install",
        "--uninstall",
        "--licenses",
        "--version",
        "--help",
    )

    /** Number of `y` answers fed to `sdkmanager --licenses`. */
    private const val LICENCE_ANSWERS = 50

    /**
     * True when [sdkRoot] cannot be used for `d8` yet: missing/empty directory, or no
     * `platforms/<ver>/android.jar`, or no `build-tools/<ver>/d8`.
     */
    fun needsInstall(sdkRoot: File?, packages: List<String> = emptyList()): Boolean {
        if (sdkRoot == null || !sdkRoot.isDirectory) return true
        if (sdkRoot.listFiles().isNullOrEmpty()) return true

        // Without a package list there is nothing to verify beyond "usable at all".
        if (packages.isEmpty()) return !hasAnyPlatform(sdkRoot) || !hasAnyBuildTools(sdkRoot)

        return packages.any { !isPackagePresent(sdkRoot, it) }
    }

    private fun hasAnyPlatform(sdkRoot: File): Boolean =
        File(sdkRoot, "platforms").listFiles().orEmpty().any { File(it, "android.jar").isFile }

    private fun hasAnyBuildTools(sdkRoot: File): Boolean =
        File(sdkRoot, "build-tools").listFiles().orEmpty().any { isD8(it) }

    private fun isD8(dir: File): Boolean = File(dir, "d8").isFile || File(dir, "d8.jar").isFile

    /** Whether [isPackagePresent] can decide the spec locally, i.e. it is a `platforms`/`build-tools` one. */
    private fun isCheckableSpec(spec: String): Boolean {
        val group = spec.split(';').firstOrNull()?.trim()
        return group == "platforms" || group == "build-tools"
    }

    /**
     * Whether `sdkmanager`'s work for [spec] is already on disk, e.g. `platforms;android-34` →
     * `<sdk>/platforms/android-34/android.jar`, `build-tools;34.0.0` → `<sdk>/build-tools/34.0.0/d8`.
     *
     * Specs this plugin cannot verify (`platform-tools`, `cmdline-tools;latest`, …) count as
     * satisfied: sdkmanager owns those, and inventing a check for them risks installing on
     * every single build.
     */
    private fun isPackagePresent(sdkRoot: File, spec: String): Boolean {
        val parts = spec.split(';')
        if (parts.size != 2) return true
        val group = parts[0].trim()
        val name = parts[1].trim()
        if (group.isEmpty() || name.isEmpty()) return true

        return when (group) {
            "platforms" -> File(File(sdkRoot, "platforms"), name).let { File(it, "android.jar").isFile }
            "build-tools" -> isD8(File(File(sdkRoot, "build-tools"), name))
            else -> true
        }
    }

    /**
     * The `<sdk>/cmdline-tools/latest/bin/sdkmanager[.bat]` path, even if the file does not exist yet.
     *
     * The installer writes to and reads from this location, so it is computed before unpacking.
     */
    fun sdkManagerFile(sdkRoot: File): File {
        val bin = File(sdkRoot, "$TOOLS_DIR/bin")
        val bat = File(bin, "sdkmanager.bat")
        return if (bat.isFile) bat else File(bin, "sdkmanager")
    }

    /**
     * Ensures [packages] are installed in [sdkRoot], downloading the command-line tools from
     * [toolsUrl] first when they are missing.
     *
     * @param log progress sink, called with human-readable lines.
     * @throws GradleException when the download, the unpack or `sdkmanager` fails.
     */
    fun install(
        sdkRoot: File,
        toolsUrl: String,
        packages: List<String>,
        timeoutMinutes: Long,
        log: (String) -> Unit,
        extraArgs: List<String> = emptyList(),
    ) {
        // Before anything is downloaded: a rejected flag must not cost a 140 MB download first.
        val rejected = extraArgs.filter { it.isConflictingExtraArg() }
        if (rejected.isNotEmpty()) {
            throw GradleException(
                "download.androidSdkExtraArgs contains flag(s) the plugin sets itself: " +
                "${rejected.joinToString(", ")}. It passes --sdk_root=<sdk> and installs " +
                "download.androidSdkDownloadPackages, so only flags that refine that call are allowed " +
                "(e.g. --proxy=http --proxy_host=... --proxy_port=...)."
            )
        }

        sdkRoot.mkdirs()

        if (!sdkManagerFile(sdkRoot).isFile) {
            val zip = download(sdkRoot, toolsUrl, timeoutMinutes, log)
            unpack(zip, sdkRoot, log)
            zip.delete()
        }

        val sdkManager = sdkManagerFile(sdkRoot)
        if (!sdkManager.isFile) {
            throw GradleException(
                "The Android command-line tools were unpacked into '$sdkRoot' but no sdkmanager was found. " +
                "Check download.androidSdkDownloadUrl — it must point at the official commandline tools zip."
            )
        }
        makeExecutable(sdkManager, required = true, log = log)
        // The avdmanager script sits next to sdkmanager and gets the same chmod: zip extraction
        // drops the executable bit, and creating an AVD from this SDK needs it.
        makeExecutable(File(sdkManager.parentFile, "avdmanager"), required = false, log = log)

        // Licenses first, otherwise the installation refuses to run.
        runSdkManager(sdkRoot, listOf("--licenses") + extraArgs, timeoutMinutes, log, answerLicences = true)
        runSdkManager(sdkRoot, packages + extraArgs, timeoutMinutes, log, answerLicences = false)

        // Only packages the plugin can actually verify may be reported missing. Specs such as
        // `platform-tools` or `cmdline-tools;latest` are deliberately delegated to sdkmanager
        // (`isPackagePresent` treats them as satisfied), so an empty `missing` must not fall through
        // to "no platforms/build-tools" — that reported a successful installation as a failure.
        val missing = packages.filterNot { isPackagePresent(sdkRoot, it) }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "sdkmanager finished but '$sdkRoot' does not provide: ${missing.joinToString(", ")}" +
                "\nCheck download.androidSdkDownloadPackages (currently $packages) and the sdkmanager output above."
            )
        }
        // With no checkable spec at all there is nothing to verify, but jarAndroid still needs a
        // platform and build-tools: say so instead of failing the installation.
        if (packages.none { isCheckableSpec(it) } && (!hasAnyPlatform(sdkRoot) || !hasAnyBuildTools(sdkRoot))) {
            log(
                "Warning: '$sdkRoot' has no usable platforms/build-tools and none of the requested " +
                "packages ($packages) can be verified locally; jarAndroid will fail unless a platform " +
                "and build-tools are present."
            )
        }
    }

    private fun download(sdkRoot: File, toolsUrl: String, timeoutMinutes: Long, log: (String) -> Unit): File {
        val target = File(sdkRoot, TOOLS_ZIP_NAME)
        // Written to a sibling and moved into place, so an interrupted download cannot leave a
        // truncated archive that the next run tries to unpack.
        val part = File(sdkRoot, "$TOOLS_ZIP_NAME.part")
        val timeoutMillis = (timeoutMinutes * 60_000L).toInt()
        log("Downloading Android command-line tools from $toolsUrl")

        val url = runCatching { URI.create(toolsUrl).toURL() }.getOrElse {
            throw GradleException("Invalid download.androidSdkDownloadUrl '$toolsUrl': ${it.message}")
        }
        val connection = runCatching { url.openConnection() }.getOrElse {
            throw GradleException("Cannot open $toolsUrl: ${it.message}")
        }
        connection.connectTimeout = timeoutMillis
        connection.readTimeout = timeoutMillis

        runCatching {
            connection.getInputStream().use { input ->
                FileOutputStream(part).use { output -> input.copyTo(output) }
            }
        }.onFailure {
            throw GradleException("Failed to download the Android command-line tools from $toolsUrl: ${it.message}")
        }

        // Only a complete copy reaches the real name, so an interrupted download leaves a `.part`
        // file instead of an archive the next run would try to unpack.
        Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        log("Downloaded ${target.length() / 1024 / 1024} MB to ${target.name}")
        return target
    }

    private fun unpack(zip: File, sdkRoot: File, log: (String) -> Unit) {
        val staging = File(sdkRoot, TOOLS_STAGING_DIR)
        staging.deleteRecursively()
        staging.mkdirs()
        log("Unpacking ${zip.name}")

        ZipInputStream(zip.inputStream().buffered()).use { stream ->
            var entry = stream.nextEntry
            while (entry != null) {
                val target = File(staging, entry.name)
                // Zip-slip guard: never write outside the staging directory.
                if (!target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                    throw GradleException("Refusing to unpack '${entry.name}' outside '$staging'")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered().use { stream.copyTo(it) }
                }
                stream.closeEntry()
                entry = stream.nextEntry
            }
        }

        // The archive holds a single top-level directory (`cmdline-tools` in current
        // releases) that has to become `<sdk>/cmdline-tools/latest`.
        val inner = staging.listFiles().orEmpty().firstOrNull { it.isDirectory }
            ?: throw GradleException("Unexpected command-line tools archive layout in ${zip.name}")
        File(sdkRoot, "cmdline-tools").mkdirs()
        val destination = File(sdkRoot, TOOLS_DIR)
        if (destination.exists()) destination.deleteRecursively()
        runCatching {
            Files.move(inner.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.onFailure {
            inner.copyRecursively(destination, overwrite = true)
            inner.deleteRecursively()
        }
        staging.deleteRecursively()
    }

    /**
     * Whether [arg] is a flag the plugin's own `sdkmanager` invocation already decides.
     *
     * Accepts both the `--flag` and the `--flag=value` spelling. A package spec has no `-` prefix and
     * is therefore not rejected here, though it would end up installed alongside the configured ones.
     */
    private fun String.isConflictingExtraArg(): Boolean = substringBefore('=') in CONFLICTING_EXTRA_ARGS

    private fun runSdkManager(
        sdkRoot: File,
        args: List<String>,
        timeoutMinutes: Long,
        log: (String) -> Unit,
        answerLicences: Boolean,
    ) {
        val command = mutableListOf(sdkManagerFile(sdkRoot).absolutePath, "--sdk_root=${sdkRoot.absolutePath}")
        command += args
        log("Running ${command.joinToString(" ")}")

        val builder = ProcessBuilder(command)
        builder.directory(sdkRoot)
        builder.redirectErrorStream(true)
        // The sdkmanager launcher is a Java program: point it at the JVM running this build.
        builder.environment()["JAVA_HOME"] = System.getProperty("java.home")

        // The sdkmanager cache lives in $ANDROID_USER_HOME/cache (default ~/.android/cache), and
        // a read-only path there is reported as a *download* failure — read-only HOME happens in
        // containers and sandboxes. Point it inside the SDK instead, which we just proved
        // writable; an explicit value from the environment wins.
        val environment = builder.environment()
        if (environment["ANDROID_USER_HOME"].isNullOrBlank()) {
            val userHome = File(sdkRoot, ANDROID_USER_HOME_DIR).also { it.mkdirs() }
            environment["ANDROID_USER_HOME"] = userHome.absolutePath
            // Older command-line tools read this name instead.
            environment["ANDROID_PREFS_ROOT"] = userHome.absolutePath
        }

        val process = builder.start()

        if (answerLicences) {
            // Answer every prompt with "y" instead of shelling out to `yes`, which does not
            // exist on Windows.
            process.outputStream.bufferedWriter().use { writer ->
                repeat(LICENCE_ANSWERS) { writer.write("y\n") }
            }
        } else {
            process.outputStream.close()
        }

        // Drain stdout concurrently: sdkmanager prints progress and would deadlock on a
        // full pipe if we waited for exit first.
        val output = StringBuilder()
        val drain = Thread {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { output.appendLine(it) }
            }
        }
        drain.isDaemon = true
        drain.start()

        val finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES)
        if (!finished) {
            process.destroyForcibly()
            throw GradleException("sdkmanager timed out after $timeoutMinutes minutes")
        }
        drain.join(5_000)

        val text = output.toString()
        if (process.exitValue() != 0) {
            throw GradleException("sdkmanager failed (exit=${process.exitValue()}):\n$text${repositoryHint(text)}")
        }
        text.lineSequence().filter { it.isNotBlank() }.forEach(log)
    }

    /**
     * Extra hint when sdkmanager reports a failed manifest download.
     *
     * The tool downloads the repository manifest fine and then fails while *caching* it, so the
     * message it prints ("IO exception while downloading manifest") points at the network when the
     * real cause is usually a read-only cache directory. That distinction cost a real debugging
     * session, hence this hint.
     */
    private fun repositoryHint(output: String): String {
        val unreachable = output.contains("Failed to download any source lists") ||
            output.contains("IO exception while downloading manifest")
        if (!unreachable) return ""

        return "\n\nsdkmanager could not read the package repository. This is often a writable-cache " +
            "problem rather than a network one: it caches manifests under ANDROID_USER_HOME/cache " +
            "(default ~/.android/cache) and reports a failed download when that path is read-only. " +
            "The plugin redirects that cache to <sdk>/.android-user unless ANDROID_USER_HOME is " +
            "already set — so check that variable first, then network/proxy access to dl.google.com. " +
            "For a mirror, point download.androidSdkDownloadUrl at it (that is the command-line tools) and " +
            "pass its proxy through download.androidSdkExtraArgs, which sdkmanager needs because it reads " +
            "Google's own package list."
    }

    /**
     * Restores the executable bit that zip extraction drops.
     *
     * A silent failure here surfaced much later as an unrelated `sdkmanager` error, so [required]
     * files fail loudly and optional ones (the `avdmanager` script this plugin never runs) only warn.
     */
    private fun makeExecutable(file: File, required: Boolean, log: (String) -> Unit) {
        if (!file.isFile) return
        if (!file.setExecutable(true, false)) {
            val message = "Could not make '${file.absolutePath}' executable (read-only mount or permissions?)."
            if (required) throw GradleException(message) else log("Warning: $message")
        }
    }
}
