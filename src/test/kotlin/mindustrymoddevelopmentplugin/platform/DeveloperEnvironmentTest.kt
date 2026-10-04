package mindustrymoddevelopmentplugin.platform

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the environment report says, given an environment that is handed to it.
 *
 * Everything is injected on purpose: the report is the one place this plugin describes the machine it runs
 * on, so it has to be testable without being on that machine.
 */
class DeveloperEnvironmentTest {

    private fun project(vararg dirs: String): File = createTempDirectory("project").toFile().also { root ->
        dirs.forEach { File(root, it).mkdirs() }
    }

    private fun describe(
        projectDir: File = project(),
        env: Map<String, String> = emptyMap(),
        sysProps: Map<String, String> = emptyMap(),
        host: HostPlatform = HostPlatform.Desktop,
    ): String = DeveloperEnvironment.describe(projectDir, host, host, env, sysProps, "9.8.0").joinToString("\n")

    @Test
    fun `an editor's project directory is evidence, not a guess`() {
        val idea = describe(project(".idea"))
        assertTrue(idea.contains(".idea in the project"), idea)

        val code = describe(project(".vscode"))
        assertTrue(code.contains(".vscode in the project"), code)

        val runConfigs = describe(project(".run"))
        assertTrue(runConfigs.contains(".run configurations"), runConfigs)
    }

    @Test
    fun `the editor variables are reported as hints`() {
        val jetbrains = describe(sysProps = mapOf("idea.active" to "true"))
        assertTrue(jetbrains.contains("JetBrains IDE"), jetbrains)

        val terminal = describe(env = mapOf("TERMINAL_EMULATOR" to "JetBrains-JediTerm"))
        assertTrue(terminal.contains("JetBrains terminal"), terminal)

        val vscode = describe(env = mapOf("TERM_PROGRAM" to "vscode"))
        assertTrue(vscode.contains("VS Code"), vscode)

        val vscodeVars = describe(env = mapOf("VSCODE_PID" to "1234"))
        assertTrue(vscodeVars.contains("VS Code"), vscodeVars)
    }

    @Test
    fun `termux is named from its own variables, and the platform line says so`() {
        val termux = describe(env = mapOf("TERMUX_VERSION" to "0.118"), host = HostPlatform.Android)
        assertTrue(termux.contains("Host platform: android"), termux)
        assertTrue(termux.contains("detected: android"), termux)
        assertTrue(termux.contains("Termux 0.118"), termux)

        val prefix = describe(env = mapOf("PREFIX" to "/data/data/com.termux/files/usr"))
        assertTrue(prefix.contains("Termux (PREFIX="), prefix)
    }

    @Test
    fun `ci runners are named, and a nameless CI is still noticed`() {
        val actions = describe(env = mapOf("GITHUB_ACTIONS" to "true", "GITHUB_WORKFLOW" to "CI"))
        assertTrue(actions.contains("GitHub Actions (CI)"), actions)

        val anonymous = describe(env = mapOf("CI" to "true"))
        assertTrue(anonymous.contains("not a recognised runner"), anonymous)
    }

    @Test
    fun `an environment that says nothing is reported as unknown rather than guessed`() {
        val report = describe()
        assertTrue(report.contains("not recognised"), report)
        assertTrue(report.contains("not Termux"), report)
        assertTrue(report.contains("Continuous integration: no"), report)
        assertTrue(report.contains("Gradle: 9.8.0"), report)
    }
}
