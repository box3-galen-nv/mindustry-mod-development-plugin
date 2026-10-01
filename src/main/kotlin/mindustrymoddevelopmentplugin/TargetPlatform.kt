package mindustrymoddevelopmentplugin

/**
 * Packaging target platform.
 *
 * Selects the build target for [MindustryModExtension.modVersion].
 */
enum class TargetPlatform {
    /** Desktop jar only. */
    Jar,
    /** Android DEX only. */
    Android,
    /** All platforms — the value [MindustryModExtension.modVersion] falls back to. */
    All,
}
