package mindustrymoddevelopmentplugin.wiring

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The default staging directory: shared storage when the game's picker can see it, the private fallback
 * otherwise.
 */
class AndroidStagingTest {

    private val home = File("/data/data/com.termux/files/home")
    private val shared = File("/sdcard/Download")

    @Test
    fun `the shared download directory is the default`() {
        assertEquals(shared, AndroidStaging.defaultDir(home, shared) { true })
    }

    @Test
    fun `an unusable shared volume falls back to the termux-private directory`() {
        assertEquals(File(home, "AndroidStaging"), AndroidStaging.defaultDir(home, shared) { false })
    }

    @Test
    fun `the fallback is recognized so a run can explain itself`() {
        assertTrue(AndroidStaging.isPrivateFallback(File(home, "AndroidStaging"), home))
        assertTrue(!AndroidStaging.isPrivateFallback(shared, home))
        // A path that merely starts with the home directory's *name* is not inside it.
        assertTrue(!AndroidStaging.isPrivateFallback(File("/data/data/com.termux/files/home2"), home))
    }
}
