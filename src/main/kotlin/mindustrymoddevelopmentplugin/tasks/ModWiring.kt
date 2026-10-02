package mindustrymoddevelopmentplugin.tasks

import mindustrymoddevelopmentplugin.game.MindustryApi
import mindustrymoddevelopmentplugin.game.GameDataDir
import mindustrymoddevelopmentplugin.platform.HostPlatform
import mindustrymoddevelopmentplugin.meta.ArtifactNaming
import mindustrymoddevelopmentplugin.dsl.MindustryBuildConfig
import mindustrymoddevelopmentplugin.dsl.MindustryDownloadConfig
import mindustrymoddevelopmentplugin.dsl.MindustryModExtension
import mindustrymoddevelopmentplugin.dsl.MindustryModRootExtension
import mindustrymoddevelopmentplugin.dsl.MindustryRunConfig
import mindustrymoddevelopmentplugin.meta.ModFileReader
import mindustrymoddevelopmentplugin.meta.ModMeta
import mindustrymoddevelopmentplugin.tasks.GradleProperties
import mindustrymoddevelopmentplugin.tasks.JarTask
import mindustrymoddevelopmentplugin.tasks.DeployTask
import mindustrymoddevelopmentplugin.tasks.BuildModHJsonTask
import mindustrymoddevelopmentplugin.tasks.AndroidSdk
import mindustrymoddevelopmentplugin.tasks.ClearModsTask
import mindustrymoddevelopmentplugin.tasks.DownloadMindustryTask
import mindustrymoddevelopmentplugin.tasks.GenerateIdeaRunConfigsTask
import mindustrymoddevelopmentplugin.tasks.IdeaRunConfigs
import mindustrymoddevelopmentplugin.tasks.JarAndroidTask
import mindustrymoddevelopmentplugin.tasks.RunMindustryTask
import mindustrymoddevelopmentplugin.tasks.RunLogging
import java.io.File
import javax.inject.Inject
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.logging.Logger
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSet
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.build.event.BuildEventsListenerRegistry
import mindustrymoddevelopmentplugin.MindustryModPlugin

/**
 * Everything that wires one mod project: its source sets, the Kotlin plugin check, the artifact
 * tasks and the Android SDK options they share. Moved out of the plugin entry point, which had
 * grown past 700 lines, and kept as one object so each file still declares exactly one thing.
 */
internal object ModWiring {

