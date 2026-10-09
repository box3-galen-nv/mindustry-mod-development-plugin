# Changelog

## Unreleased

- Copying the built mod jars into the game's mods directory is its own task, `copyMods`, which
  `runMindustry` depends on: it can be run alone, it is UP-TO-DATE while the jars are unchanged, and it
  no longer hides a write into the game directory inside the launch task.
- The Android SDK download is now its own task, `downloadAndroidSdk`, which `jarAndroid` depends on.
- `download.androidSdkDownloadPlatformOnly` (default off) lets a Termux setup with `pkg install d8`
  install just the platform packages, so `android.jar` can reach d8's desugaring classpath without
  installing a build-tools over the d8 that is already there.
- `downloadAndroidApk` fetches the Mindustry APK from `run.androidApkUrl` when a project sets one — the
  plugin cannot fetch it itself, because itch.io serves no direct file URL — validates that it really is an
  APK (`AndroidManifest.xml` and `classes.dex`), and warns with the itch.io pointer when neither a URL nor a
  local `run.androidApkPath` is available. A missing APK never fails a build.
- `checkAndroidApkVersion` reads the APK's own `assets/version.properties` and warns when it does not match
  `download.mindustryDownloadVersion`, or when it is a BE build whose application id differs from
  `run.androidAppId`. Local only, warn only, and switchable with `run.androidApkVersionCheck`.
- On Termux the unpacked command-line tools get their shebang rewritten to `$PREFIX/bin/sh`, because
  Android has no `/bin/sh` and `sdkmanager` is a shell script the plugin execs directly.
  It is UP-TO-DATE while the requested packages are present, is skipped when a d8 already resolved
  (a configured one, the `PATH`, or an installed SDK), and never runs from inside `jarAndroid` itself.

## 1.0.1

- declares that the configuration cache is not supported, which the Plugin Portal requires of every
  plugin and which 1.0.0 was submitted without. No behavior change.

## 1.0.0

First release, published on the Gradle Plugin Portal as
`io.github.box3-galen-nv.mindustry-mod-development-plugin`.

- builds Mindustry mods written in Java or Kotlin: `jar`, `jarAndroid` (d8), and a merged `deploy`
- generates `mod.json` / `mod.hjson` from the `modMeta { }` block, back-filled from an existing metadata file
- resolves the game API from the GitHub release assets (no POM, no transitive dependencies, v97 or newer)
- runs the game with the mods deployed into the game data directory: `MINDUSTRY_DATA_DIR` or the per-OS
  default, overridable with `run.gameDataDir`, and always `<dataDir>/mods`
- downloads the game and the Android SDK with its own downloader, so no third-party download plugin is
  added to a consumer's buildscript classpath
- generates the IDEA run configurations (game with a JDWP socket + attach debugger)
