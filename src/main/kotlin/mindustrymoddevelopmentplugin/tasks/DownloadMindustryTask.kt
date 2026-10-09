package mindustrymoddevelopmentplugin.tasks

import org.gradle.api.logging.Logger
import mindustrymoddevelopmentplugin.dsl.MindustryDownloadConfig
import java.io.File
import mindustrymoddevelopmentplugin.platform.HostPlatform
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.gradle.api.GradleException
import org.gradle.api.Task

/**
 * Configures `downloadMindustry`, which fetches the game jar from the GitHub release assets.
 *
 * The plugin ships its own downloader instead of a third-party download task, because it needs three
 * things a generic downloader does not offer. A `<name>.part` file that is only moved into place once
 * the bytes are complete and verified, so an interrupted download cannot leave a truncated jar that
 * later builds trust. An *existing* jar that is never replaced, because `download.mindustryGamePath`
 * may point at the user's own build. And the resolved URL as the task input, so a game version change
 * re-downloads even when the file name stays the same, while an unchanged URL never touches the
 * network — which is what makes `--offline` work. A jar this build did not download (`<name>.url`
 * stamp absent, e.g. one the user built) is never replaced, only reported when it cannot be a jar.
 */
internal object DownloadMindustryTask {
    /** Below this size the file cannot be a game jar; the real one is tens of megabytes. */
    private const val MIN_PLAUSIBLE_JAR_BYTES = 1L * 1024 * 1024

    /** How long to wait for the connection and for each read; a stalled download must not hang a build. */
    private const val CONNECT_TIMEOUT_MILLIS = 30_000
    private const val READ_TIMEOUT_MILLIS = 60_000

    private const val BUFFER_BYTES = 64 * 1024

