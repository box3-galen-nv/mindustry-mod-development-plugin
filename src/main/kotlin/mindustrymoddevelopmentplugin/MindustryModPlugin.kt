package mindustrymoddevelopmentplugin

import mindustrymoddevelopmentplugin.dsl.HostPlatform
import mindustrymoddevelopmentplugin.dsl.ArtifactNaming
import mindustrymoddevelopmentplugin.dsl.MindustryBuildConfig
import mindustrymoddevelopmentplugin.dsl.MindustryDownloadConfig
import mindustrymoddevelopmentplugin.dsl.MindustryModExtension
import mindustrymoddevelopmentplugin.dsl.MindustryModRootExtension
import mindustrymoddevelopmentplugin.dsl.MindustryRunConfig
import mindustrymoddevelopmentplugin.meta.ModFileReader
import mindustrymoddevelopmentplugin.meta.ModMeta
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

/**
 * Gradle plugin for building Mindustry mods.
 *
 * The plugin registers two extensions on every project — `mindustryModRoot { }` (download, run,
 * build settings) and `mindustryMod { }` plus the top-level `modMeta { }` (mod metadata) — and
 * wires up the tasks whose bodies live in one file per task under `tasks/`
 * (`<TaskName>Task.kt`).
 *
 * It works in two phases, and the split matters:
 * - [apply] registers the extensions and the download/run tasks right away, and adds the
 *   `afterEvaluate` hook that decides whether a project is a mod.
 * - The mod tasks (`jar`, `jarAndroid`, `deploy`, `buildModHJson`) are registered from that hook,
 *   because "is this a mod project?" can only be answered once the build script has run: it depends
 *   on `modMeta.name` and on whether a metadata file exists.
 *
 * Values are read through `Provider`s or inside task configuration actions, never while the build
 * script is still executing, so a DSL value always wins over its convention default.
 */
