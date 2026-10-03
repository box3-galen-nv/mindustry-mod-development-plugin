package mindustrymoddevelopmentplugin.wiring

import java.io.File

/**
 * Chooses the default directory `runMindustry` stages mod jars into on Android.
 *
 * The staged jar has to be somewhere the *game's* file picker can reach, because importing it is a manual
 * step in the game. A Termux-private directory under the home directory is not reachable: the picker
 * (`ACTION_OPEN_DOCUMENT`) goes through Android's document provider, which only shows the shared volumes.
 * The private directory is therefore the fallback for a device where the shared volume is missing or
 * Termux was never granted storage access, and the run says so when it uses it.
 */
internal object AndroidStaging {
    /** The shared Download directory, and the private fallback for when it cannot be used. */
    val SHARED_DIR = File("/sdcard/Download")

    /**
     * @param userHome the private fallback's parent, which is Termux's own home on Android
     * @param shared the shared volume to prefer, overridable so the choice can be tested
     * @param isUsable whether a directory can actually be written to; injectable for the same reason
     */
    fun defaultDir(
        userHome: File,
        shared: File = SHARED_DIR,
        isUsable: (File) -> Boolean = { it.isDirectory && it.canWrite() },
    ): File = if (isUsable(shared)) shared else File(userHome, "AndroidStaging")

    /** True when [dir] is the private fallback, which the game's picker cannot see. */
    fun isPrivateFallback(dir: File, userHome: File): Boolean =
        dir.path.startsWith(userHome.path + File.separator)
}
