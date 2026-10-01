# mindustry-mod-development-plugin

**简体中文** · [English](README.md)

用于构建 [Mindustry](https://github.com/Anuken/Mindustry) 模组的 Gradle 插件，支持 Kotlin/Java，自动处理元数据生成、打包、Android DEX 编译和游戏启动。


---

## 使用方法

### 引入插件

插件发布在 [Gradle Plugin Portal](https://plugins.gradle.org/) 上，Gradle 默认就会去那里找，因此**无需**为它声明仓库。目前**不支持 configuration cache**：请用 `--no-configuration-cache`（或不要开启 `org.gradle.configuration.cache`），等日志监听器重做后再启用。

```kotlin
// build.gradle.kts (root)
plugins {
    id("io.github.box3-galen-nv.mindustry-mod-development-plugin") version "1.0.0" apply false
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

**游戏 API 不需要你声明任何仓库。** 插件从 **GitHub Release 资产**解析 `arc-core` + `core`
（与官方 [MindustryJavaModTemplate](https://github.com/Anuken/MindustryJavaModTemplate) 同一机制），
通过一个 content-filtered 的 Ivy 仓库，且声明为 artifact-only 元数据 —— 没有 POM，因此没有传递依赖，
也不需要信任任何镜像：

| `mindustryApiVersion` | 使用的资产 | 体积 |
|-----------------------|-----------|------|
| `be` | `MindustryBuilds` 的 `master/latest.jar` | ~15 MB |
| `latest`、`>= 155.4` | `dependencies.jar` | 13–15 MB |
| `97 … 155.3` | `Mindustry.jar`（可运行 jar，同时内含 Arc） | 73–89 MB |

**v97 是下限**：这是第一个带模组系统的版本（`mod.json` / `plugin.json`）。v92 只有服务端插件，
`mod.hjson` 从 v101 开始，而如今模组 import 的包从 v102 才存在 —— 低于 v97 会直接报错，而不是给出
令人困惑地依赖解析失败。

唯一可能仍需要声明的仓库是 `mavenCentral()`，且仅**Kotlin** 模组需要（`kotlin-stdlib`）。纯 Java
模组的 `repositories { }` 可以是空的。

> 为什么不用 Maven 坐标？JitPack 上 `core:v159` 的 POM 要求
> `com.github.Anuken.Arc:arc-core:6aee8e7686` 这个没有任何仓库提供的 commit hash（而 Gradle 会优先选它）；
> 第三方的 Zelaux/MindustryRepo 镜像也不自足（且它最新的 v156.1 的 module 元数据里残留了合并冲突标记，Gradle 直接拒绝解析）—— 它的 `core:v146` POM 需要 `org.lz4:lz4-java`。资产路线两个问题都没有。

### 单项目模式

根项目自身就是模组，无需 `src/<name>/` 子模块。

```kotlin
// Java-only 模组请去掉 kotlin("jvm") 那一行
plugins {
    kotlin("jvm") version "2.4.20"
    id("io.github.box3-galen-nv.mindustry-mod-development-plugin") version "1.0.0"
}

mindustryModRoot {
    mindustryApiVersion = "159"
}

// modMeta { } 是独立的顶层配置块
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

**源码放哪**：项目目录本身就是源码根 —— `.kt` / `.java` 直接放在 `build.gradle.kts` 旁边（单项目），
或放在子项目根目录（多项目 `src/<mod>/`）。`src/main/kotlin` 同样可用。`build/` 与 `.gradle/` 会被排除，
Kotlin 脚本（`*.kts`）永远不参与模组源码编译。`.java` 由 `kotlin("jvm")` 顺带应用的 Java 插件编译，
所以项目需要应用 Kotlin 或某个 Java 插件 —— 应用 Kotlin 时 Java 已经就位。

### 多项目模式

每个模组以子项目形式放在 `src/<name>/` 下。

```
project/
├── build.gradle.kts          # 根项目 — 下载 + 运行
├── settings.gradle.kts       # include(":src/modA", ":src/modB")
└── src/
    └── modA/
        └── build.gradle.kts  # 模组配置
            modMeta { name = "modA"; version = "1.0"; java = true }
            mindustryMod { generateModMeta = true }
```

---

## 扩展配置

### 仅根项目

| 属性 | 类型 | 默认值 | 说明 |
|----------|------|---------|----|
| `mindustryApiVersion` | `String` | *(未设置)* | 编译所用的 Mindustry 版本 —— 可选，不设就不加任何 API 依赖：`"159"`、`"v159"`、`"latest"`、`"be"`（≥ v97） |
| `build.useHJson` | `Boolean` | `false` | 生成元数据的格式（默认 `mod.json`） |
| `download.mindustryDownloadVersion` | `String` | `"146"` | 要下载的游戏版本；可用 `"latest"`，`"be"` 只是编译期 API 通道、没有发布资产 |
| `download.mindustryDownloadUrl` | `String` | GitHub Releases | 下载基础 URL |
| `download.mindustryGamePath` | `RegularFile` | `<root>/build/game/Mindustry-<version>.jar` | 游戏 jar 路径；以 `.jar` 结尾按文件用，否则按目录用 |
| `download.mindustryDownloadFileName` | `String` | `"Mindustry-{version}"` | 下载文件名模板 |
| `download.androidSdkAutoDownload` | `Boolean` | `false` | 可选开启（供 `jarAndroid` 使用）：SDK 不可用时下载并安装 |
| `download.androidSdkDownloadUrl` | `String` | official Google URL | 命令行工具下载地址（渠道/镜像） |
| `download.androidSdkDownloadPackages` | `List<String>` | `["platforms;android-34", "build-tools;34.0.0"]` | 要下载并安装的 `sdkmanager` 包 |
| `download.androidSdkDownloadTimeoutMinutes` | `Long` | `30` | 工具与每次 `sdkmanager` 调用的**下载**超时，单位分钟（不是运行超时） |
| `download.androidSdkExtraArgs` | `List<String>` | `[]` | 透传给 `sdkmanager` 的额外参数 —— 它没有"指定仓库地址"的开关，镜像只能按 HTTP 代理传 |
| `run.gameDataDir` | `Directory` | `MINDUSTRY_DATA_DIR`, else the per-OS Mindustry directory | 游戏数据目录；mods 目录跟随它（`<gameDataDir>/mods`）。设置它会传 `-Dmindustry.data.dir` |
| `run.deployTag` | `String` | `"mm-deploy"` | 标记本插件部署的 jar（`[mm-deploy]…`） |
| `run.cleanDeployedFiles` | `Boolean` | `true` | 再次部署前删除本插件先前部署的文件 |
| `run.useDeployRun` | `Boolean` | `true` | 拷贝合并后的 `deploy` 包而非 `jar`（只决定拷哪个产物；`runMindustry` 不会依赖该任务） |
| `run.androidSdkInstallDir` | `Directory` | `<gradleUserHome>/mindustry-mod-development-plugin/android-sdk` | 未配置 `androidSdkDir` 时的安装目录 |
| `build.format` | `String` | `"{name}-{version}.{build_count}"` | 产物文件名格式 |
| `build.jarSuffix` | `String` | `"-Jar"` | 桌面 jar 后缀 |
| `build.androidSuffix` | `String` | `"-Android"` | Android 包后缀 |
| `build.deploySuffix` | `String` | `""` | 合并包后缀 |
| `build.timeFormat` | `String` | `"yyyyMMdd_HHmmss"` | `{time}` 占位符格式 |
| `build.androidSdkDir` | `Directory` | auto | `jarAndroid` 用的 Android SDK，同时是安装目标；缺失/未配置时依次回退到环境变量和用户目录（路径失效只跳过，不报错） |
| `build.d8Args` | `List<String>` | `[]` | 附加传给 `d8` 的参数列表 |
| `build.d8TimeoutMinutes` | `Long` | `30` | `d8` 子进程超时，单位**分钟** |
| `build.d8DrainJoinMillis` | `Long` | `5000` | 等待 d8 输出 drain 线程的时长（毫秒，不是 d8 超时） |

### 日志与调试

| 属性 | 类型 | 默认值 | 说明 |
|----------|------|---------|----|
| `debug.maxLogFiles` | `Int` | `25` | `build/logger/` 中保留的运行日志数 |
| `debug.enableRunLogging` | `Boolean` | `true` | 是否把 `runMindustry` 输出写入日志文件（控制台始终有输出） |
| `debug.enableDebug` | `Boolean` | `false` | 是否默认打开 JDWP 端口；单次运行由 `-PmindustryDebug=...` 覆盖 |
| `debug.debugPort` | `Int` | `5005` | JDWP 端口，也会写进生成的附加配置 |
| `debug.debugSuspend` | `Boolean` | `false` | 是否默认等待调试器；单次运行由 `-PmindustryDebugSuspend=...` 覆盖 |

单次运行传入的属性会**双向**覆盖这里的默认值：项目里开了 `enableDebug`，也可以用
`-PmindustryDebug=false`（CI 里推荐）把它关掉；裸写 `-PmindustryDebug` 视为开启。生成的 Gradle 运行配置传的就是 `=true`。

#### 游戏数据目录

数据目录决定游戏把 `settings.bin`、`saves/`、`maps/`、`schematics/`、`screenshots/` 放在哪里 —— 也决定插件把模组部署到哪里，因为 Mindustry 只在 `<数据目录>/mods` 里找模组（目录不存在时会自动创建）。你不配置时，插件按游戏自己的规则解析同一个目录：设置了 `MINDUSTRY_DATA_DIR` 就用它，否则用系统位置（`~/Library/Application Support/Mindustry`、`%AppData%/Mindustry`、`~/.local/share/Mindustry`）。**`mindustryModsDir` 已删除**：mods 目录永远跟随数据目录。

设置 `run.gameDataDir` 会让插件向游戏 JVM 传 `-Dmindustry.data.dir=<绝对路径>`。想要"项目内数据目录"（存档放在构建目录旁边、且 `clean` 不会删），写 `run { gameDataDir = layout.projectDirectory.dir("data") }`；多项目构建要在**每个项目**里都设置（例如放在 `allprojects { }`），它们才会共用同一个目录。该属性从 Mindustry **v147** 才有；旧版本上插件只**告警**，运行时回退到环境变量或游戏自己的目录。`download.mindustryDownloadVersion` 默认仍是 `"146"`，所以项目内数据目录建议配 v147+（或 `"latest"`）。

注意：`data/` 在 `build/` 之外，`./gradlew clean` 不会删它 —— 建议自己加进 `.gitignore`。你指定的目录是**全新空目录**：原存档与设置留在原处，插件不复制。两个实例共用一个数据目录会争 `settings.bin` 与 `saves/`，请各给一个 `gameDataDir`。项目根下的该目录会被排除出 mod 源码扫描，里面的 `.kt`/`.java` 不会被编译进 jar。该属性由 `ClientLauncher` 读取，所以专用无头服务端不认它（本插件也不会启动服务端），Android/iOS 同样不受影响。

#### Android SDK 自动安装

`jarAndroid` 需要一个含 `platforms/<ver>/android.jar` 与 `build-tools/<ver>/d8` 的 SDK。当解析出的 SDK 无法满足 `download.androidSdkDownloadPackages` 时（缺失、为空、或**版本不对**），插件会从配置的渠道下载官方命令行工具，并用 `sdkmanager` 安装这些包（license 自动接受）。安装位置：配置了 `build.androidSdkDir` 就用它（目录不存在或为空则创建），否则用 `run.androidSdkInstallDir`；在别处（如 `ANDROID_HOME`）找到的 SDK 只按原样使用，绝不改动。它默认关闭，构建不会擅自开始几百 MB 的下载：需要时用 `download { androidSdkAutoDownload = true }` 开启，否则 `jarAndroid` 会像以前一样报错并列出所有探测过的位置。用镜像时：命令行工具由 `androidSdkDownloadUrl` 指定为镜像地址；`sdkmanager` 的包下载则需要在 `androidSdkExtraArgs` 里指定镜像作为 HTTP 代理（`--proxy=http --proxy_host=<host> --proxy_port=<port>`，镜像只支持 HTTP 时再加 `--no_https`）—— `sdkmanager` 读的是 Google 自己的包列表，这个代理是唯一能让包下载改道的途径。

> `sdkmanager` 会把仓库清单缓存在 `$ANDROID_USER_HOME/cache`（默认 `~/.android/cache`），该路径不可写时它会**误报成"下载失败"**（容器/沙箱里的真实坑）。除非环境已设置 `ANDROID_USER_HOME`，插件会把它重定向到 `<sdk>/.android-user`。

#### 在 IDEA 中调试

两个运行配置由**一个独立任务**写入 `<root>/.run/`：它不依赖任何任务、也没有任务依赖它，且在**执行时**才读取 `debugPort`，所以 DSL 里的值会正确落到文件里。跑一次即可（IDEA 需要文件先存在才能点）：

```sh
./gradlew generateIdeaRunConfigs      # 写入 .run/Mindustry-*.run.xml，之后是 UP-TO-DATE
```

1. **Mindustry: runMindustry (debug, port N)** —— Gradle 配置，带 `-PmindustryDebug=true` 启动 `runMindustry`，从而打开 JDWP 端口。
2. **Mindustry: attach debugger (port N)** —— 指向同一端口的 Remote JVM Debug 配置，附加后即可命中模组源码里的断点。

先用第一个启动游戏，再用第二个附加。几个省时间的注意点：

- 第一个配置请点 **Run** 而不是 Debug：它是 *Gradle* 配置，点 Debug 会把 IDEA 挂到 Gradle 构建进程上（`ExternalSystemDebugServerProcess=true`，与 IDEA 自带 Gradle 模板一致），调试的不是你的模组。
- JDWP 只监听 `localhost`。未鉴权的调试端口若监听所有网卡，等于把远程代码执行暴露给同网段任何人。
- **Debugger 控制台没有游戏输出**：stdout 被 tee 到 Gradle 控制台与 `build/logger/log_*.log`，控制台空不代表卡住。
- `debug { }` 提供默认值（`enableDebug`、`debugPort`、`debugSuspend`），单次运行传入的属性会**双向**覆盖它们。生成的配置传 `-PmindustryDebug=true`；想断在启动期代码就给那次运行再加 `-PmindustryDebugSuspend=true`；CI 里用 `-PmindustryDebug=false`（或写进 `gradle.properties`）强制关闭。
- 该任务没有依赖、也没人依赖它，只有你主动调用才会运行；若完全不想要它，用 `tasks.named("generateIdeaRunConfigs") { enabled = false }` 关掉。没有变化时任务是 UP-TO-DATE —— 这点很重要，因为 IDEA 同步时也会改写这些文件，两者不再互相打架。

### 模组项目

#### 元数据

`modMeta { }` 注册为**项目级顶层扩展**，与 `mindustryMod { }` 平级而不是嵌套其中。
`mindustryMod { modMeta { ... } }` 的旧写法依然可用，配置的是同一个对象。

| 字段 | 必填 | 说明 |
|-------|----------|----|
| `name` | ✅ | 模组标识名 |
| `internalName` | — | 只读派生，依赖列表里填这个 |
| `displayName` | | 显示名称 |
| `author` | | 作者 |
| `version` | | 版本号 |
| `description` | | 简介 |
| `subtitle` | | 列表短描述 |
| `main` | | 入口主类 |
| `repo` | | 仓库路径 |
| `minGameVersion` | | 最低兼容版本（字符串） |
| `java` | ✅ 推荐 | 标记为 Java/Kotlin 模组 |
| `hidden` | | 隐藏且不能注册内容 |
| `iosCompatible` | | 声称兼容 iOS |
| `textureScale` | | 纹理缩放（属性驼峰，文件键仍为 `texturescale`） |
| `pregenerated` | | 跳过 bleed 与图标生成 |
| `contentOrder` | | 内容加载顺序 |
| `legacyCompatible` | | 兼容旧主版本 |
| `dependencies` | | 硬依赖 |
| `softDependencies` | | 软依赖 |

完整字段见 [`ModMeta.kt`](src/main/kotlin/com/example/mindustry/meta/ModMeta.kt)。

> 已覆盖引擎 `Mods.ModMeta` 的全部 19 个字段；`internalName` 为派生字段（不是 `mod.hjson` 的键）。

#### 文件路径

| 属性 | 类型 | 默认值 | 说明 |
|----------|------|---------|----|
| `readme` | `RegularFile` | `<projectDir>/README.md` | 存在则打包入 jar |
| `license` | `RegularFile` | `<projectDir>/LICENSE` | 存在则打包入 jar |
| `icon` | `RegularFile` | `<projectDir>/icon.png` | 存在则打包入 jar |
| `assets` | `ConfigurableFileCollection` | `<projectDir>/assets/` | 支持多个目录 |
| `generateModMeta` | `Boolean` | `false` | 自动生成元数据文件，未配置的字段从已有元数据文件回填 |

#### 元数据回填

`generateModMeta = true` 时，仍为默认值的字段会从项目根目录已有的元数据文件回填，因此逐步迁移到 DSL 不会丢掉只写在文件里的元数据。DSL 中显式配置的值始终优先。由于"未配置"是按"等于默认值"判定的，把字段**显式设成默认值**无法覆盖文件里的值。

引擎接受 4 个元数据文件名 —— `mod.json`、`mod.hjson`、`plugin.json`、`plugin.hjson`（`Mods.java:34`），检查顺序与游戏一致，第一个存在的文件作为回填来源；四个文件名中凡存在的都会被打进 jar。

---

## 可用任务

| 任务 | 项目 | 说明 |
|------|---------|----|
| `downloadMindustry` | root | 下载游戏 |
| `runMindustry` | root | 部署并启动游戏 |
| `buildModHJson` | mod | 生成模组元数据 |
| `jar` | mod | 打包桌面 Jar |
| `jarAndroid` | mod | 打包 Android DEX（需要 Android SDK：`build.androidSdkDir`、`ANDROID_HOME`/`ANDROID_SDK_ROOT`，或自动下载） |
| `deploy` | mod | 合并桌面和 Android 包 |
| `generateIdeaRunConfigs` | root | 生成 `.run/` 的 IDEA 运行配置（无依赖） |

`runMindustry` 只负责部署已构建的 jar 并启动游戏，**不会**依赖打包任务：单独执行
`./gradlew runMindustry` 会拷贝 `build/libs/` 里现有的东西（缺产物时的提示会给出构建命令）。
想一条命令完成"构建 + 运行"，用 Gradle 保证顺序的命令行写法 `./gradlew deploy runMindustry`，
或在构建脚本里自己接线：

```kotlin
tasks.named("runMindustry") { dependsOn(":sub:deploy") }   // 每个模组项目一条
```

插件生成的 `.run/` 配置已经等价地做了这件事：里面的任务列表是"打包任务 + `runMindustry`"，
所以在 IDE 里点一下会先构建。

#### 增量构建

`buildModHJson` 与 `jarAndroid` 都声明了输入输出，没有变化时会跳过；三个 jar（`-Jar`、`-Android`、合并后的）都保留在 `build/libs/`。默认 `build.format` 含 `{build_count}`，会让每次构建的产物名都不同 —— 想让 `jar`/`deploy` 也能 UP-TO-DATE，就去掉它（`format = "{name}-{version}"`）。

---

## 构建

```sh
./gradlew test       # 运行全部测试 (JUnit 5)
./gradlew test --tests "*ModMeta*"   # 单个类
```

---

## 注意事项

- 不要在 `settings.gradle.kts` 中用 `pluginManagement` 指定 Kotlin 插件版本，会和 TestKit 冲突
- `jarAndroid` 需要 Android SDK：可用 `build.androidSdkDir`、`ANDROID_HOME`/`ANDROID_SDK_ROOT` 环境变量，或 `download.androidSdkAutoDownload`
- CI 在 JDK 17/21/25 上跑测试并校验插件元数据（`.github/workflows/ci.yml`）；无钩子、无代码生成
