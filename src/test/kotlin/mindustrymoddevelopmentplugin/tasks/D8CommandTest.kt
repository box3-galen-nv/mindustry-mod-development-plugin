package mindustrymoddevelopmentplugin.tasks

import java.io.File
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Verifies how [buildD8Command] is assembled: the d8 binary, the --classpath dependency pairs,
 * the default --min-api, where custom extraArgs are inserted, --output and the input jar.
 */
class D8CommandTest {

    @field:TempDir
    lateinit var tempDir: Path

    private val root: File get() = tempDir.toFile()

    private fun file(name: String): File = root.resolve(name).also { it.parentFile.mkdirs() }

    @Test
    fun `default command has binary classpath min-api output and input`() {
        val dep = file("dep.jar")
        val output = file("out.jar")
        val input = file("in.jar")
        val args = AndroidSdk.buildD8Command(
            d8Command = listOf("/sdk/build-tools/d8"),
            deps = listOf(dep),
            output = output,
            input = input,
        )
        assertTrue(args.first() == "/sdk/build-tools/d8")
        assertTrue(args.contains("--classpath") && args.contains(dep.absolutePath))
        val minApiIndex = args.indexOf("--min-api")
        assertTrue(minApiIndex >= 0 && args[minApiIndex + 1] == "14")
        assertTrue(args[args.indexOf("--output") + 1] == output.absolutePath)
        assertTrue(args.last() == input.absolutePath)
    }
    @Test
    fun `extra args are inserted after defaults and before output`() {
        val output = file("out.jar")
        val input = file("in.jar")
        val args = AndroidSdk.buildD8Command(
            d8Command = listOf("d8"),
            deps = emptyList(),
            extraArgs = listOf("--no-desugaring", "--release"),
            output = output,
            input = input,
        )
        val minApiIndex = args.indexOf("--min-api")
        val outputIndex = args.indexOf("--output")
        assertTrue(args.slice(minApiIndex + 2 until outputIndex) == listOf("--no-desugaring", "--release"), "extraArgs should sit between --min-api and --output: $args")
    }

    @Test
    fun `extra args can override default min-api`() {
        val output = file("out.jar")
        val input = file("in.jar")
        val args = AndroidSdk.buildD8Command(
            d8Command = listOf("d8"),
            deps = emptyList(),
            extraArgs = listOf("--min-api", "21"),
            output = output,
            input = input,
        )
        // With a repeated argument the last occurrence wins, so the custom --min-api 21 must
        // come after the default one.
        assertTrue(args.lastIndexOf("--min-api") > args.indexOf("--min-api"))
        assertTrue(args[args.lastIndexOf("--min-api") + 1] == "21")
    }
    @Test
    fun `custom minApi parameter is honoured`() {
        val output = file("out.jar")
        val input = file("in.jar")
        val args = AndroidSdk.buildD8Command(
            d8Command = listOf("d8"),
            deps = emptyList(),
            minApi = 26,
            output = output,
            input = input,
        )
        assertTrue(args[args.indexOf("--min-api") + 1] == "26")
    }

    @Test
    fun `d8FailureHint explains an unsupported class file version`() {
        // Observed for real: d8 in build-tools 34.0.0 rejects Java 25 bytecode (major version 69)
        val hint = AndroidSdk.d8FailureHint(
            "Error in input.jar:Hello.class:\n" +
            "java.lang.IllegalArgumentException: Unsupported class file major version 69"
        )
        assertTrue(hint.contains("jvmTarget"), "the hint should name the Kotlin setting: $hint")
        assertTrue(hint.contains("sourceCompatibility"), "the hint should name the Java settings: $hint")
        assertTrue(hint.contains("androidSdkDownloadPackages"), "the hint should mention the build-tools option: $hint")
    }

    @Test
    fun `d8FailureHint stays empty for unrelated failures`() {
        assertTrue(AndroidSdk.d8FailureHint("some other d8 error").isEmpty())
    }
}
