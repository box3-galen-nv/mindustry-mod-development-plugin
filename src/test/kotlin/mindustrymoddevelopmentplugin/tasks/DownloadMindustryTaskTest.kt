package mindustrymoddevelopmentplugin.tasks

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Path
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Verifies the plugin's own downloader: the version-to-URL mapping, the `.part` handling, the jar check
 * and the HTTP error path. `file:` URLs cover the copy path, a local server covers the HTTP path.
 */
class DownloadMindustryTaskTest {
    @TempDir
    lateinit var root: Path

    private val rootDir: File get() = root.toFile()

    /** A body that passes [DownloadMindustryTask.looksLikeJar]: zip signature and over 1 MB. */
    private fun jarBytes(size: Int = 1_100_000): ByteArray = ByteArray(size).also {
        it[0] = 0x50
        it[1] = 0x4B
    }

    /** Builds the configured task; [baseUrl] gets `/v<version>/Mindustry.jar` appended. */
    private fun taskFor(target: File, baseUrl: String, version: String = "146") =
        ProjectBuilder.builder().build().let { project ->
            val task = project.tasks.register("downloadMindustry").get()
            DownloadMindustryTask.configure(task, target, baseUrl, version)
            task
        }

    /** Runs the task action the way Gradle would. */
    private fun run(target: File, baseUrl: String, version: String = "146") {
        val task = taskFor(target, baseUrl, version)
        task.actions.forEach { it.execute(task) }
    }

