package mindustrymoddevelopmentplugin.tasks

import java.io.File
import org.gradle.api.Project

/**
 * Model of the IntelliJ IDEA run configurations in `<rootProject>/.run/`, so that a mod author can
 * start the game and stop on breakpoints without hand-building configurations.
 *
 * Two configurations are generated, mirroring the two supported flows:
 * - a **Gradle** configuration that runs `runMindustry` with `-PmindustryDebug=true`, which
 *   makes the game JVM open the JDWP port;
 * - a **Remote JVM Debug** configuration that attaches to that port, which is what resolves
 *   breakpoints in the mod sources.
 *
 * Once the game JVM is listening, either attach the generated Remote configuration (works
 * even without a `suspend` window) or run the game with [DEBUG_SUSPEND_PROPERTY] so the
 * debugger can also catch code that runs during startup.
 *
 * [write] is called by the `generateIdeaRunConfigs` task ([GenerateIdeaRunConfigsTask]), not during
 * configuration: the files are that task's declared outputs, so they are only rewritten when
 * [TEMPLATE_REVISION] or the debug port changes. Run `./gradlew generateIdeaRunConfigs` once (and
 * after changing the port) to (re)create them.
 */
internal object IdeaRunConfigs {

    /** Project property that turns the JDWP port on; used by the generated Gradle configuration. */
    const val DEBUG_PROPERTY = "mindustryDebug"

    /** Project property that makes the game JVM wait for a debugger before running. */
    const val DEBUG_SUSPEND_PROPERTY = "mindustryDebugSuspend"

    private const val DIR = ".run"

    /** Writes (or refreshes) both configurations and returns the files created. */
    fun write(
        project: Project,
        taskName: String,
        debugPort: Int,
        packagingTask: String = "deploy",
        warn: (String) -> Unit = {},
    ): List<File> {
        val (gradleConfig, remoteConfig) = files(project)
        gradleConfig.parentFile.mkdirs()
        // IDEA writes its own edits back into these files, so overwriting one silently would throw away
        // whatever the user configured there (a JDK, an environment variable). Say so instead.
        listOf(
            gradleConfig to gradleConfiguration(taskName, debugPort, packagingTask),
            remoteConfig to remoteConfiguration(debugPort),
        ).forEach { (file, content) ->
            if (file.isFile && file.readText() != content) {
                warn("Overwriting '${file.name}', which differs from what this task generates.")
            }
            file.writeText(content)
        }
        return listOf(gradleConfig, remoteConfig)
    }

    /**
     * The two files [write] produces, in `<root>/.run/`, even if they do not exist yet.
     *
     * `generateIdeaRunConfigs` declares both as its outputs, which is what makes it UP-TO-DATE while
     * nothing changed.
     */
    fun files(project: Project): List<File> =
        fileNames().map { File(File(project.rootProject.projectDir, DIR), it) }

    /**
     * Revision of the XML below, declared as a task input.
     *
     * Gradle's up-to-date check covers the task's *action* class, not the whole plugin jar, so a
     * changed template would otherwise keep the old files. Bump this whenever the XML changes.
     */
    const val TEMPLATE_REVISION = 2

    private fun fileNames() = listOf(
        "Mindustry-runMindustry-debug.run.xml",
        "Mindustry-attach-debugger.run.xml",
    )

    private fun gradleConfiguration(taskName: String, debugPort: Int, packagingTask: String): String = $$"""
        <component name="ProjectRunConfigurationManager">
          <configuration default="false" name="Mindustry: $$taskName (debug, port $$debugPort)" type="GradleRunConfiguration" factoryName="Gradle">
            <ExternalSystemSettings>
              <option name="externalProjectPath" value="$PROJECT_DIR$" />
              <option name="externalSystemIdString" value="GRADLE" />
              <option name="scriptParameters" value="-P$$DEBUG_PROPERTY=true" />
              <option name="taskDescriptions">
                <list />
              </option>
              <option name="taskNames">
                <list>
                  <option value="$$packagingTask" />
                  <option value="$$taskName" />
                </list>
              </option>
              <option name="vmOptions" />
            </ExternalSystemSettings>
            <ExternalSystemDebugServerProcess>true</ExternalSystemDebugServerProcess>
            <ExternalSystemReattachDebugProcess>true</ExternalSystemReattachDebugProcess>
            <DebugAllEnabled>false</DebugAllEnabled>
            <method v="2" />
          </configuration>
        </component>
    """.trimIndent() + "\n"

    private fun remoteConfiguration(debugPort: Int): String = """
        <component name="ProjectRunConfigurationManager">
          <configuration default="false" name="Mindustry: attach debugger (port $debugPort)" type="Remote" factoryName="Remote">
            <option name="USE_SOCKET_TRANSPORT" value="true" />
            <option name="HOST" value="localhost" />
            <option name="PORT" value="$debugPort" />
            <option name="AUTO_RESTART" value="false" />
            <method v="2" />
          </configuration>
        </component>
    """.trimIndent() + "\n"
}
