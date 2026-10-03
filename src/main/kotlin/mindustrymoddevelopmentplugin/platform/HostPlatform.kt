package mindustrymoddevelopmentplugin.platform

/**
 * Where the build, and therefore the game it deploys to, is expected to run.
 *
 * Only two cases differ in practice: a desktop machine, and Termux on Android. The Android case changes
 * three things — there is no desktop game jar to launch (Arc ships no aarch64 Linux SDL backend), the game
 * data directory is not the desktop one, and an Android SDK is not needed to produce dex.
 */
enum class HostPlatform {
    /** Detect from the environment with [detect]. */
    Auto,

    /** A desktop: `runMindustry` launches the desktop jar with JDWP. */
    Desktop,

    /** Termux on Android: no desktop jar, no attachable game JVM, and no SDK needed for dex. */
    Android;

    companion object {
        /**
         * Recognizes Termux, which is what [Android] means here.
         *
         * The environment is passed in rather than read so the decision is testable and so a build can show
         * what was inspected. `PREFIX` is only trusted when it points inside `com.termux`, because unrelated
         * tools set that variable too, while `TERMUX_VERSION` is Termux's own.
         *
         * The architecture is deliberately not part of the test: Linux plus Termux is the signal, and a
         * Termux on x86_64 (emulator, ChromeOS) is just as unable to run the desktop jar as one on aarch64.
         * What actually differs on Android — no JVM a debugger can attach to, and no way to write the game's
         * own data directory — does not depend on the CPU either.
         */
        fun detect(
            osName: String = System.getProperty("os.name").orEmpty(),
            prefix: String? = System.getenv("PREFIX"),
            termuxVersion: String? = System.getenv("TERMUX_VERSION"),
        ): HostPlatform {
            val termux = !termuxVersion.isNullOrBlank() || prefix.orEmpty().contains("com.termux")
            return if (termux && osName.lowercase().contains("linux")) Android else Desktop
        }
    }
}