    /** Serves [body] with [status] on every path and returns the server plus its base URL. */
    private fun serve(status: Int, body: ByteArray): Pair<HttpServer, String> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        return server to "http://127.0.0.1:${server.address.port}"
    }

    // ---- releaseUrl ----

    @Test
    fun `release url accepts a plain version a v prefix and latest`() {
        val base = "https://example.com/releases/download/"
        assertTrue(DownloadMindustryTask.releaseUrl(base, "146").endsWith("/v146/Mindustry.jar"))
        assertTrue(DownloadMindustryTask.releaseUrl(base, "v146").endsWith("/v146/Mindustry.jar"))
        assertTrue(
            DownloadMindustryTask.releaseUrl(base, "latest").endsWith("/latest/download/Mindustry.jar"),
            "latest must not become /vlatest/",
        )
    }

    @Test
    fun `be has no release asset and says where it belongs`() {
        val error = assertThrows(GradleException::class.java) {
            DownloadMindustryTask.releaseUrl("https://example.com", "be")
        }
        assertTrue(error.message!!.contains("mindustryApiVersion"), error.message!!)
    }

    @Test
    fun `an empty version is rejected`() {
        val error = assertThrows(GradleException::class.java) {
            DownloadMindustryTask.releaseUrl("https://example.com", "   ")
        }
        assertTrue(error.message!!.contains("mindustryDownloadVersion"), error.message!!)
    }

    // ---- download ----

    @Test
    fun `a file url is copied and verified`() {
        val source = File(rootDir, "v146/Mindustry.jar").apply {
            parentFile.mkdirs()
            writeBytes(jarBytes())
        }
        val target = File(rootDir, "game.jar")

        run(target, source.parentFile.parentFile.toURI().toString())

        assertTrue(target.length() == source.length(), "the whole body must be copied")
        assertTrue(!DownloadMindustryTask.partOf(target).exists(), "the .part file must be gone")
    }

    @Test
    fun `a downloaded jar is stamped with the url that produced it`() {
        val source = File(rootDir, "v146/Mindustry.jar").apply {
            parentFile.mkdirs()
            writeBytes(jarBytes())
        }
        val target = File(rootDir, "stamped.jar")

        run(target, source.parentFile.parentFile.toURI().toString())

        val stamp = DownloadMindustryTask.stampOf(target)
        assertTrue(stamp.isFile, "the stamp is what marks this jar as downloaded by the build")
        assertTrue(stamp.readText().trim().endsWith("/v146/Mindustry.jar"), stamp.readText())
    }

    @Test
    fun `a jar without a stamp is never replaced`() {
        // `mindustryGamePath` may point at a jar the user built. The action must leave it alone even
        // though the configured URL would fail — so a download attempt here fails the test loudly.
        val target = File(rootDir, "mine.jar").apply { writeBytes("user's own build".toByteArray()) }

        run(target, "file:///nonexistent/releases", "146")

        assertTrue(target.readText() == "user's own build", "the user's file must survive untouched")
        assertTrue(!DownloadMindustryTask.partOf(target).exists())
        assertTrue(!DownloadMindustryTask.stampOf(target).exists(), "we must not claim the user's jar")
    }

    @Test
    fun `offline with a missing jar fails instead of using the network`() {
        val target = File(rootDir, "offline.jar")
        val task = ProjectBuilder.builder().build().let { project ->
            val t = project.tasks.register("downloadMindustry").get()
            DownloadMindustryTask.configure(t, target, "file:///nonexistent/releases", "146", offline = true)
            t
        }

        val error = assertThrows(GradleException::class.java) { task.actions.forEach { it.execute(task) } }

        assertTrue(error.message!!.contains("offline mode"), error.message!!)
        assertTrue(error.message!!.contains("mindustryGamePath"), error.message!!)
        assertTrue(!target.exists() && !DownloadMindustryTask.partOf(target).exists())
    }

    @Test
    fun `an http url is streamed into the target`() {
        val (server, base) = serve(200, jarBytes())
        try {
            val target = File(rootDir, "http.jar")
            run(target, base)
            assertTrue(target.isFile && target.length() > 1_000_000, "got ${target.length()} bytes")
            assertTrue(!DownloadMindustryTask.partOf(target).exists())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `an error page is rejected and leaves nothing behind`() {
        val (server, base) = serve(200, "<html>not a jar</html>".toByteArray())
        try {
            val target = File(rootDir, "html.jar")
            val error = assertThrows(GradleException::class.java) { run(target, base) }

            assertTrue(error.message!!.contains("not a Mindustry jar"), error.message!!)
            assertTrue(error.message!!.contains("error page"), "the size hint should fire: ${error.message}")
            assertTrue(!target.exists(), "a rejected body must not be moved into place")
            assertTrue(!DownloadMindustryTask.partOf(target).exists(), "the .part file must be deleted")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `an http error names the url and the dsl options`() {
        val (server, base) = serve(404, "nope".toByteArray())
        try {
            val target = File(rootDir, "missing.jar")
            val error = assertThrows(GradleException::class.java) { run(target, base) }

            assertTrue(error.message!!.contains("HTTP 404"), error.message!!)
            assertTrue(error.message!!.contains("mindustryDownloadVersion"), error.message!!)
            assertTrue(!target.exists() && !DownloadMindustryTask.partOf(target).exists())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a custom asset name is used for the headless server`() {
        val base = "https://example.invalid/mindustry/releases/download"
        // The headless server is a different release asset, and the URL has to follow that.
        assertTrue(
            DownloadMindustryTask.releaseUrl(base, "147", "server-release.jar") ==
                "$base/v147/server-release.jar"
        )
        assertTrue(
            DownloadMindustryTask.releaseUrl(base, "latest", "server-release.jar") ==
                "$base/latest/download/server-release.jar"
        )
        // The default stays the desktop jar.
        assertTrue(DownloadMindustryTask.releaseUrl(base, "147") == "$base/v147/Mindustry.jar")
    }

    @Test
    fun `the Android warning says the task is unusable there, and why`() {
        val text = DownloadMindustryTask.androidUnavailableWarning("Mindustry.jar", "159")
        assertTrue(text.contains("not usable on Android"), text)
        assertTrue(text.contains("aarch64"), "the reason is a missing native library: $text")
        assertTrue(text.contains("Downloading it would work"), "the download itself is not the problem: $text")
        assertTrue(text.contains("useHeadlessServer"), "it must point at the route that works: $text")
    }
}
