package mindustrymoddevelopmentplugin.wiring

import org.gradle.api.Project

/**
 * Reads build properties the way the plugin needs them.
 *
 * Gradle's own accessors hand back the raw string, and a switch given on the command line has to win over
 * the DSL in both directions — so `-Px=false` has to mean off rather than merely "set".
 */
internal object GradleProperties {
    /**
     * Reads a boolean project property (`-Pname=value`, `gradle.properties`, `-D`).
     *
     * [Project.hasProperty] is not enough: it only reports that the property exists, so
     * `-PmindustryDebug=false` still opened the debug socket, and `-PmindustryDebugSuspend=false`
     * made the game wait for a debugger that never attaches — the game looks hung. A property given
     * without a value counts as enabled, which is what a command-line flag means.
     */
    fun boolean(project: Project, name: String): Boolean {
        val text = project.findProperty(name)?.toString()?.trim() ?: return false
        return text.isEmpty() || text.toBoolean()
    }

    /**
     * Like [booleanProperty], but null when the property was not given at all.
     *
     * Used where a command-line switch must be able to override a DSL default in both directions:
     * `-PmindustryDebug=false` has to win over `debug { enableDebug = true }`, which a plain
     * `dsl || property` cannot express.
     */
    fun booleanOrNull(project: Project, name: String): Boolean? {
        val text = project.findProperty(name)?.toString()?.trim() ?: return null
        return text.isEmpty() || text.toBoolean()
    }
}