    /**
     * Wires the download of [version] from [baseUrl] into [target].
     *
     * @param target final jar location; an existing file is left alone and reported as up to date
     * @param baseUrl release download base, e.g. `https://github.com/Anuken/Mindustry/releases/download`
     * @param version release to fetch, e.g. `"146"`, `"v146"` or `"latest"`
     * @param offline the build's `--offline` flag; a missing jar then fails instead of using the network
     * @param warnOnAndroid whether to warn that this download is not usable on Android; only
     * [DownloadHeadlessServerTask] turns it off, because the server jar is the one route that does run there
     */
    fun configure(
        task: Task,
        target: File,
        baseUrl: String,
        version: String,
        assetName: String = "Mindustry.jar",
        pathPropertyName: String = "download.mindustryGamePath",
        offline: Boolean = false,
        warnOnAndroid: Boolean = true,
    ) {
        // Resolved while configuring: the action may not reach into the environment itself.
        val onAndroid = HostPlatform.detect() == HostPlatform.Android
        val url = releaseUrl(baseUrl, version, assetName)
        val stamp = stampOf(target)

        task.group = "mindustry"
        task.description = "Downloads $assetName for Mindustry $version."
        // The URL is the input, so a version change invalidates the download even when the file name
        // does not contain {version}.
        task.inputs.property("url", url)
        // Up to date only when this build downloaded the jar for *this* URL; a jar the user put there
        // keeps the task running (it does nothing) instead of being replaced silently.
        task.outputs.upToDateWhen { ours(target, stamp, url) }
        task.doLast {
            val logger = task.logger
            if (onAndroid && warnOnAndroid) {
                logger.warn(androidUnavailableWarning(assetName, version))
            }
            if (target.isFile && !stamp.isFile) {
                // Not ours: `download.mindustryGamePath` may point at a jar the user built. Never replace
                // it, but say so when it cannot be a game jar, because the failure would be confusing.
                if (looksLikeJar(target)) {
                    logger.lifecycle("Using the existing '${target.name}' (${target.length() / (1024 * 1024)} MB)")
                } else {
                    logger.warn(
                        "'$target' is ${target.length()} bytes and does not look like a jar. " +
                        "Delete it, or point $pathPropertyName elsewhere, to download the game."
                    )
                }
                return@doLast
            }
            if (offline && !target.isFile) {
                // Gradle's own offline flag only covers dependency resolution, so tasks doing their own
                // network access have to honor it — and say so, rather than fetching behind the user's back.
                throw GradleException(
                    "Cannot download $url in offline mode and '$target' does not exist. " +
                    "Run without --offline, or point download.mindustryGamePath at an existing jar."
                )
            }
            val parent = target.parentFile
            if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                throw GradleException("Cannot create the directory for '$target'.")
            }
            fetch(url, target, logger::lifecycle)
        }
    }

    /**
     * Why this download is not usable on Android, and which of this plugin's routes is.
     *
     * The desktop client this task exists for cannot run there: Arc ships no SDL backend for Linux on
     * aarch64, so the jar has no native library to open a window with. The download itself would succeed,
     * which is exactly why saying nothing would be worse — the failure would only appear later, when the
     * game is launched.
     */
    internal fun androidUnavailableWarning(assetName: String, version: String): String =
        "downloadMindustry is not usable on Android: '$assetName' for Mindustry $version is the desktop " +
        "client, and Arc ships no SDL backend for Linux on aarch64, so there is no native library to open " +
        "a window with. Downloading it would work — launching it would not. For a run that does work on " +
        "Android, use downloadHeadlessServer with run.useHeadlessServer = true, or jarAndroid plus " +
        "runMindustry's staging to reach the game installed on the phone."

    /**
     * True when [target] exists and was downloaded for exactly [url].
     *
     * The stamp is what tells a jar this build owns from one the user put there: without it a version
     * change would silently keep using the old game, and with it a user's own jar would be overwritten.
     */
    private fun ours(target: File, stamp: File, url: String): Boolean =
        target.isFile && stamp.isFile && stamp.readText().trim() == url

    /**
     * The release asset URL for [version] below [baseUrl].
     *
     * A leading `v` is accepted (`"v146"` and `"146"` are the same release) and `latest` resolves to the
     * release carrying that tag. `be` has no release asset at all — it is a compile-time API channel —
     * so it fails here with a message that says where it does belong.
     */
    internal fun releaseUrl(baseUrl: String, version: String, assetName: String = "Mindustry.jar"): String {
        val trimmed = version.trim()
        if (trimmed.isEmpty()) {
            throw GradleException(
                "download.mindustryDownloadVersion is empty; set it to a release such as \"146\"."
            )
        }
        val base = baseUrl.trimEnd('/')
        val tag = trimmed.removePrefix("v")
        if (tag == "be") {
            throw GradleException(
                "Mindustry version \"be\" has no release asset to download: it is a compile-time API " +
                "channel for mindustryModRoot { mindustryApiVersion }. Use a release tag such as " +
                "\"146\" or \"latest\" for download.mindustryDownloadVersion."
            )
        }
        return if (tag == "latest") "$base/latest/download/$assetName"
        else "$base/v$tag/$assetName"
    }

    /**
     * Streams [url] into [target] through a `.part` sibling, checks the zip signature, then writes the stamp.
     *
     * Shared with the other downloaders in this plugin, so the expected kind of file and the property that
     * pointed here are parameters: an APK failure must not tell the user to check the game jar's URL. A
     * `file:` URL is read from disk instead of fetched, which is what the tests use.
     */
    internal fun fetch(
        url: String,
        target: File,
        log: (String) -> Unit,
        expected: String = "Mindustry jar",
        sourceHint: String = "download.mindustryDownloadUrl and download.mindustryDownloadVersion",
    ) {
        val stamp = stampOf(target)
        val part = partOf(target)
        part.delete()
        log("Downloading $url")

        val bytes = try {
            open(url).use { input -> part.outputStream().use { input.copyTo(it, BUFFER_BYTES) } }
        } catch (e: IOException) {
            part.delete()
            throw GradleException("Failed to download $url: ${e.message}", e)
        }

        if (!looksLikeJar(part)) {
            val size = if (part.isFile) part.length() else 0L
            part.delete()
            throw GradleException(
                "Downloaded $url, but the result is not a $expected ($size bytes). " +
                "Check $sourceHint" +
                (if (size in 1 until MIN_PLAUSIBLE_JAR_BYTES) " — that response looks like an error page." else ".")
            )
        }

        move(part, target)
        // The stamp is written last: a stamp without a jar means the jar was deleted, not that it exists.
        stamp.writeText(url)
        log("Downloaded ${bytes / (1024 * 1024)} MB to ${target.name}")
    }

    /** Opens [url] for reading, failing with the status code when the server refuses. */
    private fun open(url: String): InputStream {
        val connection = try {
            URI(url).toURL().openConnection()
        } catch (e: Exception) {
            throw GradleException("Invalid download URL '$url': ${e.message}", e)
        }

        if (connection !is HttpURLConnection) return connection.getInputStream()

        connection.instanceFollowRedirects = true
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        // GitHub's asset hosts reject requests without a user agent.
        connection.setRequestProperty("User-Agent", "mindustry-mod-development-plugin")
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            throw GradleException(
                "Downloading $url failed with HTTP $code. " +
                "Check download.mindustryDownloadVersion and download.mindustryDownloadUrl."
            )
        }
        return connection.inputStream
    }

    /** Moves [part] over [target], preferring an atomic rename within the same directory. */
    private fun move(part: File, target: File) {
        try {
            Files.move(
                part.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: IOException) {
            // Some filesystems cannot move atomically; a plain replace is still all-or-nothing here
            // because both paths live in the same directory.
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** The temporary sibling of [target]; same directory, so the final move stays on one filesystem. */
    internal fun partOf(target: File): File = File(target.parentFile, target.name + ".part")

    /** Records which URL produced [target]; deleting it makes an existing jar the user's own again. */
    internal fun stampOf(target: File): File = File(target.parentFile, target.name + ".url")

    /** True when [file] starts with the zip signature and is not obviously truncated. */
    internal fun looksLikeJar(file: File): Boolean {
        if (!file.isFile || file.length() < MIN_PLAUSIBLE_JAR_BYTES) return false
        return file.inputStream().use { it.read() == 0x50 && it.read() == 0x4B }
    }

    /**
     * Substitutes `{version}` into the download file name template and validates the result.
     *
     * A space or one of `\ / : * ? " < > |` throws; CJK characters only warn. The `.jar` suffix is
     * not part of the name — the caller appends it.
     */
    internal fun resolveDownloadFileName(download: MindustryDownloadConfig, logger: Logger): String {
        val name = download.mindustryDownloadFileName.get()
            .replace("{version}", download.mindustryDownloadVersion.get())

        val invalid = name.toCharArray().filter { it in INVALID_FILE_NAME_CHARS }
        if (invalid.isNotEmpty()) {
            throw GradleException(
                "Invalid character(s) in download.mindustryDownloadFileName: " +
                invalid.toSet().joinToString("") { if (it == ' ') "' '" else "'$it'" } +
                "\nFile name: \"$name\"\n" +
                "Avoid spaces and the following characters: \\ / : * ? \" < > |"
            )
        }

        if (CJK_CHARS.containsMatchIn(name)) {
            logger.warn("Download file name \"$name\" contains CJK characters. This may cause issues on some operating systems.")
        }
        return name
    }
        private val INVALID_FILE_NAME_CHARS = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|', ' ')
        private val CJK_CHARS =
            Regex("[\u4e00-\u9fff\u3400-\u4dbf\u2e80-\u2eff\u2f00-\u2fdf\u3000-\u303f]")
}
