package mindustrymoddevelopmentplugin.meta

import java.io.File

/**
 * One mod project's built artifact: what to copy, what to call it in messages, and how to build it.
 *
 * Plain values on purpose. The tasks that move these jars must not hold the projects (or the packaging
 * tasks) they came from, or the configuration cache refuses to serialize them.
 */
internal class ModArtifact(val name: String, val jar: File, val packagingCommand: String)
