package mindustrymoddevelopmentplugin.dsl

import mindustrymoddevelopmentplugin.ArtifactNaming
import mindustrymoddevelopmentplugin.TargetPlatform
import mindustrymoddevelopmentplugin.meta.ModMeta
import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider

/**
 * Mod subproject extension (`mindustryMod { ... }`).
 *
 * Declared by every mod subproject under `src/<mod-name>/` to configure mod metadata.
 *
 * Metadata itself lives in the separate top-level `modMeta { }` extension; this extension
 * keeps the project-side switches (metadata generation, readme/license/icon/assets).
 *
 * Usage example:
 * ```kotlin
 * modMeta {
 *     name = "MyMod"
 *     author = "You"
 *     version = "1.0.0"
 *     java = true
 * }
 *
 * mindustryMod {
 *     generateModMeta = true
 * }
 * ```
 *
 * `mindustryMod { modMeta { ... } }` still works and configures the very same object.
 */
abstract class MindustryModExtension @Inject constructor(val project: Project) {

    // -- Mod metadata ------------------------------------------------------------
    // Extension properties, configured directly with a block in build.gradle.kts

    /**
     * Mod metadata, shared with the project-level `modMeta { }` extension.
     *
     * The instance is registered once per project under the name `modMeta`, so the top-level
     * block and the [modMeta] action here configure the same object. It is created on demand,
     * which keeps this extension usable on a project that never applied the plugin (tests,
     * hand-built projects).
     */
    val modMeta: ModMeta
        get() = project.extensions.findByType(ModMeta::class.java)
            ?: project.extensions.create("modMeta", ModMeta::class.java)

    // -- Gradle lazy properties --------------------------------------------------

    /** Whether to generate `mod.hjson`/`mod.json` automatically. Default `false`. */
    abstract val generateModMeta: Property<Boolean>

    /** README file path. Default `<projectDir>/README.md`. */
    abstract val readme: RegularFileProperty

    /** License file path. Default `<projectDir>/LICENSE`. */
    abstract val license: RegularFileProperty

    /** Mod icon file path. Default `<projectDir>/icon.png`. */
    abstract val icon: RegularFileProperty

    /** Mod asset directories (multiple allowed). Default `<projectDir>/assets/`. */
    abstract val assets: ConfigurableFileCollection

    init {
        generateModMeta.convention(false)
        readme.convention(project.layout.projectDirectory.file("README.md"))
        license.convention(project.layout.projectDirectory.file("LICENSE"))
        icon.convention(project.layout.projectDirectory.file("icon.png"))
        assets.from(project.layout.projectDirectory.dir("assets"))
    }

    /**
     * Configures the [ModMeta] metadata.
     *
     * @param action  configuration closure receiving the [ModMeta] instance.
     */
    fun modMeta(action: Action<ModMeta>) {
        action.execute(modMeta)
    }

    // -- Utility methods ---------------------------------------------------------

    /**
     * Generates the mod jar file name (without the `.jar` suffix).
     *
     * Consistent with the plugin's internal jar naming: reads `format` / `jarSuffix` /
     * `androidSuffix` from `mindustryModRoot { build { } }` and applies the same
     * name/version fallbacks as the plugin (name → `"mod"`, version → `"0.0.1"`).
     *
     * @param platform  target platform, default [TargetPlatform.All] (no suffix appended).
     * @return  lazily evaluated file name Provider.
     */
    fun modVersion(platform: TargetPlatform = TargetPlatform.All): Provider<String> {
        return project.providers.provider {
            val rootExt = project.rootProject.extensions.findByType(MindustryModRootExtension::class.java)
            // Same implementation as the plugin's task naming, so the reported name is the produced
            // name — including {author} / {time} and the metadata-file fallback.
            val names = ArtifactNaming.names(project, this, rootExt)
            when (platform) {
                TargetPlatform.All -> names.base
                TargetPlatform.Jar -> names.jar
                TargetPlatform.Android -> names.android
            }
        }
    }

    /**
     * Reads the current build counter.
     *
     * Stored in `build/buildCounter.txt`; incremented after every `jar` task run.
     *
     * @return  the current count, or `0` if the file does not exist.
     */
    @Synchronized
    fun readBuildCounter(): Int {
        val file = project.file("build/buildCounter.txt")
        return if (file.exists()) file.readText().trim().toIntOrNull() ?: 0 else 0
    }

    /**
     * Adds one to the counter and returns the new value.
     *
     * The whole read-modify-write runs under an exclusive [java.nio.channels.FileLock], so two Gradle
     * processes building the same project cannot lose an increment the way `write(read() + 1)` could.
     *
     * The name itself is resolved while the build is configured, outside that lock, so two
     * *concurrent* builds of one project can still pick the same `{build_count}`. Put `{time}` in
     * [MindustryBuildConfig.format] when uniqueness matters more than a small number.
     */
    @Synchronized
    fun incrementBuildCounter(): Int {
        val file = project.file("build/buildCounter.txt")
        file.parentFile.mkdirs()
        java.io.RandomAccessFile(file, "rw").use { raf ->
            raf.channel.use { channel ->
                channel.lock().use {
                    val bytes = ByteArray(raf.length().toInt())
                    raf.seek(0)
                    raf.readFully(bytes)
                    val current = String(bytes).trim().toIntOrNull() ?: 0
                    val next = current + 1
                    raf.setLength(0)
                    raf.seek(0)
                    raf.write(next.toString().toByteArray())
                    channel.force(true)
                    return next
                }
            }
        }
    }

    /**
     * Writes the build counter.
     *
     * @param count  the count value to write.
     */
    @Synchronized
    fun writeBuildCounter(count: Int) {
        val file = project.file("build/buildCounter.txt")
        file.parentFile.mkdirs()
        file.writeText(count.toString())
    }
}
