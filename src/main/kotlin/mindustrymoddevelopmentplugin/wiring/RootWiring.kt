package mindustrymoddevelopmentplugin.wiring

import java.io.File
import mindustrymoddevelopmentplugin.dsl.MindustryDownloadConfig
import mindustrymoddevelopmentplugin.dsl.MindustryModRootExtension
import mindustrymoddevelopmentplugin.dsl.MindustryRunConfig
import mindustrymoddevelopmentplugin.game.GameDataDir
import mindustrymoddevelopmentplugin.game.MindustryApi
import mindustrymoddevelopmentplugin.platform.HostPlatform
import mindustrymoddevelopmentplugin.tasks.ClearModsTask
import mindustrymoddevelopmentplugin.tasks.DownloadAndroidSdkTask
import mindustrymoddevelopmentplugin.tasks.DownloadMindustryTask
import mindustrymoddevelopmentplugin.tasks.GenerateIdeaRunConfigsTask
import mindustrymoddevelopmentplugin.idea.IdeaRunConfigs
import mindustrymoddevelopmentplugin.logging.RunLogging
import mindustrymoddevelopmentplugin.tasks.RunMindustryTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.bundling.Jar
import org.gradle.build.event.BuildEventsListenerRegistry
import mindustrymoddevelopmentplugin.MindustryModPlugin

/**
 * Everything the root project owns: the download, clear and run tasks, the conventions the DSL
 * relies on, and the warnings about the data directory, Android builds and a headless mismatch.
 */
internal object RootWiring {