    internal fun configureModule(project: Project, ext: MindustryModExtension, hasModFile: Boolean) {
        // Computed once and shared: the Kotlin-presence scan and the source sets must agree, and
        //     building the list twice would walk the same paths twice.
        // Every project whose directory sits inside this one: a root project that is also a mod must not
        // package its subprojects' sources (and a nested subproject must not package its own children).
        val nestedProjectDirs = project.rootProject.allprojects
            .filter { it != project && it.projectDir.toPath().startsWith(project.projectDir.toPath()) }
            .map { it.projectDir }
        val excludedSources = modSourceExcludes(
            project.projectDir,
            project.gradle.gradleUserHomeDir,
            ownProjectDataDir(project),
            nestedProjectDirs,
        )
        val kotlin = requireKotlinPluginIfNeeded(project, excludedSources)
        val rootExt = project.rootProject.extensions.findByType(MindustryModRootExtension::class.java)

        warnAboutRootOnlySettings(project)
        MindustryApi.configure(project, rootExt?.mindustryApiVersion?.orNull)
        configureSourceDirs(project, kotlin, excludedSources)

        // -- Names ---------------------------------------------------------------
        val build = rootExt?.build
        val author = ArtifactNaming.resolveMetaValue(ext.modMeta.author, project.projectDir, "author")
        // Only worth warning about when the format asks for it: the default one does not, and warning on
        // every Java-only mod about an unused tag is pure noise.
        val authorFormat = build?.format?.get() ?: MindustryBuildConfig.DEFAULT_FORMAT
        if (author.isBlank() && authorFormat.contains("{author}")) {
            project.logger.warn(
                "Mod '${project.name}': modMeta.author is not set while build.format contains {author}, so " +
                "that part of the artifact name stays empty."
            )
        }
        val names = ArtifactNaming.names(project, ext, rootExt)

        val useHJson = build?.useHJson?.get() ?: MindustryBuildConfig.DEFAULT_USE_HJSON
        val modFileName = if (useHJson) "mod.hjson" else "mod.json"
        val generateMeta = ext.generateModMeta.get()
        val libsDir = project.layout.buildDirectory.dir("libs").get().asFile

        // -- Tasks ---------------------------------------------------------------
        project.tasks.register("buildModHJson") { task ->
            task.group = MindustryModPlugin.MINDUSTRY_GROUP
            task.description = "Writes mod.json / mod.hjson from the modMeta { } block."
            BuildModHJsonTask.configure(
                task, ext.modMeta, generateMeta, useHJson, project.projectDir,
                project.layout.buildDirectory.file(modFileName).get().asFile,
            )
        }

        project.tasks.named("jar", Jar::class.java) { task ->
            JarTask.configure(
                task, names.jar, ext, hasModFile, project, generateMeta, modFileName,
                metaName = ext.modMeta.name,
                counterFile = ArtifactNaming.buildCounterFile(project.projectDir),
            )
        }

        project.tasks.register("jarAndroid") { task ->
            task.group = MindustryModPlugin.MINDUSTRY_GROUP
            task.description = "Builds the Android DEX jar with d8."
            JarAndroidTask.configure(
                    task, libsDir, names.jar, names.android,
                    project.files(
                        project.configurations.getByName("compileClasspath"),
                        project.configurations.getByName("runtimeClasspath"),
                    ),
                    jarAndroidOptions(project, rootExt),
                )
        }

        // A project may already deploy somewhere (a server, a container). Registering our own task under the
        // same name would fail the whole build, so the user's task wins, and we say what is not happening.
        if (project.tasks.findByName("deploy") != null) {
            project.logger.warn(
                "Project '${project.path}' already has a 'deploy' task, so the plugin did not add its own " +
                "merge task. runMindustry will not deploy this project's mod until that task is renamed."
            )
            return@configureModule
        }
        project.tasks.register("deploy", Jar::class.java) { task ->
            task.group = MindustryModPlugin.MINDUSTRY_GROUP
            task.description = "Merges the desktop and Android jars into the one the game loads."
            DeployTask.configure(
                task, libsDir, names.jar, names.android, names.deploy, project,
                // Auto-download means jarAndroid will provide an SDK itself; otherwise one has to be found.
                androidSdkAvailable = jarAndroidOptions(project, rootExt).let { options ->
                    options.autoDownloadSdk || AndroidSdk.findAndroidSdkDir(options.androidSdkDir?.orNull?.asFile) != null
                },
            )
        }
    }

    /**
     * Whether the Kotlin plugin is applied, failing when `.kt` sources exist without it.
     *
     * The Kotlin plugin cannot be applied for the user: `kotlin { }` accessors are generated only
     * for plugins declared in the project's own `plugins { }` block, and applying it late is
     * rejected by Kotlin itself. A project that ships `.kt` sources without the plugin therefore
     * gets an error naming the first file. `java`, by contrast, can be supplied here — and must be,
     * because the very next step adds to `compileOnly`, a configuration the Java plugin creates.
     *
     */
    internal fun requireKotlinPluginIfNeeded(project: Project, excluded: List<String>): KotlinProjectExtension? {
        val kotlin = project.extensions.findByType(KotlinProjectExtension::class.java)
        if (kotlin == null) {
            val sources = project.fileTree(project.projectDir) { tree ->
                tree.include("**/*.kt")
                tree.exclude(excluded)
            }.files
            if (sources.isNotEmpty()) {
                throw GradleException(
                    "Found ${sources.size} Kotlin source file(s) " +
                    "(first: ${sources.first().relativeTo(project.projectDir)}), " +
                    "but the kotlin(\"jvm\") plugin is not applied.\n" +
                    "Apply it in this project's own plugins block:\n" +
                    "  plugins {\n" +
                    "      kotlin(\"jvm\") version \"<version>\"\n" +
                    "  }\n" +
                    "A mod with no `.kt` files does not need the Kotlin plugin at all."
                )
            }
        }
        // `java` is applied in apply() already (`java-library` / `application` bring it too), and the very
        // next step adds to `compileOnly`, a configuration it creates.
        return kotlin
    }

