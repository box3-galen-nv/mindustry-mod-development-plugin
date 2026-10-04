package mindustrymoddevelopmentplugin.platform

import java.io.File

/**
 * What the build can tell about the machine and the editor it is being run from.
 *
 * Two kinds of evidence, deliberately kept apart.
 *
 * The environment variables and system properties are *heuristics*. They are listed one by one in
 * [editorHints] so that a wrong guess can be corrected by reading that list, and so that no claim here rests
 * on an assumption about what a particular IDE sets — only on what the variable is called.
 *
 * The project directories are facts: an `.idea` or `.vscode` directory exists because someone used that
 * editor in this project, whoever started this particular build.
 */
internal object DeveloperEnvironment {
    /**
     * One line per fact, in the order they are worth reading.
     *
     * Everything is a parameter so the report can be tested without this machine's real environment, and so
     * the caller can resolve it while configuring rather than reaching into the project from a task action.
     */
    fun describe(
        projectDir: File,
        hostPlatform: HostPlatform,
        detectedPlatform: HostPlatform,
        env: Map<String, String>,
        sysProps: Map<String, String>,
        gradleVersion: String,
    ): List<String> = listOf(
        "Host platform: ${hostPlatform.name.lowercase()} " +
            "(detected: ${detectedPlatform.name.lowercase()}; ${termux(env)})",
        "Operating system: ${sysProps["os.name"].orEmpty()} ${sysProps["os.version"].orEmpty()}" +
            " (${sysProps["os.arch"].orEmpty()})",
        "Java: ${sysProps["java.version"].orEmpty()} at ${sysProps["java.home"].orEmpty()}",
        "Gradle: $gradleVersion",
        "Editor: ${editor(projectDir, env, sysProps)}",
        "Terminal: ${terminal(env)}",
        "Continuous integration: ${ci(env)}",
    )

    /** The editor's own project directory first, because that is evidence rather than a guess. */
    private fun editor(projectDir: File, env: Map<String, String>, sysProps: Map<String, String>): String {
        val found = mutableListOf<String>()
        if (File(projectDir, ".idea").isDirectory) found += "IntelliJ IDEA or Android Studio (.idea in the project)"
        if (File(projectDir, ".vscode").isDirectory) found += "VS Code (.vscode in the project)"
        if (File(projectDir, ".run").isDirectory) found += "this plugin's generated .run configurations"
        found += editorHints(env, sysProps)
        return if (found.isEmpty()) "not recognised (no editor variables, no .idea/.vscode/.run directory)" else found.joinToString("; ")
    }

    /**
     * The editor hints, one variable group per entry.
     *
     * These are heuristics on purpose: the plugin knows what each variable is *called*, not that every
     * version of every IDE sets it, and saying so here is cheaper than being wrong quietly.
     */
    private fun editorHints(env: Map<String, String>, sysProps: Map<String, String>): List<String> = buildList {
        if (sysProps["idea.active"] == "true" || !sysProps["idea.version"].isNullOrBlank()) {
            add("a JetBrains IDE started this build (idea.active or idea.version)")
        }
        if (!env["IDEA_INITIAL_DIRECTORY"].isNullOrBlank() || env["TERMINAL_EMULATOR"] == "JetBrains-JediTerm") {
            add("a JetBrains terminal (IDEA_INITIAL_DIRECTORY or TERMINAL_EMULATOR)")
        }
        if (env["TERM_PROGRAM"] == "vscode" || env.keys.any { it.startsWith("VSCODE_") }) {
            add("a VS Code terminal (TERM_PROGRAM=vscode or a VSCODE_ variable)")
        }
    }

    /** Termux's own prefix is what makes the Android branch of [HostPlatform] work. */
    private fun termux(env: Map<String, String>): String = when {
        !env["TERMUX_VERSION"].isNullOrBlank() -> "Termux ${env["TERMUX_VERSION"]}"
        env["PREFIX"].orEmpty().contains("com.termux") -> "Termux (PREFIX=${env["PREFIX"]})"
        else -> "not Termux"
    }

    private fun terminal(env: Map<String, String>): String {
        val program = env["TERM_PROGRAM"]
        if (!program.isNullOrBlank()) return "$program ${env["TERM_PROGRAM_VERSION"].orEmpty()}".trim()
        if (!env["WT_SESSION"].isNullOrBlank()) return "Windows Terminal"
        if (!env["KITTY_WINDOW_ID"].isNullOrBlank() || env["TERM"].orEmpty().contains("kitty")) return "kitty"
        if (!env["ALACRITTY_SOCKET"].isNullOrBlank()) return "Alacritty"
        if (!env["GHOSTTY_RESOURCES_DIR"].isNullOrBlank()) return "Ghostty"
        return env["TERM"].orEmpty().ifBlank { "unknown" }
    }

    /** Several defaults in this plugin assume a human is watching, so CI is worth naming. */
    private fun ci(env: Map<String, String>): String {
        val runner = listOf(
            "GITHUB_ACTIONS" to "GitHub Actions",
            "GITLAB_CI" to "GitLab CI",
            "BUILDKITE" to "Buildkite",
            "TEAMCITY_VERSION" to "TeamCity",
            "JENKINS_URL" to "Jenkins",
            "CIRCLECI" to "CircleCI",
            "TRAVIS" to "Travis",
        ).firstOrNull { (key, _) -> !env[key].isNullOrBlank() }
        return when {
            runner != null -> runner.second + env["GITHUB_WORKFLOW"]?.let { " ($it)" }.orEmpty()
            env["CI"] == "true" -> "yes (CI=true, but not a recognised runner)"
            else -> "no"
        }
    }
}
