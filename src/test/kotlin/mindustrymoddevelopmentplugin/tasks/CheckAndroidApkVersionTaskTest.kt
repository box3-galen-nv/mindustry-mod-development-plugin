package mindustrymoddevelopmentplugin.tasks

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the APK version check reads and what it warns about.
 *
 * The fixture is the *real* content of the official v160.5 APK's `assets/version.properties`, escaped colon
 * and all, so the parser is exercised against what actually ships rather than a simplified version of it.
 */
class CheckAndroidApkVersionTaskTest {

    private val realProperties = """
        #Sun Sep 20 09:47:04 EDT 2026
        number=8
        build=160.5
        modifier=release
        androidBuildCode=30596
        buildDate=September 20, 2026 09\:47 AM
        type=official
        commitHash=unknown
    """.trimIndent() + "\n"

    private fun apk(entries: Map<String, String>): File {
        val file = createTempDirectory("apk").toFile().resolve("Mindustry.apk")
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (entry, body) ->
                zip.putNextEntry(ZipEntry(entry))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `the real version properties are read`() {
        val properties = CheckAndroidApkVersionTask.readVersionProperties(
            apk(mapOf(CheckAndroidApkVersionTask.VERSION_ENTRY to realProperties)),
        )
        assertEquals("160.5", properties?.getProperty("build"))
        assertEquals("official", properties?.getProperty("type"))
        assertEquals("30596", properties?.getProperty("androidBuildCode"))
    }

    @Test
    fun `an apk without the entry, and a file that is not one, are both null`() {
        assertNull(CheckAndroidApkVersionTask.readVersionProperties(apk(mapOf("classes.dex" to "dex"))))
        val notAZip = createTempDirectory("apk").toFile().resolve("Mindustry.apk").apply {
            writeText("<html>404</html>")
        }
        assertNull(CheckAndroidApkVersionTask.readVersionProperties(notAZip))
        assertNull(CheckAndroidApkVersionTask.readVersionProperties(File("/nonexistent/Mindustry.apk")))
    }

    @Test
    fun `a matching version says nothing`() {
        assertTrue(CheckAndroidApkVersionTask.problems("160.5", "official", "160.5", "io.anuke.mindustry").isEmpty())
        // A leading v is the same release, which is how the downloader treats it too.
        assertTrue(CheckAndroidApkVersionTask.problems("v146", "official", "146", "io.anuke.mindustry").isEmpty())
    }

    @Test
    fun `a mismatched version names both and points at the knob`() {
        val warnings = CheckAndroidApkVersionTask.problems("999", "official", "147", "io.anuke.mindustry")
        assertEquals(1, warnings.size, warnings.toString())
        assertTrue(warnings[0].contains("999") && warnings[0].contains("147"), warnings[0])
        assertTrue(warnings[0].contains("download.mindustryDownloadVersion"), warnings[0])
    }

    @Test
    fun `a non-official type names the app id to set`() {
        val warnings = CheckAndroidApkVersionTask.problems("160.5", "be", "160.5", "io.anuke.mindustry")
        assertEquals(1, warnings.size, warnings.toString())
        assertTrue(warnings[0].contains("io.anuke.mindustry.be"), warnings[0])
        assertTrue(warnings[0].contains("io.anuke.mindustry"), "the current app id must be named: ${warnings[0]}")
        assertTrue(warnings[0].contains("run.androidAppId"), warnings[0])
    }

    @Test
    fun `missing fields are not treated as mismatches`() {
        assertTrue(CheckAndroidApkVersionTask.problems(null, null, "147", "io.anuke.mindustry").isEmpty())
        assertTrue(CheckAndroidApkVersionTask.problems("", "", "", "").isEmpty())
        // No expected version means nothing to compare against.
        assertTrue(CheckAndroidApkVersionTask.problems("160.5", "official", "", "io.anuke.mindustry").isEmpty())
    }
}