    /**
     * Points the source sets at the **project directory**.
     *
     * Sources live next to `build.gradle.kts` (single-project mode) or in the subproject root
     * (multi-project mode, `src/<mod>/`). Registering it on the source set keeps the IDE module in
     * sync with what is compiled. The earlier approach left the source set on the default
     * `src/main/kotlin` and pointed `compileKotlin` at a file tree instead, so IDEA had no source
     * root for these files and breakpoints could not be resolved onto them.
     *
     * `srcDir` is additive, so a build script that sets its own `srcDirs` keeps them, and
     * `src/main/kotlin` stays valid because it lives inside the project dir.
     */
    internal fun configureSourceDirs(project: Project, kotlin: KotlinProjectExtension?, excluded: List<String>) {
        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
        val main: SourceSet = sourceSets.getByName("main")

        main.java.srcDir(project.projectDir)
        main.java.exclude(excluded)
        // Resources stay empty on purpose: with the project dir as a resource dir, files like
        // build.gradle.kts would end up inside the jar.
        main.resources.setSrcDirs(emptyList<File>())

        kotlin?.sourceSets?.getByName("main")?.let { mainKotlin ->
            mainKotlin.kotlin.srcDir(project.projectDir)
            mainKotlin.kotlin.exclude(excluded)
        }
    }

    /** Collects the `jarAndroid` settings, falling back to the `run { }` conventions. */
    internal fun jarAndroidOptions(project: Project, rootExt: MindustryModRootExtension?): JarAndroidTask.Options {
        val build = rootExt?.build
        val run = rootExt?.run
        val download = rootExt?.download
        return JarAndroidTask.Options(
            androidSdkDir = build?.androidSdkDir,
            d8Args = build?.d8Args?.get().orEmpty(),
            d8TimeoutMinutes = build?.d8TimeoutMinutes?.get() ?: MindustryBuildConfig.DEFAULT_D8_TIMEOUT_MINUTES,
            d8DrainJoinMillis = build?.d8DrainJoinMillis?.get()
                ?: MindustryBuildConfig.DEFAULT_D8_DRAIN_JOIN_MILLIS,
            // `build.d8Executable` always wins. The PATH is only consulted when the project did *not* name an
            // SDK directory: a configured `androidSdkDir` is an explicit instruction, and a project that
            // points it at an empty directory expects that one to be installed rather than silently using
            // whatever d8 happens to be on the PATH. With no SDK configured — Termux, where `pkg install d8`
            // is the whole setup — the PATH is exactly what should be used, and no SDK is touched.
            d8Command = AndroidSdk.resolveD8(
                configured = build?.d8Executable?.orNull?.asFile,
                pathEnv = if (build?.androidSdkDir?.orNull != null) null else System.getenv("PATH"),
                sdkRoot = null,
            ),
            autoDownloadSdk = download?.androidSdkAutoDownload?.get()
                ?: MindustryDownloadConfig.DEFAULT_ANDROID_SDK_AUTO_DOWNLOAD,
            sdkDownloadUrl = download?.androidSdkDownloadUrl?.orNull
                ?: MindustryDownloadConfig.commandLineToolsUrl(),
            sdkDownloadPackages = download?.androidSdkDownloadPackages?.get()
                ?: MindustryDownloadConfig.DEFAULT_ANDROID_SDK_DOWNLOAD_PACKAGES,
            sdkDownloadTimeoutMinutes = download?.androidSdkDownloadTimeoutMinutes?.get()
                ?: MindustryDownloadConfig.DEFAULT_ANDROID_SDK_DOWNLOAD_TIMEOUT_MINUTES,
            sdkExtraArgs = download?.androidSdkExtraArgs?.get().orEmpty(),
            sdkInstallDir = run?.androidSdkInstallDir?.orNull?.asFile
                ?: File(project.gradle.gradleUserHomeDir, "$MindustryModPlugin.PLUGIN_DIR_NAME/android-sdk"),
        )
    }

