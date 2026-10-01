package mindustrymoddevelopmentplugin

import java.io.File
import mindustrymoddevelopmentplugin.dsl.HostPlatform
import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Resolution order of the game's data directory.
 *
 * `MINDUSTRY_DATA_DIR` wins because the engine reads it too (v126+); otherwise the per-OS application
 * data directory is used, which is where the game writes when nothing overrides it.
 */
class GameDataDirTest {

    @Test
    fun `the environment variable wins over the per-OS directory`() {
        val resolved = GameDataDir.resolve(
            env = "/tmp/custom-mindustry",
            osName = "Mac OS X",
            userHome = "/Users/someone",
        )

        assertEquals(File("/tmp/custom-mindustry"), resolved)
    }

    @Test
    fun `a blank environment variable counts as unset`() {
        val resolved = GameDataDir.resolve(env = "   ", osName = "Linux", userHome = "/home/someone")

        assertEquals(File("/home/someone/.local/share/Mindustry"), resolved)
    }

    @Test
    fun `each supported OS gets the directory the game uses`() {
        assertEquals(
            File("/Users/someone/Library/Application Support/Mindustry"),
            GameDataDir.resolve(env = null, osName = "Mac OS X", userHome = "/Users/someone"),
        )
        assertEquals(
            File("/home/someone/.local/share/Mindustry"),
            GameDataDir.resolve(env = null, osName = "Linux", userHome = "/home/someone"),
        )
        assertEquals(
            File("C:/Users/someone/AppData/Roaming/Mindustry"),
            GameDataDir.resolve(env = null, osName = "Windows 11", userHome = "C:/Users/someone", appData = "C:/Users/someone/AppData/Roaming"),
        )
        // Without APPDATA the roaming directory is derived from the user home.
        assertEquals(
            File("C:/Users/someone/AppData/Roaming/Mindustry"),
            GameDataDir.resolve(env = null, osName = "Windows 11", userHome = "C:/Users/someone", appData = null),
        )
    }

    @Test
    fun `an unknown OS fails with the property to set`() {
        val error = assertThrows<GradleException> {
            GameDataDir.resolve(env = null, osName = "Plan9", userHome = "/home/someone")
        }

        assertTrue(error.message!!.contains("Plan9"), error.message!!)
        assertTrue(error.message!!.contains("gameDataDir"), error.message!!)
    }

    @Test
    fun `termux resolves to the Android external files directory`() {
        // The APK's own choice: AndroidLauncher sets the data directory to getExternalFilesDir(null), and
        // nothing can override it. `os.name` is "Linux" there too, which is why this branch comes first.
        assertEquals(
            File("/storage/emulated/0/Android/data/io.anuke.mindustry/files"),
            GameDataDir.resolve(
                osName = "Linux",
                userHome = "/data/data/com.termux/files/home",
                hostPlatform = HostPlatform.Android,
            ),
        )
    }

    @Test
    fun `the android app id is configurable for the BE build`() {
        assertEquals(
            File("/storage/emulated/0/Android/data/io.anuke.mindustry.be/files"),
            GameDataDir.resolve(
                osName = "Linux",
                hostPlatform = HostPlatform.Android,
                androidAppId = "io.anuke.mindustry.be",
            ),
        )
    }

    @Test
    fun `an explicit environment variable still wins on android`() {
        assertEquals(
            File("/tmp/forced"),
            GameDataDir.resolve(
                env = "/tmp/forced",
                osName = "Linux",
                hostPlatform = HostPlatform.Android,
            ),
        )
    }

    @Test
    fun `a plain linux desktop is not treated as android`() {
        assertEquals(
            File("/home/someone/.local/share/Mindustry"),
            GameDataDir.resolve(
                osName = "Linux",
                userHome = "/home/someone",
                hostPlatform = HostPlatform.Desktop,
            ),
        )
    }
}