class MindustryModPlugin @Inject constructor(
    /** Needed to close a leaked `runMindustry` log file when the task fails — Gradle has no `doFinally`. */
    private val buildEvents: BuildEventsListenerRegistry,
) : Plugin<Project> {

    override fun apply(project: Project) {
        // Once per Gradle instance, before any task can run.
        RunLogging.registerTaskFinishListener(project, buildEvents)

        registerExtensions(project)

        // `java` is applied here rather than in afterEvaluate so that `java { }`, `sourceSets { }` and the
        // `implementation` accessor all exist while the build script is still being evaluated. Whether a
        // project is a mod is not known yet, and applying `java` to a non-mod project is harmless.
        if (!project.plugins.hasPlugin("java")) {
            project.pluginManager.apply("java")
        }

        project.afterEvaluate { configureModuleIfMod(project) }

        configureRoot(project)
    }

    /**
     * Registers `modMeta { }` and `mindustryMod { }`.
     *
     * Both reach the same [ModMeta] instance (see [MindustryModExtension.modMeta]), so the metadata
     * can be configured either as a top-level block or inside `mindustryMod { }`.
     */
    private fun registerExtensions(project: Project) {
        project.extensions.create("modMeta", ModMeta::class.java)
        project.extensions.create("mindustryMod", MindustryModExtension::class.java, project)
    }

    /**
     * Adds the mod build tasks to every project that is a mod project.
     *
     * The only project that is skipped is a root project without metadata: in a multi-project build
     * the root orchestrates the build (`downloadMindustry` / `runMindustry`) and the mods live in
     * subprojects, so the root should not package itself.
     */
    private fun configureModuleIfMod(project: Project) {
        val ext = project.extensions.findByType(MindustryModExtension::class.java) ?: return
        // Any of the engine's four metadata file names counts as metadata:
        // mod.json / mod.hjson / plugin.json / plugin.hjson.
        val hasModFile = ModFileReader.existingFiles(project.projectDir).isNotEmpty()
        val isRootWithoutModConfig = project == project.rootProject &&
            ext.modMeta.name.isBlank() && !hasModFile
        if (isRootWithoutModConfig) return

        configureModule(project, ext, hasModFile)
    }

    // =========================================================================
    //  Download + run (every project)
    // =========================================================================

    /** Registers `mindustryModRoot { }` and the tasks that do not depend on the project being a mod. */
    private fun configureRoot(project: Project) {
        val ext = project.extensions.create("mindustryModRoot", MindustryModRootExtension::class.java)
        val logger = project.logger
        val download = ext.download
        val run = ext.run
        val debug = ext.debug

        // The KDoc promises a default, and a convention is where Gradle expects one: the value depends on
        // the Gradle user home, which the DSL object itself cannot see.
        run.androidSdkInstallDir.convention(
            project.layout.dir(
                project.provider { File(project.gradle.gradleUserHomeDir, "$PLUGIN_DIR_NAME/android-sdk") },
            ),
        )

        // The headless server and the staging directory live in the *root* project: one server jar and one
        // import directory for the whole build, however many mod projects there are.
        download.headlessJarPath.convention(
            project.layout.file(
                project.provider { File(project.rootProject.projectDir, "build/game/server-release.jar") },
            ),
        )
        run.headlessServerWorkingDir.convention(
            project.layout.dir(project.provider { File(project.rootProject.projectDir, "build/headless") }),
        )
        // Staging goes to the home directory rather than /sdcard: it is Termux-private, has no path length
        // or shared-storage permission problems, and the game's import dialog can read it.
        run.androidStagingDir.convention(
            project.layout.dir(
                project.provider { File(System.getProperty("user.home"), "AndroidStaging") },
            ),
        )

        // A relative MINDUSTRY_DATA_DIR is resolved against this *Gradle* process's working directory,
        // while the game resolves it against its own — so the plugin and the game can disagree.
        val envDataDir = System.getenv(GameDataDir.ENV_VAR)?.trim().orEmpty()
        if (envDataDir.isNotEmpty() && !File(envDataDir).isAbsolute) {
            logger.warn(
                "${GameDataDir.ENV_VAR} is set to the relative path '$envDataDir'. The game resolves it " +
                "against its own working directory, so set an absolute path or run.gameDataDir to be sure " +
                "both use the same directory."
            )
        }

        // Lazy on purpose: `resolveDownloadFileName` validates the template, and validating during
        // configuration would reject a value the build script has not assigned yet.
        val resolvedFileName = project.providers.provider { resolveDownloadFileName(download, logger) }

        // The default game path depends on the version, so it is also resolved lazily.
        download.mindustryGamePath.convention(
            project.rootProject.layout.projectDirectory
                .dir("build").dir("game")
                .file(resolvedFileName.map { "$it.jar" })
        )

        // A path ending in `.jar` is used as-is; anything else is a directory that gets the file
        // name appended.
        val downloadPath = project.providers.provider {
            val gameFile = download.mindustryGamePath.get().asFile
            if (gameFile.extension == "jar") gameFile else File(gameFile, "${resolvedFileName.get()}.jar")
        }

        project.tasks.register("downloadMindustry") { task ->
            DownloadMindustryTask.configure(
                task, downloadPath.get(),
                download.mindustryDownloadUrl.get(),
                download.mindustryDownloadVersion.get(),
                // Captured as a plain value: the task action must not reach back into the project.
                offline = project.gradle.startParameter.isOffline,
            )
        }

        // Root project only: there is one server jar for the whole build, and its path convention already
        // points at the root, so a per-project task would only be noise.
        if (project == project.rootProject) {
            project.tasks.register("downloadHeadlessServer") { task ->
                DownloadMindustryTask.configure(
                    task, download.headlessJarPath.get().asFile,
                    download.mindustryDownloadUrl.get(),
                    download.mindustryDownloadVersion.get(),
                    assetName = "server-release.jar",
                    pathPropertyName = "download.headlessJarPath",
                    offline = project.gradle.startParameter.isOffline,
                )
            }
        }

        // Resolved at task realization time, when every project has been configured — otherwise the
        // `deploy` tasks of subprojects do not exist yet and the scan silently finds nothing.
        //
        // The root project is included on purpose: in single-project mode the root project *is* the mod,
        // and `subprojects` does not contain it, so a run used to deploy nothing without saying so. A
        // root that is not a mod simply has no `deploy` task and is filtered out here.
        val modProjects = project.providers.provider {
            (listOf(project.rootProject) + project.rootProject.subprojects)
                // `is Jar` and not just "a task called deploy": a subproject may deploy to a server or a
                // container with its own `deploy`, and that one used to be picked up here — clearMods then
                // crashed on it and runMindustry complained that it is not a Jar task.
                .filter { it.tasks.findByName("deploy") is Jar }
        }

        // The directory the game would use by itself: MINDUSTRY_DATA_DIR, else the per-OS default.
        val implicitDataDir = project.providers.provider {
            GameDataDir.resolve(
                hostPlatform = run.hostPlatform.get(),
                androidAppId = run.androidAppId.get(),
            ).absoluteFile
        }

        // Only a directory *this build* picks needs the JVM property — the game already honours the
        // environment variable and its own per-OS default, so passing the property for those would be
        // redundant (and would earn a warning on a version that cannot read it). A project-local
        // directory is simply one of the values the build script can set.
        val chosenDataDir = project.providers.provider { run.gameDataDir.orNull?.asFile?.absoluteFile }

        // The game only looks for mods under <dataDir>/mods, so that path follows the data directory;
        // it does not have to exist yet.
        val modsDir = project.providers.provider { File(chosenDataDir.orNull ?: implicitDataDir.get(), "mods") }

        project.tasks.register("clearMods") { task ->
            task.group = MINDUSTRY_GROUP
            task.description = "Removes the mod jars this plugin deployed earlier from the mods folder."
            ClearModsTask.configure(
                task, modProjects.get(), run.cleanDeployedFiles.get(),
                run.deployTag.get(), modsDir.get(), project,
            )
        }

        project.tasks.register("runMindustry", JavaExec::class.java) { task ->
            task.group = MINDUSTRY_GROUP
            task.description = "Deploys the built mods and launches the game."
            val resolvedDataDir = chosenDataDir.orNull
            if (resolvedDataDir != null) {
                warnIfDataDirUnsupported(project, download.mindustryDownloadVersion.get())
                warnAboutAndroidBuildDefaults(project, run)
            }

            val headless = if (run.useHeadlessServer.get()) {
                warnAboutHeadlessVersionMismatch(project, download, ext)
                if (run.gameDataDir.orNull != null) {
                    // Saying nothing here would be the same silent-ignore bug the root-only warning exists for.
                    project.logger.warn(
                        "run.gameDataDir is ignored while run.useHeadlessServer is on: the server always " +
                        "uses <headlessServerWorkingDir>/config as its data directory."
                    )
                }
                RunMindustryTask.HeadlessOptions(
                    jar = download.headlessJarPath.get().asFile,
                    workingDir = run.headlessServerWorkingDir.get().asFile,
                    // The path, not the bare name: a subproject's runMindustry must depend on the root's task.
                    downloadTaskName = ":downloadHeadlessServer",
                )
            } else {
                null
            }
            val resolvedModsDir = headless
                ?.let { File(File(it.workingDir, "config"), "mods") }
                ?: modsDir.get()
            RunMindustryTask.configure(
                task, modProjects.get(), downloadPath.get(), resolvedModsDir, project,
                run.useDeployRun.get(), run.deployTag.get(),
                debug.maxLogFiles.get(), debug.enableRunLogging.get(),
                dataDir = resolvedDataDir,
                debug = RunMindustryTask.DebugOptions(
                    // The generated IDEA configuration passes -PmindustryDebug=true. A property given on
                    //     the command line wins over the DSL in both directions, so CI can force the
                    //     socket off for a project that enables it in `debug { }`.
                    enabled = project.booleanPropertyOrNull(IdeaRunConfigs.DEBUG_PROPERTY)
                        ?: debug.enableDebug.get(),
                    port = debug.debugPort.get(),
                    suspend = project.booleanPropertyOrNull(IdeaRunConfigs.DEBUG_SUSPEND_PROPERTY)
                        ?: debug.debugSuspend.get(),
                ),
                headless = headless,
            )
        }

        // Root project only: it owns `runMindustry`, and the generated files always land in the
        // root's `.run/`, so a subproject must not overwrite them with its own defaults.
        if (project == project.rootProject) {
            // IDEA's .run/*.xml describes a desktop JVM: on Android the editor is Neovim/jdtls and the
            // debugger attaches to a headless server, so generating them would only be noise.
            if (run.hostPlatform.get() != HostPlatform.Android) {
                project.tasks.register("generateIdeaRunConfigs") { task ->
                    // The generated configuration runs the packaging task before `runMindustry`, because the
                    //     task itself no longer depends on it and a one-click IDE run should build first.
                    GenerateIdeaRunConfigsTask.configure(
                        task, project, debug.debugPort,
                        packagingTask = if (run.useDeployRun.get()) "deploy" else "jar",
                    )
                }
            }
        }
    }

    // =========================================================================
    //  Mod projects: compile + package
    // =========================================================================

    private fun configureModule(project: Project, ext: MindustryModExtension, hasModFile: Boolean) {
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
            task.group = MINDUSTRY_GROUP
            task.description = "Writes mod.json / mod.hjson from the modMeta { } block."
            BuildModHJsonTask.configure(task, ext, generateMeta, useHJson, project, modFileName)
        }

        project.tasks.named("jar", Jar::class.java) { task ->
            JarTask.configure(task, names.jar, ext, hasModFile, project, generateMeta, modFileName)
        }

        project.tasks.register("jarAndroid") { task ->
            task.group = MINDUSTRY_GROUP
            task.description = "Builds the Android DEX jar with d8."
            JarAndroidTask.configure(task, libsDir, names.jar, names.android, project, jarAndroidOptions(project, rootExt))
        }

        // A project may already deploy somewhere (a server, a container). Registering our own task under the
        // same name would fail the whole build, so the user's task wins and we say what is not happening.
        if (project.tasks.findByName("deploy") != null) {
            project.logger.warn(
                "Project '${project.path}' already has a 'deploy' task, so the plugin did not add its own " +
                "merge task. runMindustry will not deploy this project's mod until that task is renamed."
            )
            return@configureModule
        }
        project.tasks.register("deploy", Jar::class.java) { task ->
            task.group = MINDUSTRY_GROUP
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
    private fun requireKotlinPluginIfNeeded(project: Project, excluded: List<String>): KotlinProjectExtension? {
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
    private fun configureSourceDirs(project: Project, kotlin: KotlinProjectExtension?, excluded: List<String>) {
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
    private fun jarAndroidOptions(project: Project, rootExt: MindustryModRootExtension?): JarAndroidTask.Options {
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
                ?: File(project.gradle.gradleUserHomeDir, "$PLUGIN_DIR_NAME/android-sdk"),
        )
    }

    // =========================================================================
    //  Helpers
    // =========================================================================

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
    private fun warnAboutRootOnlySettings(project: Project) {
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
    private fun ownProjectDataDir(project: Project): File? =
        project.extensions.findByType(MindustryModRootExtension::class.java)?.run?.gameDataDir?.orNull?.asFile

    /**
     * Names the two Gradle defaults that do not fit Android, without changing them.
     *
     * Android cannot watch the file system, and Android 12+ kills background processes when memory is
     * tight, which together make `-t`/`--continuous` unusable and make daemons vanish for no visible
     * reason. Editing the user's build settings silently would be worse than one line of advice.
     */
    private fun warnAboutAndroidBuildDefaults(project: Project, run: MindustryRunConfig) {
        if (project != project.rootProject) return
        if (run.hostPlatform.get() != HostPlatform.Android) return
        val missing = buildList {
            if (!project.providers.gradleProperty("org.gradle.vfs.watch").isPresent) {
                add("org.gradle.vfs.watch=false (file watching is unavailable)")
            }
            if (!project.providers.gradleProperty("org.gradle.daemon").isPresent) {
                add("org.gradle.daemon=false (daemons get killed)")
            }
        }
        if (missing.isEmpty()) return
        project.logger.lifecycle(
            "Android / Termux build detected. Consider adding to gradle.properties: " +
                missing.joinToString(", ") +
                ". See the Android / Termux section of the README for the rest (d8 from Termux, the game " +
                "data directory, and why -t does not work)."
        )
    }

    /**
     * The headless server is the runtime the mod is loaded into, so its version has to match the API the mod
     * was compiled against. Mismatches surface as `NoClassDefFoundError` deep inside the game otherwise.
     */
    private fun warnAboutHeadlessVersionMismatch(
        project: Project,
        download: MindustryDownloadConfig,
        ext: MindustryModRootExtension,
    ) {
        val api = ext.mindustryApiVersion.orNull?.trim()?.removePrefix("v") ?: return
        val jar = download.mindustryDownloadVersion.get().trim().removePrefix("v")
        if (api == jar || api == "be" || jar == "latest") return
        project.logger.warn(
            "The headless server downloads Mindustry \"$jar\" while mindustryApiVersion is \"$api\". " +
            "The mod is compiled against $api and run inside $jar, which usually fails with " +
            "NoClassDefFoundError. Set download.mindustryDownloadVersion to the same release."
        )
    }

    /**
     * Warns when the configured game version cannot read `-Dmindustry.data.dir`.
     *
     * A warning rather than a failure: the game falls back to `MINDUSTRY_DATA_DIR` or its own per-OS
     * directory, so the run still works — it just ignores the directory this build chose. Evaluated
     * while `runMindustry` is configured, which is before the download starts.
     */

    private fun warnIfDataDirUnsupported(project: Project, version: String) {
        // On Android the property cannot work at all, whatever the game version: AndroidLauncher overwrites
        // the data directory with getExternalFilesDir(null), and nothing can pass JVM arguments to the APK.
        if ((project.extensions.findByType(MindustryModRootExtension::class.java))?.run?.hostPlatform?.get() ==
            HostPlatform.Android
        ) {
            project.logger.warn(
                "Running on Android: neither MINDUSTRY_DATA_DIR nor -Dmindustry.data.dir reaches the game, " +
                "because its launcher sets the data directory to /storage/emulated/0/Android/data/<appId>/" +
                "files itself. Import the built jar in the game (Mods -> Import mod) instead of expecting " +
                "it in a mods folder this build writes to."
            )
            return
        }
        if (MindustryApi.supportsDataDir(version)) return
        project.logger.warn(
            "Mindustry v${version.trim().removePrefix("v")} cannot read -Dmindustry.data.dir (added in " +
            "v147), so this run uses MINDUSTRY_DATA_DIR or the operating system's Mindustry directory " +
            "instead of the configured gameDataDir. Set download.mindustryDownloadVersion to v147 or " +
            "later (or \"latest\") to use it."
        )
    }

    /**
     * Reads a boolean project property (`-Pname=value`, `gradle.properties`, `-D`).
     *
     * [Project.hasProperty] is not enough: it only reports that the property exists, so
     * `-PmindustryDebug=false` still opened the debug socket, and `-PmindustryDebugSuspend=false`
     * made the game wait for a debugger that never attaches — the game looks hung. A property given
     * without a value counts as enabled, which is what a command-line flag means.
     */
    private fun Project.booleanProperty(name: String): Boolean {
        val text = findProperty(name)?.toString()?.trim() ?: return false
        return text.isEmpty() || text.toBoolean()
    }

    /**
     * Like [booleanProperty], but null when the property was not given at all.
     *
     * Used where a command-line switch must be able to override a DSL default in both directions:
     * `-PmindustryDebug=false` has to win over `debug { enableDebug = true }`, which a plain
     * `dsl || property` cannot express.
     */
    private fun Project.booleanPropertyOrNull(name: String): Boolean? {
        val text = findProperty(name)?.toString()?.trim() ?: return null
        return text.isEmpty() || text.toBoolean()
    }

    /**
     * Substitutes `{version}` into the download file name template and validates the result.
     *
     * A space or one of `\ / : * ? " < > |` throws; CJK characters only warn. The `.jar` suffix is
     * not part of the name — the caller appends it.
     */
    private fun resolveDownloadFileName(download: MindustryDownloadConfig, logger: Logger): String {
        val name = download.mindustryDownloadFileName.get()
            .replace("{version}", download.mindustryDownloadVersion.get())

        val invalid = name.toCharArray().filter { it in INVALID_FILE_NAME_CHARS }
        if (invalid.isNotEmpty()) {
            throw GradleException(
                "Invalid character(s) in download.mindustryDownloadFileName: " +
                invalid.toSet().joinToString("") { if (it == ' ') "' '" else "'$it'" } +
                "\nFile name: \"$name\"\n" +
                "Avoid spaces and the following characters: \\ / : * ? \" < > |"
            )
        }

        if (CJK_CHARS.containsMatchIn(name)) {
            logger.warn("Download file name \"$name\" contains CJK characters. This may cause issues on some operating systems.")
        }
        return name
    }

    companion object {
        /** Task group every task this plugin registers belongs to. */
        private const val MINDUSTRY_GROUP = "mindustry"

        /** Directory under the Gradle user home that holds the auto-installed Android SDK. */
        private const val PLUGIN_DIR_NAME = "mindustry-mod-development-plugin"

        private val INVALID_FILE_NAME_CHARS = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|', ' ')

        private val CJK_CHARS =
            Regex("[\u4e00-\u9fff\u3400-\u4dbf\u2e80-\u2eff\u2f00-\u2fdf\u3000-\u303f]")
    }
}
