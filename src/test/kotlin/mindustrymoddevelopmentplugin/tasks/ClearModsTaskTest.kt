package mindustrymoddevelopmentplugin.tasks

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import mindustrymoddevelopmentplugin.dsl.ArtifactNaming
import mindustrymoddevelopmentplugin.dsl.MindustryRunConfig
import org.junit.jupiter.api.Test

/**
 * Matching rule of `clearMods`.
 *
 * It used to be `name.contains(modName, ignoreCase = true)`, so mod `my` also deleted the files of mod
 * `my-mod` — a destructive cross-mod mistake in multi-project builds.
 */
class ClearModsTaskTest {

    private val format = "{name}-{version}.{build_count}"

    private val values = ArtifactNaming.Values(name = "my", author = "me", version = "1.0")
    private val SUFFIXES = listOf("-Jar", "-Android", "")

    /** The tag the plugin deploys with by default; the tests must follow it if it ever changes. */
    private val TAG = MindustryRunConfig.DEFAULT_DEPLOY_TAG
    private val PREFIX = "[$TAG]"

    @Test
    fun `a shorter mod name does not match a longer one`() {
        val pattern = ClearModsTask.deployedJarPattern(TAG, values, format, SUFFIXES)

        assertTrue(pattern.containsMatchIn("${PREFIX}my-1.0.5-Jar.jar"), "its own artifact must match")
        assertFalse(pattern.containsMatchIn("${PREFIX}my-mod-1.0.5-Jar.jar"), "another mod's artifact must survive")
        assertFalse(pattern.containsMatchIn("${PREFIX}my-mod-1.0.5-Android.jar"))
    }

    @Test
    fun `the longer name still matches its own artifacts`() {
        val pattern = ClearModsTask.deployedJarPattern(TAG, values.copy(name = "my-mod"), format, SUFFIXES)

        assertTrue(pattern.containsMatchIn("${PREFIX}my-mod-1.0.5-Jar.jar"))
        assertTrue(pattern.containsMatchIn("${PREFIX}my-mod-1.0.5-Android.jar"))
        // Case differences in the mod name must not stop the cleanup.
        assertTrue(ClearModsTask.deployedJarPattern(TAG, values.copy(name = "MyMod"), format, SUFFIXES).containsMatchIn("${PREFIX}mymod-1.0-Jar.jar"))
    }

    @Test
    fun `a longer name that continues with a digit is not this mod's file`() {
        // The regression: `{version}` used to allow `-`, so mod `my` matched `my-2nd-1.0.3.jar`.
        val pattern = ClearModsTask.deployedJarPattern(TAG, values, format, SUFFIXES)

        assertFalse(pattern.containsMatchIn("${PREFIX}my-2nd-1.0.3.jar"), "another mod's file must survive")
        assertFalse(pattern.containsMatchIn("${PREFIX}my-2nd-1.0.3-Jar.jar"))
        assertTrue(pattern.containsMatchIn("${PREFIX}my-1.0.3.jar"), "its own artifact still matches")
    }

    @Test
    fun `the tag prefix is required`() {
        val pattern = ClearModsTask.deployedJarPattern(TAG, values.copy(name = "my-mod"), format, SUFFIXES)

        assertFalse(pattern.containsMatchIn("my-mod-1.0.5-Jar.jar"), "only files this plugin deployed")
        assertFalse(pattern.containsMatchIn("[x]my-mod-1.0.5-Jar.jar"), "a different tag belongs to another tool")
    }

    @Test
    fun `a name glued to a placeholder still needs the boundary to look like that placeholder`() {
        val glued = ClearModsTask.deployedJarPattern(TAG, values, "{name}{version}", SUFFIXES)

        assertTrue(glued.containsMatchIn("${PREFIX}my1.0.0.jar"), "the version starts with a digit")
        assertFalse(glued.containsMatchIn("${PREFIX}my-mod1.0.0.jar"), "a longer name does not start with a digit")
    }

    @Test
    fun `a format that is only the name anchors on the configured suffixes`() {
        val nameOnly = ClearModsTask.deployedJarPattern(TAG, values, "{name}", SUFFIXES)

        // The format produces exactly "<name><suffix>.jar", so those files are the only candidates.
        assertTrue(nameOnly.containsMatchIn("${PREFIX}my.jar"), "the deploy artifact has no suffix")
        assertTrue(nameOnly.containsMatchIn("${PREFIX}my-Jar.jar"))
        assertTrue(nameOnly.containsMatchIn("${PREFIX}my-Android.jar"))
        assertFalse(nameOnly.containsMatchIn("${PREFIX}my-mod.jar"), "a longer mod name is not this mod's file")
        assertFalse(nameOnly.containsMatchIn("${PREFIX}my7.jar"), "this format never appends a bare digit")
    }
}
