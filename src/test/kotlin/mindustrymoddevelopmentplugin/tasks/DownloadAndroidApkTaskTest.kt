package mindustrymoddevelopmentplugin.tasks

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What makes a file usable as the Mindustry APK the Android run imports.
 *
 * The checks are what a real APK satisfies — `AndroidManifest.xml` plus `classes.dex` — and what an HTML
 * error page saved as `.apk`, or an unrelated archive, does not.
 */
class DownloadAndroidApkTaskTest {

    private fun zip(name: String, entries: Map<String, String>): File {
        val file = createTempDirectory("apk").toFile().resolve(name)
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (entry, body) ->
                zip.putNextEntry(ZipEntry(entry))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    private fun apk(entries: Map<String, String>) = zip("Mindustry.apk", entries)

    @Test
    fun `a real apk layout passes`() {
        assertNull(DownloadAndroidApkTask.apkProblem(apk(mapOf(
            "AndroidManifest.xml" to "<manifest/>",
            "classes.dex" to "dex",
            "assets/version.properties" to "build=160.5",
        ))))
    }

    @Test
    fun `a zip without the manifest or the dex is rejected by name`() {
        val noDex = DownloadAndroidApkTask.apkProblem(apk(mapOf("AndroidManifest.xml" to "<manifest/>")))
        assertTrue(noDex != null && noDex.contains("classes.dex"), noDex ?: "no message")

        val noManifest = DownloadAndroidApkTask.apkProblem(apk(mapOf("classes.dex" to "dex")))
        assertTrue(noManifest != null && noManifest.contains("AndroidManifest.xml"), noManifest ?: "no message")
    }

    @Test
    fun `an error page saved as an apk is rejected`() {
        val notAZip = createTempDirectory("apk").toFile().resolve("Mindustry.apk").apply {
            writeText("<html><body>404 Not Found</body></html>")
        }
        val problem = DownloadAndroidApkTask.apkProblem(notAZip)
        assertTrue(problem != null && problem.contains("not a zip"), problem ?: "no message")
    }

    @Test
    fun `a missing file says so`() {
        val problem = DownloadAndroidApkTask.apkProblem(File("/nonexistent/Mindustry.apk"))
        assertTrue(problem != null && problem.contains("does not exist"), problem ?: "no message")
    }

    @Test
    fun `the guidance says where the apk comes from and why the plugin cannot fetch it`() {
        val text = DownloadAndroidApkTask.guidance(File("/work/mod/build/game/Mindustry.apk"))
        assertTrue(text.contains("anuke.itch.io/mindustry"), text)
        assertTrue(text.contains("run.androidApkUrl"), text)
        assertTrue(text.contains("session-backed"), "the reason the plugin cannot fetch it must be stated: $text")
        assertTrue(text.contains("/work/mod/build/game/Mindustry.apk"), text)
    }
}