    /** Registers `mindustryModRoot { }` and the tasks that do not depend on the project being a mod. */
    internal fun configureRoot(project: Project, buildEvents: BuildEventsListenerRegistry) {
        val ext = project.extensions.create("mindustryModRoot", MindustryModRootExtension::class.java)
        val logger = project.logger
        val download = ext.download
        val run = ext.run
        val debug = ext.debug

        // The KDoc promises a default, and a convention is where Gradle expects one: the value depends on
        // the Gradle user home, which the DSL object itself cannot see.
        run.androidSdkInstallDir.convention(
            project.layout.dir(
                project.provider { File(project.gradle.gradleUserHomeDir, "$MindustryModPlugin.PLUGIN_DIR_NAME/android-sdk") },
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
        /*
        The staged jar has to be somewhere the *game's* file picker can reach, because importing it is a
        manual step inside the game: the picker only shows shared storage, so a Termux-private directory
        under the home directory is the fallback rather than the default. See AndroidStaging.
        */
        run.androidStagingDir.convention(
            project.layout.dir(
                project.provider { AndroidStaging.defaultDir(File(System.getProperty("user.home"))) },
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
        val resolvedFileName = project.providers.provider { DownloadMindustryTask.resolveDownloadFileName(download, logger) }

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
        /*
        Root project only: the SDK lives in the Gradle user home, or wherever build { } points, so one
        install serves every mod project. jarAndroid depends on this instead of installing the SDK itself,
        which is what keeps an inspection-only build from starting a download.
        */
        if (project == project.rootProject) {
            project.tasks.register("downloadAndroidSdk") { task ->
                task.group = MindustryModPlugin.MINDUSTRY_GROUP
                task.description = "Downloads and installs the Android SDK that jarAndroid dexes with."
                DownloadAndroidSdkTask.configure(
                    task,
                    ModWiring.androidOptions(
                        project,
                        project.extensions.findByType(MindustryModRootExtension::class.java),
                    ),
                )
            }
        }


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

        /*
        Resolved at task realization time, when every project has been configured — otherwise the
        `deploy` tasks of subprojects do not exist yet and the scan silently finds nothing.
        
        The root project is included on purpose: in single-project mode the root project *is* the mod,
        and `subprojects` does not contain it, so a run used to deploy nothing without saying so. A
        root that is not a mod simply has no `deploy` task and is filtered out here.
        */
        val modProjects = project.providers.provider {
            (listOf(project.rootProject) + project.rootProject.subprojects)
                /*
                `is Jar` and not just "a task called deploy": a subproject may deploy to a server or a
                container with its own `deploy`, and that one used to be picked up here — clearMods then
                crashed on it and runMindustry complained that it is not a Jar task.
                */
                .filter { it.tasks.findByName("deploy") is Jar }
        }

        // The directory the game would use by itself: MINDUSTRY_DATA_DIR, else the per-OS default.
        val implicitDataDir = project.providers.provider {
            GameDataDir.resolve(
                hostPlatform = run.hostPlatform.get(),
                androidAppId = run.androidAppId.get(),
            ).absoluteFile
        }

        /*
        Only a directory *this build* picks needs the JVM property — the game already honors the
        environment variable and its own per-OS default, so passing the property for those would be
        redundant (and would earn a warning on a version that cannot read it). A project-local
        directory is simply one of the values the build script can set.
        */
        val chosenDataDir = project.providers.provider { run.gameDataDir.orNull?.asFile?.absoluteFile }

        // The game only looks for mods under <dataDir>/mods, so that path follows the data directory;
        // it does not have to exist yet.
        val modsDir = project.providers.provider { File(chosenDataDir.orNull ?: implicitDataDir.get(), "mods") }

        project.tasks.register("clearMods") { task ->
            task.group = MindustryModPlugin.MINDUSTRY_GROUP
            task.description = "Removes the mod jars this plugin deployed earlier from the mods folder."
            ClearModsTask.configure(
                    task,
                    ClearModsTask.cleanupsFor(modProjects.get(), run.deployTag.get(), project.logger),
                    run.cleanDeployedFiles.get(),
                    modsDir.get(),
                )
        }

        project.tasks.register("runMindustry", JavaExec::class.java) { task ->
            task.group = MindustryModPlugin.MINDUSTRY_GROUP
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
                task, modArtifacts(modProjects.get(), if (run.useDeployRun.get()) "deploy" else "jar"),
                downloadPath.get(), resolvedModsDir, project,
                run.deployTag.get(),
                debug.maxLogFiles.get(), debug.enableRunLogging.get(),
                dataDir = resolvedDataDir,
                // Registered on demand and shared per build, so no plumbing is needed.
                logCleanup = RunLogging.register(project, buildEvents),
                debug = RunMindustryTask.DebugOptions(
                    /*
                    The generated IDEA configuration passes -PmindustryDebug=true. A property given on
                    the command line wins over the DSL in both directions, so CI can force the
                    socket off for a project that enables it in `debug { }`.
                    */
                    enabled = GradleProperties.booleanOrNull(project, IdeaRunConfigs.DEBUG_PROPERTY)
                        ?: debug.enableDebug.get(),
                    port = debug.debugPort.get(),
                    suspend = GradleProperties.booleanOrNull(project, IdeaRunConfigs.DEBUG_SUSPEND_PROPERTY)
                        ?: debug.debugSuspend.get(),
                ),
                headless = headless,
            )
        }

        /*
        Android has no JVM to launch and no debugger to attach to: the APK's launcher takes no JVM
        arguments, so there is no way to add a debug agent to it, and ART is not a JVM a JDWP client can
        attach to. The task above is registered at apply time so a build script can still write
        tasks.named("runMindustry") { dependsOn("deploy") }; the staging behavior is attached here, because
        run { } is only evaluated after apply().
        */
        project.afterEvaluate {
            if (run.hostPlatform.get() == HostPlatform.Android && !run.useHeadlessServer.get()) {
                val debugRequested = GradleProperties.booleanOrNull(project, IdeaRunConfigs.DEBUG_PROPERTY)
                    ?: debug.enableDebug.get()
                if (debugRequested) {
                    throw GradleException(
                        "Debugging is not possible on Android: the game runs in the Android runtime, which has " +
                        "no JDWP socket to attach to. Set run.useHeadlessServer = true and attach to that JVM, " +
                        "or debug on a desktop."
                    )
                }
                if (!run.useDeployRun.get()) {
                    throw GradleException(
                        "run.useDeployRun = false cannot work on Android: the APK only loads classes.dex, which " +
                        "only the merged 'deploy' artifact contains. Turn it back on, or use the headless server."
                    )
                }
                warnAboutAndroidBuildDefaults(project, run)
                val task = project.tasks.named("runMindustry").get()
                task.actions.clear()
                task.setDependsOn(emptyList<String>())
                RunMindustryTask.configureAndroid(
                    task, modArtifacts(modProjects.get(), "deploy"),
                    deployTag = run.deployTag.get(),
                    options = RunMindustryTask.AndroidOptions(
                        appId = run.androidAppId.get(),
                        stagingDir = run.androidStagingDir.get().asFile,
                        launchApk = run.androidLaunchApk.get(),
                        // Resolved here: the action holds plain values and cannot ask the
                        // file system itself.
                        privateStagingFallback = AndroidStaging.isPrivateFallback(
                            run.androidStagingDir.get().asFile,
                            File(System.getProperty("user.home")),
                        ),
                    ),
                )
            }
        }

        // Root project only: it owns `runMindustry`, and the generated files always land in the
        // root's `.run/`, so a subproject must not overwrite them with its own defaults.
        if (project == project.rootProject) {
            // IDEA's .run/*.xml describes a desktop JVM: on Android the editor is Neovim/jdtls and the
            // debugger attaches to a headless server, so generating them would only be noise.
            if (run.hostPlatform.get() != HostPlatform.Android) {
                project.tasks.register("generateIdeaRunConfigs") { task ->
                    // The generated configuration runs the packaging task before `runMindustry`, because the
                    // task itself no longer depends on it and a one-click IDE run should build first.
                    GenerateIdeaRunConfigsTask.configure(
                        task, project, debug.debugPort,
                        packagingTask = if (run.useDeployRun.get()) "deploy" else "jar",
                    )
                }
            }
        }
    }

    /**
     * Names the two Gradle defaults that do not fit Android, without changing them.
     *
     * Android cannot watch the file system, and Android 12+ kills background processes when memory is
     * tight, which together make `-t`/`--continuous` unusable and make daemons vanish for no visible
     * reason. Editing the user's build settings silently would be worse than one line of advice.
     */
    internal fun warnAboutAndroidBuildDefaults(project: Project, run: MindustryRunConfig) {
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
    internal fun warnAboutHeadlessVersionMismatch(
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

    internal fun warnIfDataDirUnsupported(project: Project, version: String) {
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
     * Resolves each mod project's artifact while configuring.
     *
     * The task actions copy these jars, so they must not hold the projects or the packaging tasks — the
     * configuration cache cannot serialize those.
     */
    private fun modArtifacts(projects: List<Project>, deployTaskName: String): List<RunMindustryTask.ModArtifact> =
        projects.map { sub ->
            val jarTask = sub.tasks.named(deployTaskName, Jar::class.java).get()
            RunMindustryTask.ModArtifact(
                name = sub.name,
                jar = jarTask.archiveFile.get().asFile,
                // The task's own path: the root project's path is ":", which would print "::deploy".
                packagingCommand = jarTask.path,
            )
        }
}
