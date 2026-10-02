package mindustrymoddevelopmentplugin

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Integration tests for up-to-date behaviour and the build counter.
 */
internal class UpToDateIntegrationTest : TestKitFixture() {


    // ---- jarAndroid input declaration: a changed jar must not leave a stale dex ----

    @Test
    fun `jarAndroid is up to date when nothing changed and re-runs after a change`() {
        fakeAndroidSdk()
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", androidBuildScript())
        write("mod.hjson", "name: '''test-mod'''\nversion: '''1.0'''\njava: true\n")

        val first = runner().withArguments("jarAndroid").build()
        assertTrue(first.task(":jarAndroid")?.outcome == TaskOutcome.SUCCESS, "首次应真正执行")

        val second = runner().withArguments("jarAndroid").build()
        assertTrue(
            second.task(":jarAndroid")?.outcome == TaskOutcome.UP_TO_DATE,
            "无改动时应为 UP-TO-DATE，实际 ${second.task(":jarAndroid")?.outcome}",
        )

        // Change the metadata packed into the jar → jar is rebuilt → jarAndroid must re-run (otherwise the dex is stale)
        write("mod.hjson", "name: '''test-mod'''\nversion: '''2.0'''\njava: true\n")
        val third = runner().withArguments("jarAndroid").build()
        assertTrue(
            third.task(":jarAndroid")?.outcome == TaskOutcome.SUCCESS,
            "输入 jar 变化后必须重跑，实际 ${third.task(":jarAndroid")?.outcome}",
        )
    }


    // ---- deploy keeps intermediate artifacts → jar / jarAndroid stay incremental ----

    @Test
    fun `deploy keeps intermediate jars so jar and jarAndroid stay up to date`() {
        fakeAndroidSdk()
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", androidBuildScript())
        write("mod.hjson", "name: '''test-mod'''\nversion: '''1.0'''\njava: true\n")

        val first = runner().withArguments("deploy").build()
        assertTrue(first.task(":deploy")?.outcome == TaskOutcome.SUCCESS)

        val libs = rootDir.resolve("build/libs")
        val names = libs.listFiles()?.map { it.name }.orEmpty()
        assertTrue(names.any { it.endsWith("-Jar.jar") }, "桌面 jar 应保留在 libs 中，实际 $names")
        assertTrue(names.any { it.endsWith("-Android.jar") }, "Android jar 应保留在 libs 中，实际 $names")

        val second = runner().withArguments("deploy").build()
        assertTrue(
            second.task(":jar")?.outcome == TaskOutcome.UP_TO_DATE,
            "保留中间产物后 jar 应可增量，实际 ${second.task(":jar")?.outcome}",
        )
        assertTrue(
            second.task(":jarAndroid")?.outcome == TaskOutcome.UP_TO_DATE,
            "保留中间产物后 jarAndroid 应可增量，实际 ${second.task(":jarAndroid")?.outcome}",
        )
    }


    // ---- buildModHJson up-to-date checks ----

    @Test
    fun `buildModHJson is skipped when generateModMeta is false`() {
        write("settings.gradle.kts", """rootProject.name = "test"""")
        write("build.gradle.kts", """
            ${pluginSnippet}
            ${kotlinSnippet()}
            mindustryModRoot { mindustryApiVersion = "159" }
            mindustryMod { modMeta { name = "test-mod"; java = true } }
        """)
        val result = runner().withArguments("buildModHJson").build()
        assertTrue(
            result.task(":buildModHJson")?.outcome == TaskOutcome.SKIPPED,
            "未开启生成时应 SKIPPED 而不是空转执行，实际 ${result.task(":buildModHJson")?.outcome}",
        )
    }


    @Test
    fun `buildModHJson is up to date until the DSL changes`() {
        write("settings.gradle.kts", """rootProject.name = "test"""")
        val script = { version: String ->
            """
            ${pluginSnippet}
            ${kotlinSnippet()}
            mindustryModRoot {
                mindustryApiVersion = "159"
                build { useHJson = true }
            }
            mindustryMod {
                generateModMeta = true
                modMeta { name = "test-mod"; version = "$version"; java = true }
            }
            """.trimIndent()
        }
        write("build.gradle.kts", script("1.0"))

        val first = runner().withArguments("buildModHJson").build()
        assertTrue(first.task(":buildModHJson")?.outcome == TaskOutcome.SUCCESS)

        val second = runner().withArguments("buildModHJson").build()
        assertTrue(
            second.task(":buildModHJson")?.outcome == TaskOutcome.UP_TO_DATE,
            "DSL 未变时应 UP-TO-DATE，实际 ${second.task(":buildModHJson")?.outcome}",
        )

        write("build.gradle.kts", script("2.0"))
        val third = runner().withArguments("buildModHJson").build()
        assertTrue(
            third.task(":buildModHJson")?.outcome == TaskOutcome.SUCCESS,
            "DSL 变化后必须重新生成，实际 ${third.task(":buildModHJson")?.outcome}",
        )
        assertTrue(rootDir.resolve("build/mod.hjson").readText().contains("version: '''2.0'''"))
    }
}
