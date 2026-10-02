# mindustry-mod-development-plugin

Gradle plugin (`io.github.box3-galen-nv.mindustry-mod-development-plugin`) that builds Mindustry mods in Java or Kotlin. The
user-facing documentation is `README.md` (English, the default) with `README_zh.md` as its Chinese
twin — every behaviour or default change goes into **both**, and they link to each other at the top.
This file is the short list of things to know to change the code without breaking it.

## Build & test

```sh
GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew test              # the sandbox blocks the default Gradle home
GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew test --tests "*ModMeta*" --console=plain
```

Test classes mirror the source package; `ProjectBuilder` for wiring tests, Gradle TestKit for real
builds. Update `README.md` **and** `README_zh.md` when behaviour or a default changes.

The Gradle user home deliberately sits at the repository root, **never inside `build/`**: `clean`
deletes `build/`, and deleting a Gradle home that live daemons are writing to fails with an opaque OS
error and leaves a broken cache behind. `.gradle-home/` is gitignored.

Kotlin's daemon cannot write its temp files in this sandbox, so builds fall back to in-process
compilation and print `e:` daemon lines; ignore those and read the compiler diagnostics that follow.

## Layout

```
MindustryModPlugin.kt   entry point: apply() and the afterEvaluate hooks — the only file left in the
                        root package
game/                   MindustryApi (the game API dependency), GameDataDir (its data directory)
platform/               HostPlatform (Auto/Desktop/Android + Termux detection), TargetPlatform
                        (Jar/Android/All, the suffix a modVersion() is named for)
meta/                   ModMeta (19 engine fields), ModFileReader (the engine's 4 metadata files),
                        ArtifactNaming (artifact names from build.format / modMeta)
dsl/                    the public DSL and *only* the DSL: the two extensions
                        (MindustryModRootExtension, MindustryModExtension) and the four nested
                        configs (download, run, debug, build). Nothing else belongs here.
tasks/                  the task objects and nothing else: one <Name>Task per file, each with a
                        configure(...) that does the wiring
wiring/                 ModWiring (one mod project's sources and tasks), RootWiring (the root's tasks,
                        conventions and warnings), GradleProperties (reading -P/gradle.properties
                        booleans by value)
sdk/                    AndroidSdk (discovery and d8 command assembly), AndroidSdkInstaller (downloading
                        and installing the SDK)
idea/                   IdeaRunConfigs (the .run/*.xml generator)
logging/               RunLogging (the log tee and the cleanup BuildService)
```

## Conventions

- **Comments**: English only, KDoc for every declaration. `//` only inside bodies and for section
  banners. Sentences start with a capital letter — tool names keep their casing, so reword instead of
  starting a sentence with `sdkmanager` or `mindustryMod`.
- **Never write the comment terminator inside a KDoc** — that is an asterisk followed by a slash. Write
  globs as prose. It silently ends the comment and broke a build once.
- An **unterminated KDoc** (typically a leftover opening marker above a freshly written block) makes the
  compiler report impossible errors, such as unresolved references to a regex match type. The real
  diagnostic is a `Syntax error / Unclosed comment` at the *end* of the log — read the whole log.
- **Packages say what a type is for**: `dsl/` is extensions and configs only, `tasks/` is the task objects
  and nothing else, `wiring/` registers and configures them, `game/` is knowledge about the game,
  `platform/` is where things run, `meta/` is the engine's metadata and the mod's own naming, `sdk/` is
  Android SDK tooling, `idea/` is the `.run/` generator, `logging/` is the log tee. The root package holds
  the plugin entry point and nothing else.
- **Defaults are named constants referenced from `init { }`**, never inline literals, so a KDoc can link
  to the constant and generated text can reuse it. This rule is the one place that is written down: do not
  repeat it as a comment inside every config class.
