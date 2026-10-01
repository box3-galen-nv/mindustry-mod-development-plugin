package mindustrymoddevelopmentplugin.tasks

import mindustrymoddevelopmentplugin.dsl.MindustryModExtension
import mindustrymoddevelopmentplugin.meta.ModFileReader
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.CopySpec
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.bundling.Jar

/**
 * Configures the `jar` task, which packages the desktop mod jar.
 *
 * The name is computed once here (via [mindustrymoddevelopmentplugin.dsl.ArtifactNaming]) and the build
 * counter is advanced after a successful build.
 */
internal object JarTask {
    /**
     * Package the desktop jar: merge runtimeClasspath dependencies + icon + README
     * + LICENSE + mod.hjson / mod.json + assets directories
     */
    fun configure(
        task: Jar,
        jarName: String,
        ext: MindustryModExtension,
        hasModFile: Boolean,
        project: Project,
        generateMeta: Boolean,
        modFileName: String,
    ) {
        task.dependsOn("buildModHJson")
        task.archiveFileName.set("$jarName.jar")
        task.duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        // Validate that mod metadata exists before building.
        //     If both mindustryMod { } DSL and mod.hjson / mod.json are absent,
        //     throw a helpful error showing the minimal required configuration.
        task.doFirst {
            if (ext.modMeta.name.isBlank() && !hasModFile) {
                throw GradleException(
                    "No mod metadata found for project '${project.name}'.\n\n" +
                    "Configure mindustryMod { } in your build.gradle.kts, " +
                    "or create a mod.hjson / mod.json file.\n\n" +
                    "Example:\n" +
                    "  mindustryMod {\n" +
                    "      modMeta {\n" +
                    "          name = \"my-mod\"\n" +
                    "          version = \"1.0.0\"\n" +
                    "          java = true\n" +
                    "      }\n" +
                    "  }"
                )
            }
        }

        // Merge runtime classpath dependencies into the jar.
        //     Directories are kept as-is; jar/zips are exploded.
        task.from(project.providers.provider {
            project.configurations.getByName("runtimeClasspath").files.map { file ->
                if (file.isDirectory) file else project.zipTree(file)
            }
        })

        // Icon — Mindustry only reads icon.png or preview.png from the jar root.
        //     If the source is not one of these two names, rename it to icon.png.
        //     Non-PNG files named icon.* / preview.* trigger a warning.
        ext.icon.orNull?.asFile?.takeIf { it.exists() }?.let { iconFile ->
            val fileName = iconFile.name
            val baseName = iconFile.nameWithoutExtension.lowercase()
            val extension = iconFile.extension.lowercase()

            val targetName = when (fileName) {
                "icon.png", "preview.png" -> fileName
                else -> "icon.png"
            }

            if (extension != "png" && (baseName == "icon" || baseName == "preview")) {
                project.logger.warn(
                    "Mod icon file \"$fileName\" is not a PNG image. " +
                    "Mindustry only recognizes icon.png (or preview.png as a fallback). " +
                    "The file will be renamed to $targetName in the output jar, " +
                    "but a PNG file is strongly recommended."
                )
            }

            task.from(iconFile.parentFile) { spec: CopySpec ->
                spec.include(fileName)
                if (fileName != targetName) spec.rename(fileName, targetName)
            }
        }

        // README — included in the jar root if the file exists.
        ext.readme.orNull?.asFile?.takeIf { it.exists() }?.let { readmeFile ->
            task.from(readmeFile.parentFile) { spec: CopySpec -> spec.include(readmeFile.name) }
        }

        // LICENSE — included in the jar root if the file exists.
        ext.license.orNull?.asFile?.takeIf { it.exists() }?.let { licenseFile ->
            task.from(licenseFile.parentFile) { spec: CopySpec -> spec.include(licenseFile.name) }
        }

        // Mod metadata file — either generated in build/ (generateMeta = true)
        //     or picked up from the project root (any of the engine's four file names).
        if (generateMeta) {
            val buildModFile = project.layout.buildDirectory.file(modFileName).get().asFile
            task.from(buildModFile.parentFile) { spec: CopySpec -> spec.include(buildModFile.name) }
        } else if (hasModFile) {
            // Include whichever metadata files exist — a project may ship only
            //     mod.json or plugin.hjson while useHJson (and thus modFileName) says
            //     mod.hjson.
            task.from(project.projectDir) { spec: CopySpec -> spec.include(ModFileReader.FILE_NAMES) }
        }

        // Asset directories — each configured directory is included recursively.
        //     Missing directories are silently skipped (isDirectory check).
        ext.assets.forEach { dir ->
            if (dir.isDirectory) {
                task.from(dir) { spec: CopySpec -> spec.include("**") }
            }
        }

        // Increment the build counter file after jar is built.
        task.doLast { _: Task ->
            ext.incrementBuildCounter()
        }
    }
}
