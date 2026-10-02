package mindustrymoddevelopmentplugin.meta

import mindustrymoddevelopmentplugin.dsl.MindustryModExtension
import mindustrymoddevelopmentplugin.dsl.MindustryModRootExtension
import mindustrymoddevelopmentplugin.dsl.MindustryBuildConfig
import mindustrymoddevelopmentplugin.meta.ModFileReader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import org.gradle.api.Project

/**
 * Single implementation of the artifact naming rules.
 *
 * Two callers need the same answer from the same inputs: the plugin, when it names the `jar` /
 * `jarAndroid` / `deploy` outputs, and the public [MindustryModExtension.modVersion], which users
 * read to learn the artifact name. They used to be separate implementations, and [MindustryModExtension.modVersion]
 * silently supported fewer placeholders (`{author}` and `{time}` were missing) and skipped the
 * metadata-file fallback, so the reported name could differ from the produced one.
 */
internal object ArtifactNaming {

    /** Every name a mod produces, all derived from one resolved base. */
    data class Names(val base: String, val jar: String, val android: String, val deploy: String)

    /** What the [MindustryBuildConfig.format] placeholders resolve to for one project. */
    data class Values(val name: String, val author: String, val version: String)

    /**
     * Resolves the `{name}` / `{author}` / `{version}` values, falling back to the project's
     * metadata files for fields the DSL left blank.
     *
     * Shared by [names] and by the callers that need the values themselves (the `clearMods` pattern).
     */
    fun values(project: Project, ext: MindustryModExtension): Values = Values(
        name = resolveMetaValue(ext.modMeta.name, project.projectDir, "name").ifBlank { "mod" },
        author = resolveMetaValue(ext.modMeta.author, project.projectDir, "author"),
        version = resolveMetaValue(ext.modMeta.version, project.projectDir, "version").ifBlank { "0.0.1" },
    )

    /**
     * Resolves [MindustryModExtension.modMeta] — through [values], so the metadata-file fallback
     * applies here too — and expands [MindustryBuildConfig.format] into the artifact names.
     */
    fun names(project: Project, ext: MindustryModExtension, rootExt: MindustryModRootExtension?): Names {
        val build = rootExt?.build
        val format = build?.format?.get() ?: MindustryBuildConfig.DEFAULT_FORMAT
        val timeFormat = build?.timeFormat?.get() ?: MindustryBuildConfig.DEFAULT_TIME_FORMAT
        val (name, author, version) = values(project, ext)

        val base = format
            .replace("{name}", name)
            .replace("{author}", author)
            .replace("{version}", version)
            .replace("{build_count}", ext.readBuildCounter().toString())
            .replace("{time}", SimpleDateFormat(timeFormat).format(Date()))

        return Names(
            base = base,
            jar = base + (build?.jarSuffix?.get() ?: MindustryBuildConfig.DEFAULT_JAR_SUFFIX),
            android = base + (build?.androidSuffix?.get() ?: MindustryBuildConfig.DEFAULT_ANDROID_SUFFIX),
            deploy = base + (build?.deploySuffix?.get() ?: MindustryBuildConfig.DEFAULT_DEPLOY_SUFFIX),
        )
    }

    /**
     * Returns [dslValue] when it is set, otherwise the first of [keys] found in the project's
     * metadata files (engine order), otherwise an empty string.
     *
     * Reading the files is what makes the DSL optional for a project that already ships `mod.json`.
     */
    fun resolveMetaValue(dslValue: String, projectDir: File, vararg keys: String): String {
        if (dslValue.isNotBlank()) return dslValue
        for (key in keys) {
            ModFileReader.readMetaValue(projectDir, key)?.let { return it }
        }
        return ""
    }

    /** The counter file a mod project keeps its `{build_count}` in. */
    fun buildCounterFile(projectDir: File): File = File(projectDir, "build/buildCounter.txt")

    /** Reads the counter in [file], or 0 when it does not exist yet. */
    fun readBuildCounter(file: File): Int =
        if (file.exists()) file.readText().trim().toIntOrNull() ?: 0 else 0

    /**
     * Adds one to the counter in [file] and returns the new value.
     *
     * The whole read-modify-write runs under an exclusive [java.nio.channels.FileLock], so two Gradle
     * processes building the same project cannot lose an increment the way `write(read() + 1)` could.
     *
     * File-based on purpose: the `jar` task increments it from its action, and an action may not reach back
     * into the extension (which owns a `Project`) if the configuration cache is to serialize the task.
     */
    fun incrementBuildCounter(file: File): Int {
        file.parentFile.mkdirs()
        java.io.RandomAccessFile(file, "rw").use { raf ->
            raf.channel.use { channel ->
                channel.lock().use {
                    val bytes = ByteArray(raf.length().toInt())
                    raf.seek(0)
                    raf.readFully(bytes)
                    val next = (String(bytes).trim().toIntOrNull() ?: 0) + 1
                    raf.setLength(0)
                    raf.seek(0)
                    raf.write(next.toString().toByteArray())
                    return next
                }
            }
        }
    }
}