- **One top-level declaration per file.** Detection or factory logic belongs in that type's `companion
  object` (`HostPlatform.detect`), never in a second top-level object, so no file declares two of them.
- One object per task file, named after the file, with `configure(...)` doing the wiring; option holders
  are nested in it (`JarAndroidTask.Options`).
- **Delete a declaration's doc block with the declaration.** An orphaned KDoc is valid Kotlin and stays
  silent: it simply ends up documenting whatever declaration follows it.
- Tests use JUnit 5 `Assertions`, never bare `assert` (which depends on `-ea`). The TestKit fixtures
  declare no repositories on purpose — the plugin has to make the API resolvable by itself, and one
  fixture that needs nothing else is the proof. `SourceHygieneTest` fails on a KDoc that documents
  nothing, i.e. two doc comments in a row.

## Rules that are easy to break

- **The Kotlin plugin is the user's job.** Find it with `findByType`, configure its source set only when
  it is there, and fail naming the first `.kt` file when it is missing. Never apply Kotlin from
  `afterEvaluate` — Kotlin rejects the lifecycle. `java` *is* applied for the user, before the first
  `compileOnly` use.
- **Source roots**: add the project dir to `main`'s java and kotlin source dirs (`srcDir`, never
  `setSrcDirs`), excluding `build/`, `.gradle/`, `**/*.kts` (the Kotlin source filter matches scripts),
  any Gradle user home inside the project, and `data/`.
- **The configuration cache is supported, and it constrains the code**: a task action may hold only plain
  values (a `File`, a `String`, a `FileCollection`, a `BuildService` provider). The task-completion listener
  is a `BuildService` (`RunLogging.CleanupService`), never a plain provider, and no action may reach back
  into a `Project` or the extension that owns one — `ModWiring`/`RootWiring` resolve what the tasks need
  while configuring. Verify a change by running a task twice with `--configuration-cache` and requiring
  `stored` and then `reused`.
- **`runMindustry` declares no inputs/outputs on purpose**: it launches the game, so UP-TO-DATE would
  skip the launch. It depends only on `downloadMindustry` + `clearMods`; packaging is the user's call
  (`gradle deploy runMindustry`, or their own `dependsOn`). A bare task name matches *every* project,
  so `:runMindustry` targets exactly one.
- **`.run/` is written by the `generateIdeaRunConfigs` task**, never during configuration. Bump
  `IdeaRunConfigs.TEMPLATE_REVISION` whenever the generated XML changes, or existing files stay stale.
- **The mods path is always `<gameDataDir>/mods`**, created on demand. Unset, the data directory is
  `MINDUSTRY_DATA_DIR` or the per-OS default and the plugin passes no JVM property — only a directory the
  build *sets* is passed as `-Dmindustry.data.dir`, since the game already honours the other two. An older
  game version only warns. There is no project-local shorthand: `gameDataDir` is the single knob, and a
  multi-project build sets it per project.
- **Property semantics**: read booleans by value, so `-Px=false` really means off. Where a per-run switch
  must beat a DSL default, use `booleanPropertyOrNull(...) ?: dslValue`.
- **The Kotlin Gradle Plugin is a runtime dependency, pinned to 2.4.20** — the first release that fixes
  CVE-2026-53614. Shipping it is what lets the plugin point the Kotlin source set at the project dir (the
  reason IDEA resolves breakpoints), and it pulls a consumer who declared an older KGP up onto the patched
  version. Do not downgrade it, and do not drop it for `compileOnly`: the typed extension lookup then fails
  on a consumer whose KGP lives in another classloader.
- **No test may reach the network or launch the real game.** A fixture that executes `runMindustry` must put
  a junk jar at the *resolved* path and pin an unreachable `mindustryDownloadUrl` (`file:///nonexistent/...`),
  so a mismatched path fails locally instead of downloading the game and starting it. That happened once: a
  default version change stopped matching a fixture's file name, the task fetched the real 73 MB game, the
  run launched it, and the suite stayed green because it only asserts task outcomes. Two tests now also
  assert that their output never mentions the release URL.
- **Verify a jar by resolving it, never by a hard-coded name.** The default `build.format` carries
  `{build_count}`, so each build writes a new file name; `unzip`-ing a remembered name inspects a stale
  artifact and invents bugs. That happened here: two rounds were spent on a "missing metadata" bug that was
  a leftover `*-1.0.0-*.jar` while the real output was `*-1.0.14-*.jar`, which had the metadata all along.
  Pin the format in a test, or pick the newest file from the output directory.
- **Two deliberate tradeoffs, both documented in the README**: `jarAndroid` fingerprints the SDK by path
  only (hashing a whole SDK costs more than re-running d8), and `build/buildCounter.txt` is read while the
  artifact name is resolved, so it is not a declared task input — the name changes anyway.
- **d8 is looked up without an Android SDK, in this order**: `build.d8Executable`, then `d8` on the `PATH`
  (but only when the project did *not* configure `build.androidSdkDir` — an explicit SDK directory is an
  instruction and gets installed if empty), then any installed SDK, then an install. A lone `lib/d8.jar` is
  run as `java -cp <jar> com.android.tools.r8.D8`, because build-tools' `d8` is a shell script and Android
  has no `/bin/sh`. `android.jar` is optional: d8 runs without it with a warning. Never let a resolved d8
  trigger an install.
- **Android is recognised, not assumed**: `HostPlatform.detect` treats Linux plus (`TERMUX_VERSION` or a
  `PREFIX` inside `com.termux`) as Android. Architecture is deliberately not part of it. `GameDataDir` has an
  Android branch — `/storage/emulated/0/Android/data/<appId>/files`, what `AndroidLauncher` sets — because
  `os.name` says "Linux" there and the desktop path is wrong. On the APK both `MINDUSTRY_DATA_DIR` and
  `-Dmindustry.data.dir` are dead, so the warning must say that instead of suggesting them. Android gets no
  `.run/*.xml` and exactly one lifecycle hint, never an edit to the user's build settings.
- **On Android `runMindustry` stages instead of launching**: it copies the artifacts into
  `run.androidStagingDir` for the game's import dialog, with best-effort `am force-stop`/`am start`, and
  fails loudly when a debugger is requested (no JWDP on the Android runtime) or when `useDeployRun` is off
  (the APK only loads classes.dex). `run.useHeadlessServer` is the path that actually runs there.
- **The `runMindustry` registration happens at apply time, and the Android behaviour is attached in
  `afterEvaluate`**: `run { }` is evaluated after `apply()`, so deciding the task type there would always
  read the default — that bug registered a JavaExec on Android, downloaded the desktop jar and launched it.
  Registering early is also what keeps `tasks.named("runMindustry")` usable from a build script.
- **d8**: drain its merged output *while* it runs (a full pipe buffer deadlocks `waitFor`), include the
  captured output in failure messages, and delete a half-written output jar.
- **SDK installs never modify an SDK they did not create**, and the post-install check only reports
  packages it can verify on disk (`platforms;*`, `build-tools;*`); `jarAndroid` fingerprints the SDK by
  path, because hashing a whole SDK costs more than re-running d8. `download.androidSdkExtraArgs` is
  appended to both sdkmanager runs, so it must reject the flags the plugin sets itself (`--sdk_root` and
  the action flags) *before* downloading the tools.
- **Downloads**: `downloadMindustry` is the plugin's own downloader — no third-party download plugin is
  applied or depended on. It streams to `<name>.part`, checks the zip signature, then moves the file into
  place, and records the URL in a `<name>.url` stamp. That stamp is what separates a jar this build
  downloaded (a version change replaces it, `--offline` and up-to-date checks never touch the network)
  from one the user put there (never replaced, only reported when it cannot be a jar). `clearMods`
  matches the deploy tag plus a regex derived from `build.format`, and deletes nothing when the mod name
  cannot be resolved.
- **The mod-project scan** (`(listOf(rootProject) + rootProject.subprojects).filter { it.tasks.findByName("deploy") is Jar }`)
  must run at task-realization time, not in `afterEvaluate`, or the root `runMindustry` silently deploys
  nothing. The root project is included because single-project mode makes it the mod, and the `is Jar` test
  keeps a project's own unrelated `deploy` task out of it. Registering our `deploy` is skipped with a
  warning when that name is already taken.
- **Root-only settings on a subproject are reported, not silently ignored**: `mindustryApiVersion` and the
  whole `build { }` block come from the root project (they name the artifacts and pick the API for the whole
  build), while `run { }` is per project; `warnAboutRootOnlySettings` names the offending properties.
- **`modMeta` is its own extension**, created before `afterEvaluate` because `isRootWithoutModConfig`, the
  guard that keeps a multi-project root from configuring itself twice, reads `modMeta.name`. A root
  project may also be a mod (single-project mode), which is what makes that guard necessary.

## Verified facts — do not re-investigate

- Termux ships the **unmodified** Gradle 9.8.0 zip: `packages/gradle/build.sh` has the same
  `TERMUX_PKG_SHA256` as our wrapper pin and no patches, so 9.8 on aarch64 Android is distribution-verified.
  Termux's main repo also has `d8 37.0.0`, `kotlin 2.4.20`, `openjdk-17/21` — and no `jdtls`.
- `server-release.jar` (v147+) is self-contained with `linux/aarch64` natives, so the headless path has no
  native gap on Termux; `dependencies.jar` only exists from v159.7; the desktop `Mindustry.jar` has no
  Linux aarch64 SDL backend at all, which is why the GUI cannot run there.
- Game API: one content-filtered Ivy repository over the GitHub release assets. `be` →
  `MindustryBuilds` `master/latest.jar`; `latest` / `>= 155.4` → `dependencies.jar`;
  `97 … 155.3` → `Mindustry.jar` (also bundles Arc). **v97 is the floor** (first release with a mod
  system); releases before v90 ship only a launcher wrapper.
- The `MindustryRepo` mirror the plugin still contributes is not self-sufficient: its `core` POMs want
  `org.lz4:lz4-java`, which it does not host, and its v156.1 module metadata contains Git conflict
  markers that Gradle rejects outright. `Anuken/MindustryMaven` froze at v145–v146 and
  `Anuken/MindustryRepo` is not public, so there is no better official alternative.
- `-Dmindustry.data.dir` exists from **v147**, `MINDUSTRY_DATA_DIR` from **v126**, and the property wins.
  A headless server ignores both.
- A mod built by this plugin loads in the real headless server: `server-release.jar` (checked with v146 and
  v159.7) reports `1 mods loaded.` for a fixture whose metadata names its `main` class. There is **no**
  `Loading mod:` line in those versions — the string comes from elsewhere, so grepping for it wastes time.
- `Mods.load()` lists `<dataDir>/mods` flat and keeps `*.jar`, `*.zip` and folders containing a
  metadata file — subdirectories are not scanned. `mod.hjson` starts at v101, the `mindustry.mod.*`
  package at v102.
- Gradle executes command-line tasks in the given order, so `gradle deploy runMindustry` builds first
  without any dependency.
- `sdkmanager` caches its manifest under `$ANDROID_USER_HOME/cache` and reports a read-only path as a
  *download* failure; the installer redirects it to `<sdk>/.android-user`.
- `sdkmanager` has **no option for another repository URL** — it always reads Google's own package list,
  so a mirror can only be used as an HTTP proxy (`--proxy=http --proxy_host=... --proxy_port=...`).
- SDK discovery skips a configured `androidSdkDir` that does not exist and keeps looking, so a stale path
  cannot hide a working `ANDROID_HOME`.
- d8 fails with `Unsupported class file major version NN` when the mod targets a newer JVM than the
  selected `build-tools` understands; the failure hint says which knob to turn.
- The build counter is advanced under a file lock; the artifact name is resolved at configuration time,
  so `{time}` is the unique alternative to `{build_count}`.
- `settings.gradle.kts` must not resolve the Kotlin plugin through `pluginManagement` — it collides with
  TestKit's `withPluginClasspath()`. Tests use `buildscript { }` + `apply`.
- The plugin id (`io.github.box3-galen-nv.mindustry-mod-development-plugin`) and group
  (`io.github.box3-galen-nv`) come from `gradle.properties` and `build.gradle.kts`; the package root is
  `mindustrymoddevelopmentplugin`. CI is `.github/workflows/ci.yml`: a JDK 17/21/25 test matrix plus
  `validatePlugins` on ubuntu, and non-blocking macOS/Windows sanity jobs. No hooks, no codegen.
