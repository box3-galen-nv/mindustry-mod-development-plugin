# mindustry-mod-development-plugin

**English** · [中文文档](README_zh.md)

Gradle plugin for building [Mindustry](https://github.com/Anuken/Mindustry) mods with Kotlin/Java.
Handles mod metadata generation, jar packaging, Android DEX compilation, and game launching.

---

## Usage

### Apply the plugin

The plugin is published on the [Gradle Plugin Portal](https://plugins.gradle.org/), which Gradle already
searches, so no repository has to be declared for it. The configuration cache is supported: a build reuses its stored entry, so `--configuration-cache` (or `org.gradle.configuration-cache=true`) is fine to turn on.

```kotlin
// build.gradle.kts (root)
plugins {
    id("io.github.box3-galen-nv.mindustry-mod-development-plugin") version "1.0.1" apply false
}

repositories {
    // Only needed for Kotlin mods (kotlin-stdlib); the game API comes from the plugin.
    mavenCentral()
}

subprojects {
    apply(plugin = "io.github.box3-galen-nv.mindustry-mod-development-plugin")
}

mindustryModRoot {
    mindustryApiVersion = "159"
}
```

**The game API needs no repository from you.** The plugin resolves `arc-core` + `core` from the
**GitHub release assets** (the same mechanism as the official
[MindustryJavaModTemplate](https://github.com/Anuken/MindustryJavaModTemplate)), through one
content-filtered Ivy repository that declares artifact-only metadata — no POM, therefore no transitive
dependencies and no mirror to trust:

| `mindustryApiVersion` | release asset | size |
|-----------------------|---------------|------|
| `be` | `MindustryBuilds` `master/latest.jar` | ~15 MB |
| `latest`, `>= 155.4` | `dependencies.jar` | 13–15 MB |
| `97 … 155.3` | `Mindustry.jar` (the runnable jar, which also bundles Arc) | 73–89 MB |

**v97 is the floor**: that is the first release with a mod system (`mod.json` / `plugin.json`).
v92 had only server plugins, `mod.hjson` starts at v101, and the `mindustry.mod.*` package that mods
import today only exists from v102 — everything below v97 fails with an error instead of a confusing
resolution failure.

The only repository a project may still need is `mavenCentral()`, and only for **Kotlin** mods
(`kotlin-stdlib`). A Java-only mod builds with an empty `repositories { }`.

> Why not Maven coordinates? JitPack's `com.github.Anuken.Mindustry:core:v159` POM asks for
> `com.github.Anuken.Arc:arc-core:6aee8e7686`, a commit hash no repository serves (and Gradle prefers it
> over the direct `v159`); the third-party Zelaux/MindustryRepo mirror is not self-sufficient either (and its newest release, v156.1, ships module metadata with leftover merge-conflict markers, which Gradle rejects) — its `core:v146` POM
> needs `org.lz4:lz4-java`. The asset route has neither problem.

### Single-project mode

Root project is itself a mod — no `src/<name>/` submodule needed.

```kotlin
// build.gradle.kts — drop the kotlin("jvm") line for a Java-only mod
plugins {
    kotlin("jvm") version "2.4.20"
    id("io.github.box3-galen-nv.mindustry-mod-development-plugin") version "1.0.1"
}

mindustryModRoot {
    mindustryApiVersion = "159"
}

// modMeta { } is its own top-level block
modMeta {
    name = "my-mod"
    author = "You"
    version = "1.0.0"
    java = true
}

mindustryMod {
    generateModMeta = true
}
```

**Where sources live**: the project directory *is* the source root — put `.kt` / `.java`
files next to `build.gradle.kts` (single-project mode) or in the subproject root (multi-project
mode, `src/<mod>/`). `src/main/kotlin` keeps working too. `build/` and `.gradle/` are excluded, and
Kotlin scripts (`*.kts`) are never compiled as mod sources. `.java` files are compiled by the Java
plugin that `kotlin("jvm")` brings along, so a project needs either Kotlin applied or a Java
plugin — Kotlin already applies Java for you.

### Multi-project mode

Each mod lives in `src/<name>/` as a subproject.

```
project/
├── build.gradle.kts          # root — downloadMindustry + runMindustry
├── settings.gradle.kts       # include(":src/modA", ":src/modB")
└── src/
    └── modA/
        └── build.gradle.kts  # mod configuration
            modMeta { name = "modA"; version = "1.0"; java = true }
            mindustryMod { generateModMeta = true }
```

---

## Extension DSL

### `mindustryModRoot { }` — root project only

| Property | Type | Default | Description |
|----------|------|---------|----|
| `mindustryApiVersion` | `String` | *(unset)* | Mindustry version to compile against — optional, without it no API dependency is added: `"159"`, `"v159"`, `"latest"`, `"be"` (≥ v97) |
| `build.useHJson` | `Boolean` | `false` | `true` → `mod.hjson`, `false` → `mod.json` (the default) |
| `download.mindustryDownloadVersion` | `String` | `"147"` | Game release to download; `"latest"` is allowed, `"be"` is API-only and has no release asset |
| `download.mindustryDownloadUrl` | `String` | GitHub Releases | Download base URL |
| `download.mindustryGamePath` | `RegularFile` | `<root>/build/game/Mindustry-<version>.jar` | Game jar path; a path ending in `.jar` is used as a file, anything else as a directory |
| `download.mindustryDownloadFileName` | `String` | `"Mindustry-{version}"` | Download file name template |
| `download.androidSdkAutoDownload` | `Boolean` | `false` | Opt-in (consumed by `jarAndroid`): download and install the SDK when none is usable |
| `download.androidSdkDownloadUrl` | `String` | official Google URL | Command-line tools download (mirror/channel) |
| `download.androidSdkDownloadPackages` | `List<String>` | `["platforms;android-34", "build-tools;34.0.0"]` | `sdkmanager` packages to download and install |
| `download.androidSdkDownloadTimeoutMinutes` | `Long` | `30` | Download timeout for the tools and each `sdkmanager` call, in **minutes** (not a run timeout) |
| `download.androidSdkExtraArgs` | `List<String>` | `[]` | Extra `sdkmanager` flags — a mirror is an HTTP proxy for it, because `sdkmanager` has no repository-URL option |
| `run.gameDataDir` | `Directory` | `MINDUSTRY_DATA_DIR`, else the per-OS Mindustry directory | Game data directory; the mods path is always `<gameDataDir>/mods`. Setting it passes `-Dmindustry.data.dir` |
| `run.deployTag` | `String` | `"mm-deploy"` | Tag marking the jars this plugin deployed (`[mm-deploy]…`) |
| `run.cleanDeployedFiles` | `Boolean` | `true` | Delete the files this plugin deployed earlier, before deploying again |
| `run.useDeployRun` | `Boolean` | `true` | Copy the merged `deploy` jar instead of `jar` (this only picks the artifact; `runMindustry` never depends on that task) |
| `run.androidSdkInstallDir` | `Directory` | `<gradleUserHome>/mindustry-mod-development-plugin/android-sdk` | Install target when `androidSdkDir` is unset |
| `build.format` | `String` | `"{name}-{version}.{build_count}"` | Artifact file name format |
| `build.jarSuffix` | `String` | `"-Jar"` | Desktop jar suffix |
| `build.androidSuffix` | `String` | `"-Android"` | Android jar suffix |
| `build.deploySuffix` | `String` | `""` | Merge jar suffix |
| `build.timeFormat` | `String` | `"yyyyMMdd_HHmmss"` | Format of the `{time}` placeholder |
| `build.androidSdkDir` | `Directory` | auto | Android SDK for `jarAndroid`; also the install target. Missing/unset → falls back to `ANDROID_HOME` → `ANDROID_SDK_ROOT` → user home (a stale path is skipped, not fatal) |
| `build.d8Args` | `List<String>` | `[]` | Extra arguments passed to `d8` (e.g. `"--no-desugaring"`) |
| `build.d8TimeoutMinutes` | `Long` | `30` | Timeout for the `d8` subprocess, in **minutes** |
| `build.d8DrainJoinMillis` | `Long` | `5000` | How long to join the d8 output drain thread, in ms (not a d8 timeout) |

### `debug { }` — logging and debugging

| Property | Type | Default | Description |
|----------|------|---------|----|
| `debug.maxLogFiles` | `Int` | `25` | Run log files to keep in `build/logger/` |
| `debug.enableRunLogging` | `Boolean` | `true` | Write `runMindustry` output to log files (console tee always happens) |
| `debug.enableDebug` | `Boolean` | `false` | Default for opening the JDWP socket; `-PmindustryDebug=...` wins per run |
| `debug.debugPort` | `Int` | `5005` | JDWP port, also written into the generated attach configuration |
| `debug.debugSuspend` | `Boolean` | `false` | Default for waiting for the debugger; `-PmindustryDebugSuspend=...` wins per run |

A property passed for one run overrides the block in **both** directions: `-PmindustryDebug=false`
turns the socket off for a project that enables it here (use that in CI), and a bare
`-PmindustryDebug` counts as on. The generated Gradle run configuration passes `=true`.

#### Game data directory

The data directory decides where the game keeps `settings.bin`, `saves/`, `maps/`, `schematics/`, `screenshots/` — and where the plugin deploys mods, because Mindustry only looks for them in `<dataDir>/mods` (the folder is created when it does not exist). When you do not configure it, the plugin resolves the same directory the game would use: `MINDUSTRY_DATA_DIR` if that variable is set, otherwise the per-OS location (`~/Library/Application Support/Mindustry`, `%AppData%/Mindustry`, `~/.local/share/Mindustry`). There is no `mindustryModsDir` any more: the path is always `<dataDir>/mods`.

Setting `run.gameDataDir` makes the plugin pass `-Dmindustry.data.dir=<absolute path>` to the game JVM. For a project-local directory, which keeps the game's saves next to the build and survives `clean`, write `run { gameDataDir = layout.projectDirectory.dir("data") }`; in a multi-project build set it in every project (`allprojects { }`) so they share one directory. That property exists from Mindustry **v147**; on an older game the plugin only **warns** and the run proceeds with the variable or the game's own directory. `download.mindustryDownloadVersion` defaults to `"147"`, the first release that reads that property.

Notes: `data/` sits outside `build/`, so `./gradlew clean` does not delete it — add it to your own `.gitignore`. A directory you pick starts empty: existing saves and settings stay where they were, nothing is copied. Two instances sharing one data directory fight over `settings.bin` and `saves/`, so give each its own `gameDataDir`. The directory in the project root is excluded from the mod source scan, so stray `.kt`/`.java` files in it are never compiled into the jar. The property is what `ClientLauncher` reads, so a dedicated headless server ignores it (this plugin never launches one), and none of this affects Android/iOS.

#### Android SDK auto-install

`jarAndroid` needs an SDK with a `platforms/<ver>/android.jar` and a `build-tools/<ver>/d8`. When the resolved SDK cannot satisfy `download.androidSdkDownloadPackages` — it is missing, empty, or holds the *wrong versions* — the plugin downloads the official command-line tools from the configured channel and runs `sdkmanager` for those packages (licences are accepted automatically). The install target is `build.androidSdkDir` when set (an absent or empty directory is created), otherwise `run.androidSdkInstallDir`; an SDK found elsewhere, e.g. behind `ANDROID_HOME`, is used as-is and never modified. It is off by default, so a build never starts that download on its own: enable it with `download { androidSdkAutoDownload = true }`, otherwise `jarAndroid` fails with the list of locations it probed. With a mirror, point `androidSdkDownloadUrl` at it for the command-line tools, and specify the mirror as an HTTP proxy in `androidSdkExtraArgs` for the package downloads (`--proxy=http --proxy_host=<host> --proxy_port=<port>`, plus `--no_https` if it only speaks HTTP) — `sdkmanager` reads Google's own package list, so that proxy is the only way to redirect them.

> `sdkmanager` caches the repository manifest under `$ANDROID_USER_HOME/cache` (default `~/.android/cache`) and reports a *download* failure when that path is read-only — a real trap in containers and sandboxes. Unless `ANDROID_USER_HOME` is already set, the plugin redirects it to `<sdk>/.android-user`.

#### Debugging in IDEA

Two run configurations are generated in `<root>/.run/` by a standalone task — it depends on
nothing and nothing depends on it, and it reads `debugPort` at execution time, so the DSL value is
what lands in the files. Run it once (IDEA needs the files before you can click them):

```sh
./gradlew generateIdeaRunConfigs      # writes .run/Mindustry-*.run.xml, UP-TO-DATE afterwards
```

1. **Mindustry: runMindustry (debug, port N)** — a Gradle configuration that runs `runMindustry`
   with `-PmindustryDebug=true`, which opens the JDWP port.
2. **Mindustry: attach debugger (port N)** — a Remote JVM Debug configuration on the same port;
   attaching to it is what resolves breakpoints in your mod sources.

Start the game with the first, then attach with the second. Notes that save time:

- Press **Run** on the first configuration, not Debug. It is a *Gradle* configuration, so Debug
  would attach IDEA to the Gradle build process (`ExternalSystemDebugServerProcess=true`, same as
  IDEA's own Gradle template) instead of your mod.
- The JDWP socket is bound to `localhost` only. An unauthenticated debug port on every interface
  is remote code execution for anyone on the network.
- The **Debugger console shows no game output**: stdout is teed to the Gradle console and
  `build/logger/log_*.log`. An empty debugger console does not mean the game is stuck.
- `debug { }` holds the defaults (`enableDebug`, `debugPort`, `debugSuspend`), and a property passed for
  one run wins over them in both directions. The generated configuration passes `-PmindustryDebug=true`;
  add `-PmindustryDebugSuspend=true` to that run to also break into startup code, and use
  `-PmindustryDebug=false` (or `gradle.properties`) to force the socket closed for CI.
- The task has no dependencies and nothing depends on it, so it only runs when you invoke it; disable it with `tasks.named("generateIdeaRunConfigs") { enabled = false }` if you do not want it in the task list at all.
  The task is UP-TO-DATE while nothing changed, which matters because IDEA also rewrites these
  files on sync — the two no longer fight each other.

### `mindustryMod { }` — mod project

#### `modMeta { }` metadata

`modMeta { }` is registered as a **top-level project extension**, so it sits next to
`mindustryMod { }` instead of inside it. `mindustryMod { modMeta { ... } }` still works and
configures the same object.

| Field | Required | Description |
|-------|----------|----|
| `name` | ✅ | Internal mod identifier |
| `internalName` | — | Read-only, derived from `name` (`"My Mod"` → `"my-mod"`) |
| `displayName` | | Display name (falls back to `name`) |
| `author` | | Mod author |
| `version` | | Version (default `"0"`) |
| `description` | | Mod description |
| `subtitle` | | Short description in the mod list |
| `main` | | Main class (auto-derived from `name`) |
| `repo` | | GitHub `"owner/repo"` |
| `minGameVersion` | | Minimum game build, as a string (e.g. `"146"`) |
| `java` | ✅ Recommended | Marks as Java/Kotlin mod |
| `hidden` | | Hides from mod browser, cannot add content |
| `iosCompatible` | | Claims iOS compatibility |
| `textureScale` | | Pixels per 1x1 block sprite (default `1.0`); file key stays `texturescale` |
| `pregenerated` | | Skips bleed + content icon generation |
| `contentOrder` | | Content load order (array) |
| `legacyCompatible` | | Compatible with older major version |
| `dependencies` | | Hard dependency names — `dependencies += "lib"` or `dependencies = listOf("a", "b")` |
| `softDependencies` | | Optional dependency names (same syntax) |

Full list in [`ModMeta.kt`](src/main/kotlin/com/example/mindustry/meta/ModMeta.kt).

> All 19 fields of the engine's `Mods.ModMeta` are covered; `internalName` is derived (it is not a `mod.hjson` key).

#### File paths

| Property | Type | Default | Description |
|----------|------|---------|----|
| `readme` | `RegularFile` | `<projectDir>/README.md` | Included in jar if exists |
| `license` | `RegularFile` | `<projectDir>/LICENSE` | Included in jar if exists |
| `icon` | `RegularFile` | `<projectDir>/icon.png` | Included in jar if exists |
| `assets` | `ConfigurableFileCollection` | `<projectDir>/assets/` | Multiple dirs supported |
| `generateModMeta` | `Boolean` | `false` | Auto-generate mod meta from `modMeta`, back-filled from an existing metadata file |

#### Metadata back-fill

When `generateModMeta = true`, every field still at its default value is read back from an existing metadata file in the project root, so adopting the DSL does not drop metadata that only lives in the file. Explicit DSL values always win. Because "unset" is detected as "equal to the default", explicitly setting a field *to* its default cannot override the file value.

The engine accepts four metadata file names — `mod.json`, `mod.hjson`, `plugin.json`, `plugin.hjson` (`Mods.java:34`). They are checked in exactly that order, the same one the game uses, and the first one that exists is the back-fill source. Every one of the four names that exists is packed into the jar.

---

## Available tasks

| Task | Project | Description |
|------|---------|----|
| `downloadMindustry` | root | Download game jar |
| `runMindustry` | root | Deploy mods + launch game |
| `buildModHJson` | mod | Generate `mod.hjson`/`mod.json` |
| `jar` | mod | Build desktop jar |
| `jarAndroid` | mod | Build Android DEX (needs an Android SDK: `build.androidSdkDir`, `ANDROID_HOME`/`ANDROID_SDK_ROOT`, or auto-download) |
| `deploy` | mod | Merge desktop + Android jars |
| `generateIdeaRunConfigs` | root | Write `.run/` IDEA run configurations (no dependencies) |

`runMindustry` deploys the built jars and launches the game, but it does **not** depend on the
packaging tasks: `./gradlew runMindustry` alone copies whatever `build/libs/` already holds (a missing
jar is reported with the command that builds it). Build and run in one go with the command-line order,
which Gradle guarantees — `./gradlew deploy runMindustry` — or wire it in your build script:

```kotlin
tasks.named("runMindustry") { dependsOn(":sub:deploy") }   // per mod project
```

The generated `.run/` configuration already does the equivalent: it lists the packaging task before
`runMindustry`, so a click in the IDE builds first.

#### Incremental builds

`buildModHJson` and `jarAndroid` declare their inputs and outputs, so they are skipped when nothing changed, and all three jars (`-Jar`, `-Android`, and the merged one) stay in `build/libs/`. The default `build.format` contains `{build_count}`, which gives every build a differently named artifact — drop it (`format = "{name}-{version}"`) if you want `jar`/`deploy` to be UP-TO-DATE too.

---

## Build

```sh
./gradlew test       # run all tests
./gradlew test --tests "*ModMeta*"   # single class
```

---

## Notes

- `settings.gradle.kts` must not use `pluginManagement` for Kotlin plugin — collides with Gradle TestKit
- `jarAndroid` needs an Android SDK: `build.androidSdkDir`, the `ANDROID_HOME`/`ANDROID_SDK_ROOT` variables, or `download.androidSdkAutoDownload`
- CI runs the test suite on JDK 17, 21 and 25 plus `validatePlugins` (`.github/workflows/ci.yml`); no pre-commit hooks, no codegen

## Android / Termux

Building a mod on the phone works, and doing it in plain Termux (no container) needs **no Android SDK**:

```sh
pkg install openjdk-17 git unzip d8     # d8 is the dexer; openjdk-21 too if you run jdtls
GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew jar jarAndroid deploy
```

- **d8 is found without an SDK.** `build.d8Executable` wins, then `d8` on the `PATH` (what `pkg install d8`
  provides). Either one makes `jarAndroid` skip SDK resolution entirely, so nothing is downloaded. With no
  SDK configured, an installed SDK is used if it exists, and only then is one installed.
- `android.jar` is **optional**: without it d8 still runs and warns, because desugaring is only less
  precise. Termux usually has no SDK at all.
- A build-tools directory whose `d8` launcher is missing (or unusable, because its `d8` is a shell script
  and Android has no `/bin/sh`) falls back to `java -cp lib/d8.jar com.android.tools.r8.D8`.
- Put `d8Executable` under `mindustryModRoot { build { } }`.

**Deploying.** The Android client reads `/storage/emulated/0/Android/data/io.anuke.mindustry/files/mods`,
but since Android 11 no other app — Termux included — may write there, and since Android 14 the game also
needs the file to be read-only. So import the built jar in the game instead: **Mods → Import mod**, and
pick `build/libs/<name>.jar`. The BE build uses the app id `io.anuke.mindustry.be`; both are configurable
with `run.androidAppId`, and `run.hostPlatform` (default `Auto`) decides whether the build treats itself as
Android. Termux is recognised from `TERMUX_VERSION` or a `PREFIX` inside `com.termux`.

**gradle.properties** for a phone:

```properties
org.gradle.vfs.watch=false   # Android cannot watch the file system, so -t/--continuous do not work
org.gradle.daemon=false      # Android 12+ kills background processes when memory is tight
org.gradle.jvmargs=-Xmx1g
# Supported; uncomment the next line to turn it on.
# org.gradle.configuration-cache=true
```

Two findings worth knowing, both verified against the game:

- The **desktop** `Mindustry.jar` cannot run on Android aarch64: Arc's SDL backend is only published for
  x86_64 Linux and macOS, so there is no `libsdl-arcarm64.so` at all.
- On the Android client, neither `MINDUSTRY_DATA_DIR` nor `-Dmindustry.data.dir` has any effect — its
  launcher sets the data directory itself, and nothing can pass JVM arguments to an APK.

**Running on Android.** `runMindustry` stages each built jar into `run.androidStagingDir` (default
`$HOME/AndroidStaging`), tells you to use **Mods → Import mod**, and calls `am force-stop` so the game picks
the file up on its next start; `run.androidLaunchApk = true` also runs `am start`. A missing or refusing `am`
is only a warning. Staging is all this needs: there is no JVM to launch, because the APK's launcher takes no
JVM arguments and no other app may write into its `Android/data` directory for you. Requesting a debugger on
Android fails with an explanation, since the Android runtime has no JDWP socket.

**Headless server, and how to debug on a phone.** `run.useHeadlessServer = true` runs `server-release.jar`
(downloaded by `downloadHeadlessServer` into `download.headlessJarPath`) inside
`run.headlessServerWorkingDir`, whose `config/mods` is where the mods are staged. That is an ordinary JVM,
so `-PmindustryDebug=true` opens the usual `localhost:5005` for `jdb -attach localhost:5005` or a DAP client
— the one way to debug on a phone. The server follows `download.mindustryDownloadVersion`, and a mismatch
with `mindustryApiVersion` warns, because it shows up as `NoClassDefFoundError`.