    /**
     * Directories that must never be treated as mod sources.
     *
     * `build/` and `.gradle/` are Gradle's own outputs. Kotlin script files are excluded too, because
     * the Kotlin source filter matches them — without that, `build.gradle.kts` and `settings.gradle.kts`
     * would be compiled as mod sources. (Written as a glob in the code: a literal double asterisk
     * followed by a slash here would end this comment.)
     *
     * Any Gradle cache tree is excluded as well, because a Gradle user home holds extracted Kotlin
     * files for the DSL accessors. A project-local `GRADLE_USER_HOME` would otherwise be compiled as
     * mod source, and so would one left behind by an earlier build after the variable moved
     * elsewhere — "Found 182 Kotlin source file(s)" is the symptom that led here.
     *
     * The project-local game data directory is excluded too — both its default location and any
     * custom [dataDir] inside this project — because the game writes saves, screenshots and mods
     * there, and none of that is mod source. Since [dataDir] is optional and usually null, the
     * default entry is always present so a leftover directory is ignored as well.
     *
     * Visible for the tests that pin the exclude list.
     */
    internal fun modSourceExcludes(
        projectDir: File,
        gradleUserHome: File,
        dataDir: File? = null,
        nestedProjectDirs: List<File> = emptyList(),
    ): List<String> {
        // Always excluded: the default location, so a leftover data directory from an earlier build
        // is not compiled as mod source either.
        val excludes = mutableListOf("build/**", ".gradle/**", "**/*.kts", "**/caches/**", "data/**")
        val root = projectDir.toPath()
        val home = gradleUserHome.toPath()
        if (home.startsWith(root) && home != root) {
            excludes.add(root.relativize(home).toString().replace(File.separatorChar, '/') + "/**")
        }
        // Directories of *other* projects that live inside this one. A root project that is itself a mod
        // would otherwise compile the subprojects' sources into its own jar — duplicate classes at best,
        // someone else's code shipped at worst. Subprojects normally live under `src/<name>/`.
        for (nested in nestedProjectDirs) {
            val nestedPath = nested.toPath()
            if (nestedPath.startsWith(root) && nestedPath != root) {
                excludes.add(root.relativize(nestedPath).toString().replace(File.separatorChar, '/') + "/**")
            }
        }
        // A custom data directory inside this project is a source-root candidate too.
        if (dataDir != null) {
            val dataPath = dataDir.toPath()
            if (dataPath.startsWith(root) && dataPath != root) {
                excludes.add(root.relativize(dataPath).toString().replace(File.separatorChar, '/') + "/**")
            }
        }
        return excludes
    }

    /**
     * Warns when a *subproject* sets a setting that only the root project's block can decide.
     *
     * `mindustryApiVersion` and the whole `build { }` block are read from the root project — they name the
     * artifacts and pick the API dependency for every mod in the build — while `run { }` is per project.
     * Setting them on a subproject used to do nothing at all, silently.
     */
    internal fun warnAboutRootOnlySettings(project: Project) {
        if (project == project.rootProject) return
        val ext = project.extensions.findByType(MindustryModRootExtension::class.java) ?: return
        val build = ext.build

        val ignored = buildList {
            if (ext.mindustryApiVersion.isPresent) add("mindustryApiVersion")
            if (build.useHJson.get() != MindustryBuildConfig.DEFAULT_USE_HJSON) add("build.useHJson")
            if (build.format.get() != MindustryBuildConfig.DEFAULT_FORMAT) add("build.format")
            if (build.jarSuffix.get() != MindustryBuildConfig.DEFAULT_JAR_SUFFIX) add("build.jarSuffix")
            if (build.androidSuffix.get() != MindustryBuildConfig.DEFAULT_ANDROID_SUFFIX) {
                add("build.androidSuffix")
            }
            if (build.deploySuffix.get() != MindustryBuildConfig.DEFAULT_DEPLOY_SUFFIX) add("build.deploySuffix")
            if (build.timeFormat.get() != MindustryBuildConfig.DEFAULT_TIME_FORMAT) add("build.timeFormat")
            if (build.d8Args.get() != MindustryBuildConfig.DEFAULT_D8_ARGS) add("build.d8Args")
            if (build.androidSdkDir.isPresent) add("build.androidSdkDir")
        }
        if (ignored.isEmpty()) return

        project.logger.warn(
            "Ignoring ${ignored.joinToString(", ")} on subproject '${project.path}': these are decided by " +
            "the root project's mindustryModRoot { } block, because they name the artifacts and the API " +
            "for the whole build. Move them there, or delete them here to silence this warning."
        )
    }

    /**
     * Data directory [project]'s own `run { }` block asks for, or null when the build leaves the choice
     * to the game (in which case the data directory is not a source-root candidate here).
     */
    internal fun ownProjectDataDir(project: Project): File? =
        project.extensions.findByType(MindustryModRootExtension::class.java)?.run?.gameDataDir?.orNull?.asFile
}
