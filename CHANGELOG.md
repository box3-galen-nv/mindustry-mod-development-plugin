# Changelog

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
